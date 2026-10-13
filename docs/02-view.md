# 02 视图模块设计

## 1. 定位与边界

**视图（View）= 对某个数据源的一次具名取数定义**，对外暴露一组带类型的字段，对内屏蔽"数据怎么取"。

```
视图负责：连得上、取得到、字段类型说得清、取不到时有明确的降级行为
视图不负责：加工、聚合、跨数据源关联
```

### 明确划界

| 能力 | 视图 | 特征 | 说明 |
|---|---|---|---|
| 连接数据源、执行取数 | ✅ | ❌ | |
| 字段类型探测与声明 | ✅ | ❌ | |
| 1:1 字段暴露 | ✅ | ✅（基础特征） | 视图暴露技术字段，基础特征给业务语义 |
| 字段加工（计算/转换） | ❌ | ✅ | 加工只能在特征层，视图保证"原始语义" |
| 多行聚合（count/sum） | ❌ | ✅ | 视图只负责把 LIST 原样交出 |
| 跨源关联（join） | ❌ | ✅ | 在特征层用 SQL 做 |
| 缓存 / 熔断 / 降级 | ✅ | ✅ | 两层都有，视图层是数据级，特征层是计算级 |

> **为什么视图不做聚合**：如果视图层允许聚合，字段就变成了"依赖入参才有意义的表达式"，字段清单不再稳定，特征层的类型推导、血缘、缓存 key 全部要重新设计。**保持视图字段是"纯列"**，是整套模型稳定的基石。多行场景通过 `outputShape=LIST` 显式表达，由特征层的聚合算子消费。

## 2. 视图元模型

### 2.1 ViewDef（视图定义）

| 属性 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `viewCode` | string(64) | ✅ | 租户内唯一，`^[a-z][a-z0-9_]{2,63}$`，创建后不可改 |
| `viewName` | string(128) | ✅ | 中文展示名 |
| `viewType` | enum | ✅ | `MYSQL` / `CSV` / `HTTP` / `PMML` / （P1: `REDIS` `ES` `HIVE` `DUBBO`） |
| `outputShape` | enum | ✅ | `SCALAR` / `SINGLE` / `LIST`，见 §2.3 |
| `bizDomain` | string | | 业务域（信贷/支付/营销…），用于检索与权限 |
| `description` | text | | 业务口径说明 |
| `inputs` | InputParam[] | | 取数入参声明，见 §2.4 |
| `fields` | ViewField[] | ✅ | 字段清单，见 §2.2 |
| `typeConfig` | JSON | ✅ | 各类型专有配置，见 §3 |
| `cachePolicy` | JSON | | 缓存与降级策略，见 §5 |
| `status` | enum | ✅ | `DRAFT` / `ONLINE` / `OFFLINE` |
| `owner` | userId | ✅ | 责任人，影响面变更时通知对象 |
| `tags` | string[] | | |

### 2.2 ViewField（字段定义）

| 属性 | 类型 | 说明 |
|---|---|---|
| `fieldCode` | string | 视图内唯一，`snake_case`，供 `view.xxx.field` 引用 |
| `fieldName` | string | 中文名 |
| `dataType` | enum | 平台归一类型，见 §2.5 |
| `semanticType` | enum | 语义类型，见 §2.6 |
| `sourcePath` | string | 数据源中的定位路径：列名 / JSONPath / CSV 列序号 / PMML 字段名 |
| `nullable` | boolean | 是否可能为空（影响空值策略与表达式校验） |
| `isPrimaryKey` | boolean | SINGLE 视图必须且仅有一个主键字段 |
| `isRequestKey` | boolean | 该字段是否可作为取数入参（如 mobile、idCard） |
| `defaultValue` | string | 字段级默认值（取不到数据时的填充） |
| `dictCode` | string | 关联字典（枚举语义） |
| `unit` | string | 单位（元/分/天/次） |
| `sensitivity` | enum | `PUBLIC` / `INTERNAL` / `SENSITIVE` / `PII`，决定脱敏与权限 |
| `precision` | int | 小数位数（DECIMAL 类型） |
| `sampleValues` | string[] | 采样值，供配置台提示 |
| `description` | text | 业务含义 |

