# 06 数据模型与 API 设计

## 1. 数据库设计约定

| 项 | 约定 |
|---|---|
| 数据库 | MySQL 8.0，`utf8mb4` / `utf8mb4_0900_ai_ci` |
| 引擎 | InnoDB |
| 主键 | `id BIGINT UNSIGNED AUTO_INCREMENT` |
| 业务唯一键 | `uk_tenant_code (tenant_id, code)` —— **所有唯一索引必须包含 tenant_id** |
| 公共列 | `tenant_id`、`created_by`、`created_at`、`updated_by`、`updated_at`、`deleted`(tinyint 逻辑删除) |
| 乐观锁 | 需要并发编辑的表加 `rev INT`（如 `strategy_draft.rev`） |
| 时间 | `DATETIME(3)`，应用层统一 UTC 存储 |
| JSON | MySQL 原生 `JSON` 类型（可查询、可校验），但**不做复杂 JSON 内查询**，需要检索的字段冗余成列 |
| 大文本 | `MEDIUMTEXT`（脚本、SQL）；超大内容（Bundle）考虑对象存储 + DB 存引用 |
| 命名 | 表名 `snake_case` 单数；索引 `idx_*` / `uk_*` / `fk_*` |

### 1.1 关于"图/树/矩阵怎么存"的设计决策

| 方案 | 评价 |
|---|---|
| 关系表拆存储（node 表 + edge 表） | ❌ 读写都需要 join 重组；一个策略有几十个节点就是几十次查询；矩阵完全无法用关系表表达 |
| **JSON 快照 + 冗余检索列** | ✅ **采用**。整图作为 JSON 存一列，读一次拿全；需要检索的关系（引用了哪些特征）冗余到独立的血缘表 |
| 图数据库（Neo4j 等） | ❌ 引入新组件，收益不足以抵消运维成本 |

**核心组合**：`strategy_version.graph_json`（全量快照）+ `dep_ref`（血缘关系表）。

- 读：一次查询拿到整图
- 查影响面：`dep_ref` 用普通 SQL（带索引）就能查"哪些策略引用了这个特征"
- 一致性：写入时在同一事务里更新两者的关系

## 2. 表清单

| 域 | 表 | 说明 |
|---|---|---|
| 租户与权限 | `sys_tenant` | 租户 |
| | `sys_user` | 用户 |
| | `sys_role` | 角色 |
| | `sys_user_role` | 用户角色关联 |
| | `sys_resource_auth` | 资源授权（业务域级/资源级） |
| | `sys_tenant_quota` | 租户配额与用量 |
| 数据源 | `ds_connection` | 数据源连接 |
| 视图 | `view_def` | 视图定义（身份） |
| | `view_version` | 视图版本快照 |
| 字典 | `dict_def` / `dict_item` | 字典 |
| 分箱 | `bin_def` | 分箱组件（P1） |
| 特征 | `feature_def` | 特征定义（身份） |
| | `feature_version` | 特征版本快照 |
| | `feature_test_case` | 测试用例 |
| 策略 | `strategy_def` | 策略定义（身份） |
| | `strategy_draft` | 编辑中的草稿（可并发编辑，带 rev） |
| | `strategy_version` | 发布版本（**不可变**，含 graph_json） |
| | `strategy_bundle` | Bundle 快照（含依赖冻结） |
| | `release_record` | 发布记录 |
| | `gray_rule` | 灰度规则 |
| | `approval_record` | 审批记录 |
| 血缘 | `dep_ref` | 统一依赖/血缘边 |
| 运行 | `decision_log` | 决策日志（分表） |
| | `metric_rule_hit` | 规则命中统计（聚合表） |
| | `trace_span` | Trace（采样，可选存 DB） |
| 任务 | `batch_task` / `batch_task_result` | 批量回放任务与结果 |
| 审计 | `audit_log` | 操作审计 |

## 3. 关键表结构