### 2.3 outputShape：输出形态（关键概念）

| 形态 | 含义 | 典型场景 | 消费方式 |
|---|---|---|---|
| `SCALAR` | 单值，非表结构 | 一个数、一个字符串 | 可建基础特征 |
| `SINGLE` | 单行记录（1 主键 → 1 行） | 用户基本信息表按 userId 查 | 字段可直接建基础特征 |
| `LIST` | 多行记录（1 主键 → N 行） | 借款申请流水按 userId 查 | **字段不能直接建基础特征**，必须经聚合算子 |
| — | — | — | — |

> `LIST` 与 `SINGLE` 的区别不是"实现细节"，而是**契约级别的类型信息**。配置台在用户尝试用 LIST 视图字段建基础特征时，必须直接拦下来并引导"去创建聚合特征"。

### 2.4 InputParam（取数入参）

| 属性 | 说明 |
|---|---|
| `paramCode` | 参数名，与 `param.xxx` 对应 |
| `dataType` | 类型 |
| `required` | 是否必填（必填参数缺失 → 视图直接失败，不发起真实请求） |
| `source` | `REQUEST_PARAM`（来自决策入参） / `UPSTREAM_VAR`（来自变量池的其他变量） |
| `defaultValue` | 缺省值 |
| `validators` | 校验规则：长度、正则、枚举、范围（在**取数前**执行，防止脏参数打穿数据源） |
| `sensitive` | 是否敏感（日志脱敏） |

### 2.5 类型系统归一

各数据源原始类型统一映射到平台类型，避免上层做方言适配：

| 平台类型 | MySQL | JSON(HTTP) | CSV 推断 | PMML |
|---|---|---|---|---|
| `BIGINT` | BIGINT / INT | 整数 | 全整数 | integer |
| `DECIMAL` | DECIMAL(p,s) | 小数/字符串数字 | 含小数点 | double |
| `DOUBLE` | DOUBLE / FLOAT | 浮点 | | double |
| `STRING` | VARCHAR / TEXT | string | 兜底 | string |
| `BOOLEAN` | TINYINT(1) | boolean | true/false | boolean |
| `DATE` | DATE | `yyyy-MM-dd` | 格式匹配 | date |
| `DATETIME` | DATETIME / TIMESTAMP | ISO8601 / epoch(ms) | | |
| `ARRAY<T>` | JSON 列 | array | | |
| `OBJECT` | JSON 列 | object | | |

**统一约定**（消除在线离线不一致的根源）：
- 金额：统一 `DECIMAL`，禁止用 double 做金额运算
- 时间：内部统一 `long` epoch millis（UTC），展示层做时区转换；禁止裸用 `java.util.Date`
- 空值：统一 `null`，禁止用 `0` / `""` / `"NULL"` 表示空
- 布尔：MySQL `TINYINT(1)` 归一为 `BOOLEAN`

### 2.6 语义类型（semanticType）

语义类型不是类型系统的装饰，它是**三个功能的开关**：

| 语义类型 | 作用 |
|---|---|
| `ID` | 主键类，可用于关联、去重 |
| `PHONE` / `ID_CARD` / `BANK_CARD` / `EMAIL` / `NAME` | ① 展示脱敏规则 ② 表达式内置校验函数（`isValidIdCard()`） ③ 日志脱敏 |
| `AMOUNT` | 金额单位统一、精度校验、金额格式化 |
| `RATE` | 比率（0~1 或 0~100 的歧义预警） |
| `ENUM` | 必须绑定 `dictCode`，交叉矩阵分箱时走枚举分组 |
| `DATETIME` | 时间窗口算子可用 |
| `COUNT` / `DURATION` | 聚合语义提示 |
| `CUSTOM` | 自定义，无语义能力 |

## 3. 各类型适配器设计

### 3.1 通用适配器 SPI

```java
public interface ViewAdapter {
    ViewType supportType();
    // 编译期：校验配置合法性，探测字段元数据
    ViewMeta inspect(ViewDef def);
    // 运行期：执行取数
    ViewResult fetch(ViewDef def, FetchParam param, Deadline deadline);
}
```

所有适配器共享的横切能力（在抽象基类中实现，各适配器不重复造轮子）：
- 超时控制（连接超时 / 读超时 / 全局 deadline 传递）
- 重试（仅对幂等读操作，指数退避，最多 N 次）
- 熔断（按 `tenant+view` 维度，滑动窗口错误率）
- 限流（按 `tenant+datasource` 维度，保护下游数据库）
- 缓存（L1 Caffeine → L2 Redis，见 §5.1）
- 降级（失败时按 `cachePolicy.fallbackMode` 处理，见 §5.2）
- Metrics（耗时、成功率、超时率、熔断次数、缓存命中率）

### 3.2 MySQL 视图

#### 配置项

| 配置 | 说明 |
|---|---|
| `dsId` | 绑定的数据源连接 |
| `mode` | `TABLE`（选表，可视化点选字段） / `SQL`（手写 SQL） |
| `tableName` / `sql` | 取数语句 |
| `fetchKey` | 主键字段（SINGLE 模式必填，决定 WHERE 条件） |
| `paramMapping` | 入参 → SQL 占位符的映射 |
| `maxRows` | 最大返回行数（默认 1，LIST 默认 1000） |
| `timeoutMs` | 查询超时（默认 500ms） |
| `slowSqlThreshold` | 慢 SQL 告警阈值 |

#### 安全设计（重点）

| 风险 | 对策 |
|---|---|
| SQL 注入 | **只允许 `#{param}` 预编译占位符，禁止 `${}` 字符串拼接**（保存时静态扫描，出现 `${` 直接拒绝）；参数字面量走类型化绑定 |
| DDL/DML 越权 | Calcite 解析 AST，强制校验：根节点必须是 `SELECT`；禁止出现 `INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/GRANT`；禁止多语句（`;` 分隔） |
| 全表扫描拖垮生产库 | 强制 `maxRows` 上限 + 强制 `LIMIT`（未写则由引擎追加）；SINGLE 模式强制要求 `fetchKey` 走索引（配置台提示执行计划） |
| 越权访问其他租户数据 | 数据源连接按租户隔离，物理上不共享账号 |
| 敏感数据泄漏到日志 | 执行日志只记录 SQL 模板（占位符形态）与参数名，不记录参数值；字段级 `sensitivity` 决定结果脱敏 |
| 长事务/锁等待 | 只读账号 + 自动提交 + 超时中断 |

#### 字段自动发现

```
用户填入 SQL ──▶ Calcite 解析 ──▶ 提取 SELECT 输出列 + 推导类型
                              ──▶ 与已声明字段 diff
                              ──▶ 配置台展示"新增/删除/类型变更"清单
                              ──▶ 用户确认后写入字段定义
```

**变更引导**：如果 SQL 改后删掉了某个字段，而该字段正被特征引用 → 保存时**硬阻断**，并列出受影响的特征和策略（见 §6 影响面分析）。

#### 连接与数据源管理

| 项 | 设计 |
|---|---|
| 连接串存储 | AES-256-GCM 加密，密钥按租户派生，DB 中不出现明文 |
| 连接池 | 按 `tenant + dsId` 独立 HikariCP，池大小 = `min(10, 租户配额共享额度)`，避免一个慢数据源占满全局池 |
| 健康检查 | 定时 `SELECT 1` + 池状态指标，异常自动摘除并告警 |
| 账号要求 | **平台侧强制要求使用只读账号**；配置台在测试连接时校验（尝试执行一条 `SELECT 1`，并在可能的情况下检查 `SUPER` / `FILE` 权限） |