### 3.1 `sys_tenant`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_code` | varchar(64) | 租户编码，全局唯一 |
| `tenant_name` | varchar(128) | |
| `status` | varchar(16) | `ACTIVE` / `SUSPENDED` / `CLOSED` |
| `crypto_key_ref` | varchar(128) | 租户密钥引用（KMS key id） |
| `contact` | varchar(128) | 联系人 |
| `expire_at` | datetime(3) | 到期时间（SaaS 计费） |

索引：`uk_tenant_code(tenant_code)`

### 3.2 `sys_tenant_quota`

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenant_id` | bigint | |
| `quota_json` | json | `{maxViews, maxFeatures, maxStrategies, qpsLimit, maxBatchSamples, logRetentionDays, scriptCpuMs}` |
| `used_json` | json | 实时用量（定时刷新） |

### 3.3 `ds_connection`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_id` | bigint | |
| `ds_code` | varchar(64) | 租户内唯一 |
| `ds_name` | varchar(128) | |
| `ds_type` | varchar(24) | `MYSQL` / `MYSQL_SHARDING` / `REDIS` / `ES` / `OSS` |
| `conn_config_enc` | mediumtext | **加密后的连接配置**（AES-256-GCM，密文） |
| `pool_config` | json | 池参数 `{maxPool, minIdle, connTimeoutMs, ...}` |
| `readonly_check` | tinyint | 是否通过只读校验 |
| `health_status` | varchar(16) | `UP` / `DOWN` / `UNKNOWN` |
| `last_check_at` | datetime(3) | |

索引：`uk_ds(tenant_id, ds_code)`

### 3.4 `view_def`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_id` | bigint | |
| `view_code` | varchar(64) | 租户内唯一，不可改 |
| `view_name` | varchar(128) | |
| `view_type` | varchar(24) | `MYSQL` / `CSV` / `HTTP` / `PMML` / ... |
| `output_shape` | varchar(16) | `SCALAR` / `SINGLE` / `LIST` |
| `biz_domain` | varchar(64) | 业务域 |
| `status` | varchar(16) | `DRAFT` / `ONLINE` / `OFFLINE` |
| `owner_id` | bigint | |
| `description` | text | |
| `tags` | json | 标签数组 |
| `current_version` | int | 当前线上版本号 |
| `latest_version` | int | 最新草稿版本号 |

索引：`uk_view(tenant_id, view_code)`、`idx_tenant_status(tenant_id, status)`、`idx_owner(tenant_id, owner_id)`

### 3.5 `view_version`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_id` / `view_id` | bigint | |
| `version` | int | 自增版本号 |
| `inputs_json` | json | 入参定义 |
| `fields_json` | json | **字段定义全量快照** |
| `type_config_json` | json | 类型专有配置（SQL / URL / 文件引用等） |
| `cache_policy_json` | json | 缓存与降级策略 |
| `checksum` | char(64) | 内容校验和 |
| `change_summary` | varchar(512) | 变更说明 |
| `status` | varchar(16) | `DRAFT` / `RELEASED` / `DEPRECATED` |

索引：`uk_view_version(tenant_id, view_id, version)`

> **字段定义放 JSON 而不是独立表**：字段是视图的组成部分，永远整取整存，不存在"只查某个字段"的需求（除了血缘，那由 `dep_ref` 承担）。放 JSON 能显著简化读写。

### 3.6 `feature_def` / `feature_version`

`feature_def`（身份表）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `tenant_id` | bigint | |
| `feature_code` | varchar(64) | 租户内唯一，不可改 |
| `feature_name` | varchar(128) | |
| `feature_type` | varchar(16) | `BASE` / `DERIVED` |
| `lang` | varchar(16) | `SQL` / `JAVA` / `PYTHON` / `OP`（基础特征为空） |
| `biz_domain` | varchar(64) | |
| `lifecycle` | varchar(16) | `EXPERIMENTAL` / `STABLE` / `DEPRECATING` / `DEPRECATED` |
| `status` | varchar(16) | |
| `owner_id` | bigint | |
| `tags` | json | |
| `current_version` / `latest_version` | int | |

`feature_version`（版本快照）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `tenant_id` / `feature_id` | bigint | |
| `version` | int | |
| `view_ref_json` | json | 基础特征用：`{viewCode, viewVersion, fieldCode}` |
| `script` | mediumtext | 衍生特征用 |
| `inputs_json` | json | 输入变量清单 |
| `output_def_json` | json | `{code, dataType, precision}` |
| `exec_mode` | varchar(16) | `ONLINE` / `OFFLINE` / `BOTH` |
| `timeout_ms` | int | |
| `cache_policy_json` | json | |
| `missing_policy` / `error_policy` | varchar(24) | |
| `default_value` | varchar(255) | |
| `biz_definition` | text | **业务口径说明（必填）** |
| `checksum` | char(64) | |
| `status` | varchar(16) | |

索引：`uk_feature(tenant_id, feature_code)`、`uk_feature_version(tenant_id, feature_id, version)`

### 3.7 `dep_ref`（血缘核心表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_id` | bigint | |
| `from_type` | varchar(24) | `VIEW` / `FEATURE` / `STRATEGY` |
| `from_id` | bigint | |
| `from_version` | int | 版本号（`0` 表示不区分版本） |
| `from_field` | varchar(128) | 视图字段级血缘（如 `overdue_cnt`），其余为空 |
| `to_type` | varchar(24) | `FEATURE` / `STRATEGY` / `NODE` |
| `to_id` | bigint | |
| `to_ref` | varchar(255) | 引用点标识（如节点 ID、输入别名） |

索引：
- `idx_from(tenant_id, from_type, from_id, from_version)` —— 查"我依赖了谁"
- `idx_to(tenant_id, to_type, to_id)` —— 查"谁依赖了我"（影响面分析）

**血缘写入时机**：视图/特征/策略保存草稿时写入（按草稿版本），发布时固化。血缘分析（影响面）查询时不区分草稿/发布，但标注来源。

### 3.8 `strategy_def` / `strategy_draft` / `strategy_version`

`strategy_def`：`strategy_code`、`strategy_name`、`biz_scene`、`status`、`owner_id`、`current_version`、`reusable`（是否可作为子策略）、`tags`。

`strategy_draft`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `tenant_id` / `strategy_id` | bigint | |
| `graph_json` | json | **当前编辑中的图** |
| `rev` | int | 乐观锁版本（防并发覆盖） |
| `last_editor` | bigint | |
| `updated_at` | datetime(3) | |

索引：`uk_draft(tenant_id, strategy_id)`

`strategy_version`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `tenant_id` / `strategy_id` | bigint | |
| `version` | int | |
| `graph_json` | json | **发布时冻结的全量图快照（不可变）** |
| `bundle_id` | bigint | 关联 Bundle |
| `input_contract_json` | json | 入参契约（对外承诺） |
| `output_contract_json` | json | 输出契约 |
| `checksum` | char(64) | |
| `change_summary` | varchar(512) | |
| `approved_by` / `approved_at` | bigint / datetime(3) | |

索引：`uk_strategy_version(tenant_id, strategy_id, version)`

### 3.9 `strategy_bundle`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `tenant_id` / `strategy_id` / `version` | bigint/int | |
| `bundle_json` | json | **全量快照**（图 + 依赖的特征/视图/字典/分箱/子策略的版本内容） |
| `bundle_size` | int | 序列化后大小（字节） |
| `checksum` | char(64) | SHA-256，引擎加载后校验 |
| `deps_summary` | json | 依赖清单摘要 `{features:[{code,version}], views:[...], bins:[...]}` |
| `built_at` / `built_by` | datetime(3) / bigint | |
| `archive_url` | varchar(512) | 冷归档地址（可选） |