### 3.3 CSV / 文件视图

文件视图的本质是**「把小数据集加载成可查询的内存表」**。

#### 配置项

| 配置 | 说明 |
|---|---|
| `fileRef` | 文件引用（OSS 路径 + 版本号 + 校验和） |
| `fileFormat` | `CSV` / `TSV` / `EXCEL` |
| `delimiter` / `encoding` | 分隔符、编码（默认 `,` / `UTF-8`，兼容 GBK） |
| `headerRow` | 表头行号（允许无表头 + 手工指定列） |
| `fieldMapping` | 文件列 → 视图字段映射（列名/序号），支持类型转换与格式串 |
| `keyFields` | 主键（决定能否做 SINGLE 查询；复合主键支持） |
| `indexMode` | `HASH`（主键等值查询，O(1)） / `FULL_SCAN`（无主键，仅支持全量导出） |
| `loadStrategy` | `EAGER`（服务启动/文件上传后立即加载） / `LAZY`（首次访问加载） |
| `refreshPolicy` | `MANUAL` / `CRON`（定时从 OSS 拉最新版本） / `ON_UPLOAD` |
| `maxFileSize` | 上限（默认 200MB，超限拒绝并提示改用 MySQL/Hive） |

#### 关键设计

**1）文件版本化**。上传新文件不覆盖旧文件，生成新版本号，视图指向某个版本。支持"预览新版本差异 → 切换 → 回滚"。文件版本与视图版本是两条独立的版本线，视图版本记录自己引用的是哪个文件版本。

**2）加载策略与内存**
| 场景 | 方案 |
|---|---|
| < 10MB | 全量加载为 `Map<Key, Row>`，常驻内存（Caffeine，弱引用 + size 上限） |
| 10MB ~ 200MB | 堆外存储（`Chronicle Map` / 自研紧凑数组），避免 GC 压力 |
| > 200MB | 拒绝，引导改用 MySQL 或 Hive 视图 |

**3）多值行 → LIST 语义**。文件里一个主键对应多行时，`outputShape=LIST`，引擎按主键分组返回多行。

**4）热更新原子性**。新文件加载完成后才切换引用（`AtomicReference`），加载失败保留旧版本并告警 —— 保证"刷新失败不会导致视图不可用"。

**5）数据质量校验**。加载时统计：总行数、空值率、类型转换失败行数与样例、主键重复行。结果作为"文件体检报告"展示，用户确认后才生效。

### 3.4 HTTP 视图

外数对接是风控最典型的场景（征信、反欺诈、黑名单、多头）。

#### 配置项

| 配置 | 说明 |
|---|---|
| `url` | 支持 `#{param}` 占位符 |
| `method` | `GET` / `POST` / `PUT` |
| `headers` | 键值对，值支持占位符与内置函数（`#{sign(...)}`、`#{timestamp()}`） |
| `bodyTemplate` | 请求体模板（JSON / FORM），支持占位符与条件片段 |
| `paramLocation` | 参数放置位置：`QUERY` / `BODY` / `PATH` / `HEADER` |
| `authType` | `NONE` / `BASIC` / `BEARER` / `AK_SK_HMAC` / `CUSTOM_SIGN` |
| `authConfig` | 凭据（加密存储） + 签名算法配置（拼接规则、摘要算法、时间戳、nonce） |
| `connectTimeoutMs` / `readTimeoutMs` | 分开配置（外数常见连接快、响应慢） |
| `retryPolicy` | 重试次数、退避策略。**仅对幂等的查询接口启用**，且需用户显式勾选"该接口幂等" |
| `successCriteria` | 成功判定：`httpStatus` 白名单 + 业务码 JSONPath（如 `$.code == "0000"`） |
| `responsePath` | 响应解析：字段 → JSONPath 映射（如 `mobile → $.data.phone`） |
| `responseSizeLimit` | 响应体大小上限（防大响应打爆内存，默认 2MB） |
| `mockResponse` | Mock 响应样例，用于无依赖调试 |