> **Bundle 大小控制**：Bundle 内嵌所有依赖定义可能导致 JSON 很大（尤其视图字段多时）。优化：Bundle 只存"依赖的引用 + 校验和"，引擎按引用从各自的版本表加载。但这样会退化成"运行时按版本读多张表"，牺牲原子性。
>
> **推荐折中**：Bundle 存全量（保证原子性与可移植性），但超过阈值（如 2MB）时改为"核心图 + 依赖引用清单"，依赖的版本内容从 Redis 缓存加载（仍带校验和验证）。**P0 先用全量方案，简单可靠。**

### 3.10 `release_record` / `gray_rule`

`release_record`：

| 字段 | 说明 |
|---|---|
| `tenant_id` / `strategy_id` / `version` | |
| `env` | 环境：`PROD` / `PRE` / `TEST` |
| `release_type` | `GRAY` / `FULL` / `ROLLBACK` |
| `status` | `RELEASING` / `ACTIVE` / `STOPPED` / `ROLLED_BACK` |
| `prev_version` | 回滚用 |
| `released_by` / `released_at` | |
| `approval_id` | 关联审批单 |
| `ready_instances` / `total_instances` | 引擎实例就绪情况 |

`gray_rule`：

| 字段 | 说明 |
|---|---|
| `release_id` | |
| `rule_type` | `PERCENT` / `WHITELIST` / `CHANNEL` / `TAG` |
| `rule_config` | json：百分比值 / 名单 / 匹配条件 |
| `priority` | 多条规则时的优先顺序 |
| `effective_from` / `effective_to` | 生效时间窗 |

### 3.11 `approval_record`

`tenant_id`、`strategy_id`、`version`、`submit_by`、`submit_at`、`approval_mode`（`SINGLE`/`DOUBLE`）、`status`、`approvers`(json)、`opinions`(json)、`finished_at`。

### 3.12 `audit_log`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_id` / `operator_id` | bigint | |
| `operator_ip` | varchar(64) | |
| `action` | varchar(64) | `VIEW_CREATE` / `FEATURE_UPDATE` / `STRATEGY_RELEASE` / `DATA_PREVIEW` / ... |
| `resource_type` / `resource_id` / `resource_code` | | |
| `before_json` / `after_json` | json | 变更前后（大字段截断） |
| `detail` | json | 附加信息（如试算参数、导出范围） |
| `result` | varchar(16) | `SUCCESS` / `FAIL` |
| `created_at` | datetime(3) | |

索引：`idx_tenant_time(tenant_id, created_at)`、`idx_resource(tenant_id, resource_type, resource_id)`

### 3.13 `decision_log`（分表：按月）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | |
| `tenant_id` / `strategy_id` | bigint | |
| `strategy_code` | varchar(64) | 冗余，便于直接查询 |
| `version` | int | |
| `trace_id` | varchar(64) | |
| `biz_key` | varchar(128) | 业务主键（脱敏后或哈希） |
| `request_params` | json | 入参（脱敏） |
| `feature_snapshot` | json | 用到的特征值（可配置开关） |
| `decision_code` | varchar(32) | |
| `score` | int | |
| `reason_codes` | json | |
| `hit_path` | json | 命中节点路径 |
| `degrade_flags` | json | 降级标记 |
| `cost_ms` | int | |
| `created_at` | datetime(3) | |

索引：`idx_tenant_time(tenant_id, created_at)`、`idx_trace(trace_id)`、`idx_biz(tenant_id, biz_key, created_at)`

**分表策略**：按月分表（`decision_log_202610`），保留策略可配（默认热存 7 天，之后归档到对象存储）。

### 3.14 `metric_rule_hit`（规则命中聚合）

按 `(tenant_id, strategy_code, version, node_id, rule_id, stat_date)` 聚合的日级统计：`eval_count`、`hit_count`、`hit_rate`。

> **为什么单独建聚合表**：从 `decision_log` 里实时 group by 算"规则命中率"在亿级数据上不可行。异步预聚合是唯一可行方案。

### 3.15 `batch_task` / `batch_task_result`

`batch_task`：`tenant_id`、`strategy_code`、`version_a`、`version_b`（对比模式）、`task_type`（`REPLAY`/`COMPARE`）、`input_source`（`UPLOAD`/`DECISION_LOG`）、`input_ref`、`sample_count`、`status`、`progress`、`report_json`、`created_by`、`started_at`、`finished_at`。

`batch_task_result`：明细结果（可分表或落对象存储，大任务结果建议直接写 OSS，DB 只存引用）。

## 4. 管理端 API 设计

### 4.1 通用约定

| 项 | 约定 |
|---|---|
| 风格 | RESTful，`/api/v1/{resource}` |
| 认证 | `Authorization: Bearer <JWT>` |
| 租户 | 网关注入 `X-Tenant-Id`（前端不传） |
| 响应 | `{ code: "0", msg: "success", data: {...}, traceId: "..." }` |
| 分页 | `?page=1&size=20` → `data: { list: [], total, page, size }` |
| 错误 | `code` 非 `0` 即错误，`msg` 为用户可读文案，`detail` 为调试信息 |
| 幂等 | 写操作支持 `Idempotency-Key` header |
| 并发 | 草稿保存需带 `rev`，不匹配返回 `409` |

### 4.2 视图模块

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/views` | 列表（支持 code/name/type/status/domain/tag 筛选、排序） |
| POST | `/views` | 创建 |
| GET | `/views/{code}` | 详情（含字段、配置） |
| PUT | `/views/{code}/draft` | 保存草稿 |
| POST | `/views/{code}/validate` | 校验（不保存） |
| POST | `/views/{code}/test-connection` | 连通性测试 |
| POST | `/views/{code}/inspect` | 字段探测（SQL 解析 / 文件解析 / JSON 样例解析） |
| POST | `/views/{code}/preview` | 取数预览（真实取数，脱敏返回） |
| POST | `/views/{code}/publish` | 发布版本 |
| GET | `/views/{code}/versions` | 版本列表 |
| GET | `/views/{code}/diff?from=2&to=3` | 版本对比 |
| GET | `/views/{code}/impact` | 影响面（哪些特征/策略引用） |
| GET | `/views/{code}/health` | 健康度（耗时/成功率/缓存命中） |
| DELETE | `/views/{code}` | 删除（有引用时拒绝） |
| POST | `/datasources` / GET / PUT / DELETE | 数据源管理 |
| POST | `/datasources/{code}/test` | 数据源连通性测试 |

### 4.3 特征模块

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/features` | 列表（支持 type/lang/lifecycle/domain/tag 筛选） |
| POST | `/features` | 创建 |
| GET | `/features/{code}` | 详情 |
| PUT | `/features/{code}/draft` | 保存草稿 |
| POST | `/features/{code}/validate` | 校验（含脚本静态检查） |
| POST | `/features/{code}/preview` | 试算（手工输入 / 真实取数） |
| POST | `/features/{code}/test-cases/run` | 跑测试用例 |
| POST | `/features/{code}/batch-preview` | 批量试算（上传样本，返回统计） |
| POST | `/features/{code}/publish` | 发布 |
| GET | `/features/{code}/lineage` | 血缘（上下游） |
| GET | `/features/{code}/impact` | 影响面 |
| GET | `/features/{code}/health` | 健康度（调用量/空值率/耗时） |
| GET | `/features/dependency-graph?codes=a,b,c` | 依赖图（供配置台展示） |