#### 关键设计

**1）响应裁剪**。只按 `fields` 声明解析需要的 JSONPath，其余字段直接丢弃 —— 降低内存占用，也天然减少敏感数据落地。

**2）失败语义必须显式**。HTTP 视图的失败判定要区分三类，配置台强制用户分别选择行为：

| 失败类型 | 示例 | 可选行为 |
|---|---|---|
| 传输失败 | 连接超时、5xx、DNS 失败 | `降级默认值` / `视为无数据(null)` / `中断决策并拒绝` |
| 业务失败 | 业务码表示"查无此人"、"已过期" | 同上，但通常选 `null` 语义（查无此人 ≠ 系统故障） |
| 解析失败 | JSONPath 不存在、类型转换失败 | 同上，通常选 `告警 + null` |

> 这三类混淆是外数对接的经典 bug：把"查无此人"当系统故障走降级，或把"系统超时"当"查无此人"放行。**平台通过类型化的失败判定 API 强制区分。**

**3）签名与鉴权**。平台内置常见签名算法（HMAC-SHA256 拼接式、MD5 加盐式），并支持"自定义签名脚本"（用表达式引擎写）。凭据独立加密存储，配置台展示时永久掩码，**不提供"查看明文"功能**。

**4）超时与并发控制**。外数提供商通常有并发上限，因此：
- 按 `tenant + view` 维度做并发信号量控制
- 超时设置保守（默认读超时 300ms），配合熔断快速失败
- 熔断打开时直接走降级，不发起真实请求

**5）请求日志与脱敏**。记录：URL（占位符形态）、耗时、响应码、响应大小。**不记录**：请求/响应体的敏感字段值、鉴权头。日志中字段按 `sensitivity` 自动掩码（手机号 `138****8888`）。

### 3.5 PMML 视图（P1）

PMML 视图是"特殊视图"——它既是数据源，也是**一次模型计算**。设计上把它归入视图层，因为它在决策里的角色就是"提供一批输出字段（score / probability）"。

| 配置 | 说明 |
|---|---|
| `pmmlFile` | PMML 文件引用（版本化） |
| `modelInfo` | 解析出的模型元数据：模型名、算法、DataDictionary |
| `inputMapping` | PMML 的 `MiningField` → 平台变量（`feat.xxx` / `param.xxx`）的映射 |
| `outputMapping` | PMML 的输出字段 → 视图字段（`score` → `fieldCode`） |
| `execMode` | `IN_PROCESS`（JPMML 本地加载，快，推荐） / `REMOTE`（调用外部评分服务，便于统一模型部署） |
| `warmup` | 是否启动时预加载模型 |

**关键设计**：
- 模型文件与视图定义**同版本管理**：模型换版 = 视图新版本，可灰度、可回滚
- **模型卡**必填：算法、训练时间、训练样本量、离线指标（AUC/KS）、负责人。风控合规要求"每个上线的模型可解释、可追溯"
- 输入字段缺失时，PMML 引擎行为不一致 → 平台层统一为"缺失字段用视图字段的 `defaultValue` 填充，并打标记"
- 与开关：模型分数可作为特征（`feat.model_score`），参与决策节点阈值判定或交叉矩阵分箱

### 3.6 后续类型（P1/P2）

| 类型 | 定位 | 特殊点 |
|---|---|---|
| `REDIS` | KV 快查（黑名单、缓存值） | 强依赖 key 设计，超时极短（10ms） |
| `ES` | 模糊/多条件检索 | 查询 DSL 可视化配置，结果集裁剪 |
| `HIVE` / `SPARKSQL` | 离线宽表取数 | 延迟高（秒级），仅离线回放使用，在线禁用 |
| `DUBBO` / `GRPC` | 内部服务 RPC | 需服务发现集成，接口契约导入 |
| `KAFKA_STREAM` | 流式特征源（P2） | 与实时特征计算配套，需要状态存储 |