### 4.4 决策模块

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/strategies` | 列表 |
| POST | `/strategies` | 创建 |
| GET | `/strategies/{code}` | 详情（含草稿图） |
| PUT | `/strategies/{code}/draft` | 保存草稿图（带 rev 乐观锁） |
| POST | `/strategies/{code}/validate` | 全量校验（返回错误/警告列表 + 定位到节点） |
| POST | `/strategies/{code}/auto-inject-deps` | 自动补齐依赖节点 |
| POST | `/strategies/{code}/trial` | **单笔试算** |
| POST | `/strategies/{code}/trial/node` | 单节点试算（调试单个节点） |
| POST | `/strategies/{code}/replay` | 批量回放（异步，返回 taskId） |
| GET | `/batch-tasks/{taskId}` | 任务状态与进度 |
| GET | `/batch-tasks/{taskId}/report` | 报告 |
| GET | `/batch-tasks/{taskId}/result` | 明细下载 |
| POST | `/strategies/{code}/compare` | 版本对比（结构 + 配置 diff） |
| GET | `/strategies/{code}/diff?from=2&to=3` | 版本差异详情 |
| POST | `/strategies/{code}/submit-approval` | 提交审批 |
| POST | `/approvals/{id}/approve` | 审批通过 |
| POST | `/approvals/{id}/reject` | 驳回 |
| POST | `/strategies/{code}/release` | 发布（可带灰度配置） |
| POST | `/strategies/{code}/rollback` | 回滚到指定版本 |
| PUT | `/strategies/{code}/gray` | 调整灰度规则 |
| GET | `/strategies/{code}/versions` | 版本列表 |
| GET | `/strategies/{code}/runtime-status` | 运行时状态（各实例版本就绪情况） |
| POST | `/strategies/{code}/shadow` | 开启影子模式 |
| GET | `/meta/variables?strategyCode=x` | **变量选择器数据源**（按当前图计算可用变量） |
| GET | `/meta/functions` | 函数库清单 |
| GET | `/meta/bins` / POST | 分箱组件 |
| GET | `/meta/dicts` / POST | 字典 |
| GET | `/audit-logs` | 审计日志查询 |
| GET | `/decision-logs` | 决策日志查询（支持 traceId / bizKey） |
| POST | `/decision-logs/{traceId}/replay` | 一键回放 |

## 5. 运行时 API（引擎）

### 5.1 单笔决策

```
POST /api/v1/decision/execute
Header: Authorization: Bearer <AK/SK 签发的 token>
        X-Tenant-Id: T001
        X-Request-Id: <调用方请求号，用于幂等与追踪>

Request:
{
  "strategyCode": "loan_apply_risk",
  "version": null,                  // 可选：指定版本（不传则按灰度路由）
  "bizKey": "U123456",              // 灰度路由与幂等键
  "params": {
    "userId": "U123456",
    "applyAmount": 50000,
    "channel": "APP",
    "mobile": "13800138000"
  },
  "options": {
    "returnFeatures": true,          // 是否返回特征快照
    "returnHitPath": true,           // 是否返回命中路径
    "debug": false                   // 是否强制全量 trace（需权限）
  }
}

Response (200):
{
  "code": "0",
  "data": {
    "traceId": "b7f3a1c2-...",
    "strategyCode": "loan_apply_risk",
    "version": 12,
    "decisionCode": "REJECT",
    "score": 85,
    "reasonCodes": [
      { "code": "RISK_OVERDUE_HIGH", "msg": "近90天逾期4次，超过准入阈值3次", "severity": "HIGH" }
    ],
    "outputs": { "suggestAmount": 0, "riskLevel": "HIGH", "route": "REJECT_AUTO" },
    "features": { "user_overdue_cnt": 4, "user_overdue_level": "H" },
    "hitPath": ["n_001", "n_002", "n_003", "n_005", "n_007"],
    "degradeFlags": [],
    "costMs": 82
  },
  "traceId": "b7f3a1c2-..."
}
```

### 5.2 错误响应

```
Response (200 with error code):
{
  "code": "50101",
  "msg": "策略不存在或未发布",
  "detail": "strategyCode=loan_apply_risk, tenantId=T001",
  "traceId": "..."
}
```

> **约定：业务错误用 200 + 业务码**（便于调用方统一处理），**系统错误用 5xx**（如网关超时、引擎不可用）。风控调用方通常只关心 `decisionCode`，网络层错误应视为"决策未完成"并触发调用方的兜底逻辑，两者语义清晰区分很重要。

### 5.3 批量决策

```
POST /api/v1/decision/batch
Request: { strategyCode, items: [{bizKey, params}, ...], options }
Response: { results: [{bizKey, code, data}, ...], costMs }