## 4. 视图通用能力

### 4.1 配置态能力

| 能力 | 说明 |
|---|---|
| **字段自动探测** | 从 SQL 元数据 / 文件表头 / JSON 样例 / PMML DataDictionary 生成字段清单 |
| **样例驱动建视图** | 上传一份样例数据（CSV / JSON），自动生成字段定义 + 类型推断 + 语义推断（如识别手机号） |
| **连通性测试** | 一键测试能否取到数，返回耗时与原始响应（脱敏后） |
| **取数预览** | 填参数 → 真实取数 → 展示结果表格 + 每字段样例值 + 耗时 + 命中缓存情况 |
| **字段级试算** | 单选一个字段验证其解析是否正确（HTTP 视图调试常用） |
| **Mock 模式** | HTTP 视图可在未联调时用 mockResponse 走通全链路，便于特色化开发 |
| **配置校验** | 保存时：字段唯一性、主键存在性、参数被引用、类型完整、必填项齐全 |
| **命名规范校验** | `viewCode` / `fieldCode` 正则校验，避免 `test1`、`temp` 这类命名 |

### 4.2 版本与变更

| 变更类型 | 处理 |
|---|---|
| 新增字段 | 兼容变更，允许直接发布 |
| 修改字段类型 | **不兼容**，需新建视图或走"字段迁移"流程（提示影响面） |
| 删除字段 | **不兼容**，硬阻断直到所有引用方解绑 |
| 修改取数逻辑（SQL/URL） | 兼容但语义可能变 → 触发"口径变更"标记，通知所有下游特征责任人 |
| 修改主键 | **不兼容**，同删除字段处理 |
| 数据源连接变更 | 兼容（不改契约），热更新连接池 |

**影响面分析（核心能力）**：基于 `DepRef` 血缘表，任一视图字段变更时，可反向查出：

```
view.risk_user_info.overdue_cnt
  ├── 基础特征 user_overdue_cnt
  │     └── 衍生特征 user_overdue_level  (SQL 引用)
  │           └── 策略 loan_apply_v3 → decision 节点 "逾期准入"
  │           └── 策略 loan_apply_v3 → 交叉树 "渠道×逾期"
  └── 衍生特征 user_risk_score  (Java 引用)
        └── 策略 credit_check_v2
```

配置台的"删除/修改"按钮旁必须显示影响面数字（如"被 3 个特征、2 个策略引用"），点击展开明细，可跳转。

### 4.3 权限与脱敏

| 项 | 设计 |
|---|---|
| 视图级权限 | `VIEW_READ` / `VIEW_WRITE` / `VIEW_DELETE`，按角色 + 业务域授权 |
| 字段级权限 | `PII` 级别字段需要额外的 `SENSITIVE_READ` 权限才能看到预览值 |
| 展示脱敏 | 配置台与日志中，`PHONE/ID_CARD/BANK_CARD/NAME/EMAIL` 自动脱敏；**脱敏在服务端完成**，前端拿不到原文 |
| 导出脱敏 | 回放报告导出时同样脱敏，且需审批（P1） |
| 使用审计 | 谁在什么时候预览/试算了哪个视图、传了什么参数（参数值脱敏），全量留痕 |

## 5. 缓存、熔断与降级

### 5.1 缓存设计

```
请求 ──▶ L1 Caffeine（本地，TTL 默认 60s，最大 10000 条）
            │ miss
            ▼
         L2 Redis（TTL 默认 300s，按 tenant+view+paramHash）
            │ miss
            ▼
         真实数据源
```