限制：单批最多 200 笔；超限返回错误码并提示拆分。
说明：批量接口内部仍是逐笔决策（共享 Plan/缓存），不是向量化计算。
```

### 5.4 其他接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/decision/async` | 异步决策（P1）：提交返回 taskId，结果回调或轮询 |
| GET | `/api/v1/strategies/{code}/metadata` | 策略元信息（入参契约、输出契约、当前版本） |
| GET | `/api/v1/strategies/{code}/health` | 策略健康（可用版本、实例就绪数） |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/ready` | 就绪探针（含 Bundle 加载状态） |
| GET | `/metrics` | Prometheus 指标 |

### 5.5 鉴权方案

| 场景 | 方案 |
|---|---|
| 内部调用（同 VPC） | JWT（网关签发） + 租户校验 |
| 外部调用方（SaaS 客户） | AK/SK 签名：`HMAC-SHA256(secret, method + path + timestamp + bodyHash)`，时间戳容差 5 分钟，防重放（nonce 缓存） |
| 权限粒度 | 调用方只能调用被授权的 `strategyCode` 列表 |

## 6. 错误码体系

| 段位 | 含义 | 示例 |
|---|---|---|
| `0` | 成功 | |
| `1xxxx` | 参数与请求错误 | `10001` 缺少必填参数；`10002` 参数类型错误；`10003` 请求体过大 |
| `2xxxx` | 认证与权限 | `20001` 未认证；`20002` 无权限；`20003` 租户不匹配；`20004` 租户已停用 |
| `3xxxx` | 视图相关 | `30001` 视图不存在；`30002` 视图未发布；`30003` 数据源连接失败；`30004` 取数超时；`30005` 视图已熔断；`30006` 数据源只读校验失败 |
| `4xxxx` | 特征相关 | `40001` 特征不存在；`40002` 特征未发布；`40003` 特征计算超时；`40004` 特征计算异常；`40005` 特征依赖缺失；`40006` 脚本编译失败 |
| `5xxxx` | 决策相关 | `50001` 策略不存在；`50002` 策略未发布；`50003` 策略图校验失败；`50004` 执行超时；`50005` 命中兜底结论；`50006` 变量未定义（运行期） |
| `6xxxx` | 配置与版本 | `60001` 版本不存在；`60002` 草稿版本冲突（rev 不匹配）；`60003` 发布中；`60004` 审批未通过 |
| `7xxxx` | 限流与配额 | `70001` 触发租户 QPS 限流；`70002` 配额超限 |
| `8xxxx` | 任务相关 | `80001` 回放任务失败；`80002` 样本格式错误；`80003` 任务被取消 |
| `9xxxx` | 系统错误 | `90001` 引擎内部错误；`90002` Bundle 加载失败；`90003` Plan 编译失败；`90004` 依赖服务不可用 |

> **错误码设计原则**：`5xxxx` 中的 `50005`（命中兜底结论）非常重要 —— 它表示"决策没有正常完成，返回的是兜底结果"。调用方可以据此决定是"接受兜底结论"还是"转其他处理"。**把"兜底"和"正常拒绝"混为一谈是风控系统的重大隐患。**

## 7. 本章待确认项

- [ ] §3.9 Bundle 存储方案：P0 用全量内嵌，需要评估实际 Bundle 大小（如果视图字段特别多可能超过几 MB）。
- [ ] §3.13 决策日志分表：是按月分表还是按天？取决于日志量。建议先按月，量大再按天。
- [ ] §5.1 是否需要提供 **SDK**（Java / Go / Python）封装鉴权、重试、批量？对 SaaS 客户体验价值高。
- [ ] §5.3 批量接口的"逐笔决策"是否够用？如果调用方是批量跑批场景（数十万笔），需要独立的**离线批量决策通道**（走 Worker，不占在线引擎）。
- [ ] §6 错误码需要与调用方约定"可重试"与"不可重试"的划分，补充到接入文档。