| 设计点 | 说明 |
|---|---|
| 缓存 key | `t:{tenantId}:v:{viewCode}:{viewVersion}:{hash(normalizedParams)}`。参数先规范化（去空格、大小写统一、时间格式统一）再 hash |
| 缓存粒度 | 按"视图 + 完整入参"缓存整个结果集（不是按字段），保证语义原子 |
| TTL 策略 | 视图级配置。时效性要求高的（黑名单）TTL 短或禁用；缓慢变化的（用户画像）TTL 长 |
| 防击穿 | 同 key 并发请求走 singleflight（本地 `ConcurrentHashMap<key, CompletableFuture>`），只有一个请求回源 |
| 防雪崩 | TTL 加随机抖动（±20%） |
| 主动失效 | 外部数据变更时，通过 API 或 MQ 通知平台失效指定 key（P1） |
| 可关闭 | `cachePolicy.enabled=false` 时完全绕过缓存 |

### 5.2 降级策略（视图级，强制显式配置）

```json
{
  "fallbackMode": "DEFAULT_VALUE | NULL | STALE_CACHE | FAIL_FAST",
  "defaultValues": { "overdue_cnt": "0", "risk_level": "UNKNOWN" },
  "staleCacheTtlMs": 3600000,
  "onFallback": "CONTINUE | ABORT"
}
```

| 模式 | 行为 | 适用 |
|---|---|---|
| `DEFAULT_VALUE` | 用字段默认值填充，继续执行 | 有明确安全默认值（如"多头次数"默认 0 表示未知时谨慎） |
| `NULL` | 字段置 null，继续执行 | 决策层已有空值分支兜底 |
| `STALE_CACHE` | 使用过期缓存（**注意：需评估陈旧数据的风险**） | 数据缓慢变化、且"有旧值好于无值" |
| `FAIL_FAST` | 立即中断本次决策，返回预定义拒绝结论 | 强依赖数据（如黑名单），取不到绝不能放行 |

> `onFallback` 与 `fallbackMode` 是两个维度：前者决定"整个决策是否继续"，后者决定"字段值怎么填"。两者组合出 8 种行为，配置台用"结果预览"的方式展示每种组合下的效果，避免用户理解错。

### 5.3 熔断

| 项 | 设计 |
|---|---|
| 粒度 | `tenant + viewId` |
| 触发 | 滑动窗口（60s）内错误率 > 50% 且请求数 ≥ 20 |
| 打开后 | 直接走降级策略，不发起真实请求，**记录降级指标** |
| 半开 | 30s 后放行 1 个探针请求，成功则关闭，失败则延长 |
| 告警 | 熔断打开立即告警（这是数据源故障信号） |

## 6. 视图列表与检索

- **检索维度**：名称/编码模糊搜索、类型、业务域、状态、责任人、标签、被引用次数
- **健康度视图**：按"取数成功率、平均耗时、熔断次数、缓存命中率"排序，一眼看出问题数据源
- **使用情况**：被多少个特征/策略引用，近 30 天调用量趋势
- **批量操作**：批量打标签、批量导出配置（JSON）、批量导入

## 7. 本章待确认项

- [ ] §3.3 CSV 视图的内存方案：堆内 vs 堆外，需要根据实际文件规模定。当前设计给出 200MB 上限，如果业务有更大大文件需求，需引入本地索引文件方案。
- [ ] §3.4 HTTP 视图是否需要支持"批量请求"（一次请求 N 个用户）？当前设计为单笔。批量会显著降低外数成本，但增加引擎复杂度（需在批次内做请求合并）。
- [ ] §5.1 缓存是否需要跨节点共享"删除/失效"能力（当前 L1 只有 TTL 过期）？如果外数要求强时效，需要引入 Redis Pub/Sub 做本地缓存失效广播。
- [ ] §4.3 PII 字段的预览是否需要"申请-审批-限时查看明文"流程？当前设计是彻底不给明文。
