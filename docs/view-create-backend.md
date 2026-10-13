# 新建视图（脚本 + $入参自动解析 + 手动出参）后端改造文档

> 前端已上线新的「新建视图」页面（路由 `/views/new`，替换原弹窗表单）。
> 用户在页面中填写数据源脚本（MySQL SQL / HTTP / CSV / PMML），脚本里 `$变量名` 会被前端实时解析为入参，出参由用户手动登记。
> **当前后端 `POST /api/views` 只接收 `DataSourceView` 实体字段，请求体里的 `scriptContent / inputs / outputs` 会被 Jackson 忽略，视图能创建但脚本和出入参不会落库**，需按本文档补齐。

---

## 一、前端交互约定

### 1. 页面字段
- 基本信息：`code`、`name`、`description`、`sourceType`(MySQL/HTTP/CSV/PMML)、`readMode`(SINGLE/LIST/SCALAR)、`owner`、`businessDomain`
- 取数脚本：一段文本（MySQL 为 SQL；HTTP/CSV/PMML 为配置文本），其中：
  - **入参占位符语法：`$name` 或 `${name}`**，标识符规则 `[a-zA-Z_][a-zA-Z0-9_]*`
  - 示例：`WHERE user_id = $user_id AND biz_date = $biz_date`
- 入参：变量名由脚本自动解析（前端不可改名、不可手加），用户只补充 `varType / required / defaultValue`
- 出参：用户手动添加，每行 `fieldName / description / fieldType / defaultValue`，至少 1 个，名称不可重复

### 2. 前端保存请求（已实现，直接发到 `POST /api/views`）

```json
{
  "code": "loan_overdue_stat_view",
  "name": "贷款逾期统计视图",
  "description": "口径描述",
  "sourceType": "MySQL",
  "readMode": "SINGLE",
  "owner": "王敏",
  "businessDomain": "loan",
  "scriptContent": "SELECT user_id, overdue_cnt, overdue_days FROM risk_user_info WHERE user_id = $user_id AND biz_date = $biz_date LIMIT 1",
  "inputs": [
    { "varCode": "user_id",  "varType": "STRING", "required": true,  "defaultValue": null },
    { "varCode": "biz_date", "varType": "STRING", "required": true,  "defaultValue": null }
  ],
  "outputs": [
    { "fieldName": "overdue_cnt",   "fieldType": "INTEGER", "description": "近90天逾期次数", "defaultValue": "0", "sort": 0 },
    { "fieldName": "overdue_level", "fieldType": "ENUM",    "description": "逾期等级",       "defaultValue": "L", "sort": 1 }
  ],
  "version": "v1",
  "status": "DRAFT",
  "refCount": 0,
  "fieldCount": 2,
  "piiCount": 0
}
```

类型枚举（入参/出参通用）：`STRING / INTEGER / LONG / DECIMAL / BOOLEAN / DATETIME / ENUM / JSON`

---

## 二、数据库变更（MySQL）

### 1. `data_source_view` 新增脚本列

```sql
ALTER TABLE data_source_view
    ADD COLUMN script_content LONGTEXT NULL COMMENT '取数脚本/配置：MySQL=SQL，HTTP=配置 JSON，CSV/PMML=配置 JSON（内嵌文件内容，不依赖服务器文件）'
    AFTER degrade_chain;
```

### 2. 新增视图入参表 `view_input_var`

```sql
CREATE TABLE view_input_var (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    view_id        BIGINT       NOT NULL COMMENT '关联 data_source_view.id',
    var_code       VARCHAR(64)  NOT NULL COMMENT '入参变量名（与脚本中 $name 对应）',
    var_type       VARCHAR(16)  NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INTEGER/LONG/DECIMAL/BOOLEAN/DATETIME/ENUM/JSON',
    required_flag  TINYINT      NOT NULL DEFAULT 1 COMMENT '1=必填 0=选填',
    default_value  VARCHAR(255) NULL COMMENT '未传参时的兜底值（按 var_type 转换）',
    sort           INT          NOT NULL DEFAULT 0,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_view_var (view_id, var_code),
    KEY idx_view_id (view_id)
) COMMENT='视图入参定义（脚本 $ 占位符解析结果）';
```

> 实体可参考已有的 [FeatureInputVar.java](file:///e:/AI/work/ictriskplatfrom/backend/src/main/java/com/riskstrategy/entity/FeatureInputVar.java)，字段比它多 `required_flag / default_value / sort`。

### 3. `view_field`（出参，复用现有表）补两列

现有 [ViewField.java](file:///e:/AI/work/ictriskplatfrom/backend/src/main/java/com/riskstrategy/entity/ViewField.java) 已有 `field_name / field_type / category / pii_level / masking / is_primary / sort`，新建视图登记的出参写入该表（`category = 'OUTPUT'`），需补：

```sql
ALTER TABLE view_field
    ADD COLUMN description   VARCHAR(255) NULL COMMENT '出参口径描述' AFTER field_type,
    ADD COLUMN default_value VARCHAR(255) NULL COMMENT '取数失败/为空时的兜底值（按 field_type 转换）' AFTER description;
```

实体 `ViewField` 同步加两个属性：

```java
private String description;
private String defaultValue;
```

> `pii_level / masking / is_primary` 新建时留空，后续在字段详情里维护即可，前端创建页不采集。

---

## 三、DTO 与接口改造

### 1. 新增 `ViewCreateRequest`（不要继续直接用实体接收）

```java
@Data
public class ViewCreateRequest {
    // 基本信息（同 DataSourceView）
    private String code;
    private String name;
    private String description;
    private String sourceType;   // MySQL / HTTP / CSV / PMML
    private String readMode;     // SINGLE / LIST / SCALAR
    private String owner;
    private String businessDomain;
    private String scriptContent;

    private List<InputVarDTO> inputs;
    private List<OutputFieldDTO> outputs;

    @Data
    public static class InputVarDTO {
        private String varCode;
        private String varType;
        private Boolean required;
        private String defaultValue;
    }

    @Data
    public static class OutputFieldDTO {
        private String fieldName;
        private String fieldType;
        private String description;
        private String defaultValue;
        private Integer sort;
    }
}
```

### 2. `POST /api/views` 改造（[ViewController.java](file:///e:/AI/work/ictriskplatfrom/backend/src/main/java/com/riskstrategy/controller/ViewController.java#L29-L32)）

- 入参由 `DataSourceView` 改为 `ViewCreateRequest`
- Service 方法整体加 `@Transactional`，顺序：
  1. 基础校验（沿用现有 code 正则、sourceType/readMode 枚举、code 唯一）
  2. `scriptContent` 非空校验
  3. **服务端重新用同一正则解析脚本占位符**（不能信前端传参）：
     ```java
     // 同时支持 $name 与 ${name}
     Pattern P = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}|\\$([a-zA-Z_][a-zA-Z0-9_]*)");
     ```
     - 请求里的 `inputs[].varCode` 必须与解析结果集合**完全一致**：多了报「入参 xxx 未在脚本中引用」，少了报「脚本中存在未登记入参 xxx」（建议直接以后端解析结果为准入库，前端传的类型/必填/默认值按 varCode 匹配）
  4. `outputs` 至少 1 个；`fieldName` 校验 `^[a-zA-Z_][a-zA-Z0-9_]{0,63}$` 且同视图内唯一；`fieldType` 必须在枚举内
  5. `defaultValue` 可按类型做格式校验（INTEGER/LONG/DECIMAL/BOOLEAN 需可转换）
  6. 插入 `data_source_view`（含 `script_content`，`field_count = outputs.size()`）
  7. 批量插入 `view_input_var`
  8. 批量插入 `view_field`（`category='OUTPUT'`，`sort` 取前端下标，`is_primary=0`）
- 返回值建议返回完整详情（见下），前端创建成功后只用到 `id`

### 3. `GET /api/views/{id}` 返回补充（[ViewDetailDTO.java](file:///e:/AI/work/ictriskplatfrom/backend/src/main/java/com/riskstrategy/dto/ViewDetailDTO.java)）

```java
@Data
public class ViewDetailDTO {
    private DataSourceView view;          // 实体新增 scriptContent 后自动带上
    private List<ViewField> fields;       // 出参（category=OUTPUT，含新增的 description/defaultValue）
    private List<ViewInputVar> inputs;    // 新增：入参列表
}
```

前端视图详情面板（字段清单、血缘等）后续会读这份数据。

### 3.1 编辑视图（✅ 已联调：`PUT /api/views/{id}` + `PUT /fields` + `PUT /inputs`）

前端编辑页（路由 `/views/{id}/edit`，与新建共用页面）回填详情后，**分三个接口提交**，与当前后端 JSON 列存储实现对齐：

**第一步：更新视图本体** `PUT /api/views/{id}`，请求体为扁平 `DataSourceView`（不含 inputs/outputs，那两列 `@JsonIgnore`）：

```json
{
  "code": "risk_user_info_view",
  "name": "用户风险信息表",
  "description": "...",
  "sourceType": "MySQL",
  "readMode": "SINGLE",
  "owner": "王敏",
  "businessDomain": "信贷",
  "scriptContent": "SELECT ... WHERE user_id = $user_id ..."
}
```

后端 [ViewService#update](file:///e:/AI/work/ictriskplatfrom/backend/src/main/java/com/riskstrategy/service/ViewService.java) 行为：

0. **发布态写保护（前置校验）**：若视图 `status = PUBLISHED`，一律拒绝，返回
   「该视图已发布上线（code），发布态口径不可编辑；如需修改请先在视图列表将其「下线」，再编辑重新发布」。
   同一保护同样作用于 `PUT /fields` 与 `PUT /inputs`。**降级配置 `PUT /degrade-config` 不受限制**（它是运行时容灾参数，不是视图口径）。
1. `code` 不修改（前端编辑页编码框置灰），以 path 上的 `{id}` 为准
2. 非空字段逐项 patch：name / description / version / owner / sourceType / readMode / businessDomain / scriptContent
   （**`status` 已不接受前端传值**：状态流转只走 `POST /{id}/publish` 与 `POST /{id}/offline`，避免借编辑接口把已发布视图改回草稿、绕过写保护）
3. **脚本变更时按新占位符重建 `input_vars_json`**：重新解析 `$` 变量，同名变量保留原 varType/required/defaultValue，新变量默认 STRING+必填，脚本中已删除的变量移除
4. 刷新 `updated_at`

**第二步：upsert 出参** `PUT /api/views/{id}/fields`，请求体为 `List<ViewField>`，**按 `fieldName` 在 `output_fields_json` 中定位**（不按 id）：

```json
[ { "fieldName": "overdue_cnt", "fieldType": "INTEGER", "description": "...", "defaultValue": "0", "sort": 0 } ]
```

- **同名更新**：覆盖 fieldType / description / defaultValue / piiLevel / masking / sort；传 `null` 表示清空默认值/描述
- **新名称追加**（编辑页「+ 添加出参」）：后端校验名称格式（`^[a-zA-Z_][a-zA-Z0-9_]{0,63}$`）、与已有出参忽略大小写的重名校验、默认值与类型匹配；新字段自动置 `category=OUTPUT`、`isPrimary=0`
- 保存后重写整个出参 JSON 并同步 `field_count` 与 `updated_at`
- **不支持改名与删除已有出参**；前端编辑页已有行的出参名置灰、无删除按钮，新增行保存前可改名/删除

**第三步：保存入参元信息** `PUT /api/views/{id}/inputs`，请求体为 `List<ViewInputVar>`，按 `varCode` 匹配第一步重建后的入参列表：

```json
[ { "varCode": "user_id", "varType": "STRING", "required": true, "defaultValue": "U0001" } ]
```

- 当前前端只开放**默认值**编辑（类型/必填保持创建时配置）；脚本中已删除的变量（请求里多出的 varCode）忽略
- 传 `null` 默认值表示清空；后端校验默认值与 varType 匹配

> 保存顺序固定为 `PUT /{id}`（脚本重建入参、保留同名元信息）→ `PUT /fields` → `PUT /inputs`，保证默认值补丁总是作用在最新的变量集合上。

### 3.2 删除视图（已可用，无需新接口）

前端删除按钮调用现有的 `POST /api/views/{id}/offline`（逻辑删除 `deleted=1`）：

- 列表中 **PUBLISHED（在线）视图删除按钮禁用**（hover 提示"在线视图请先下线再删除"），草稿/降级中状态可直接删除
- 前端有二次确认弹窗，文案已声明"立即逻辑下线，引用方走降级链，可由后端恢复"
- 删除成功后前端刷新列表；若删除的是当前选中项，同时清空右侧详情

### 3.3 脚本验证接口 `POST /api/views/validate`（✅ 后端已实现并联调）

新建/编辑页脚本卡片右上角有「▶ 验证」按钮，弹窗中填入测试入参后实际执行一次脚本（**不落库**），用于保存前确认脚本可执行、入参绑定正确、出参能取回数据。

> **注意占位符不要写在引号里**：应写 `WHERE user_id = $user_id`，由预编译绑定参数；写成 `WHERE user_id = '$user_id'` 时改写后为 `'?'`，JDBC 会把它当字符串字面量而非绑定参数，导致参数个数不匹配报错。

请求体：

```json
{
  "viewId": null,
  "sourceType": "MySQL",
  "readMode": "SINGLE",
  "scriptContent": "SELECT user_id, overdue_cnt FROM risk_user_info WHERE user_id = $user_id AND biz_date = $biz_date LIMIT 1",
  "inputs": [
    { "varCode": "user_id",  "varType": "STRING", "required": true, "value": "U20261012001" },
    { "varCode": "biz_date", "varType": "STRING", "required": true, "value": "2026-10-12" }
  ],
  "outputs": [
    { "fieldName": "overdue_cnt", "fieldType": "INTEGER", "defaultValue": "0", "sort": 0 }
  ]
}
```

- `viewId`：编辑场景为视图 id，新建场景为 `null`（脚本以请求体为准，不读库）
- `inputs[].value`：用户在弹窗中填的测试值，未填时为 `null`；前端已拦截必填项为空的情况，后端仍需兜底校验
- `outputs[].defaultValue`：取数列为 NULL 时前端希望知道是否走了兜底

成功响应（HTTP 200）：

```json
{
  "success": true,
  "elapsedMs": 12,
  "results": [
    { "fieldName": "overdue_cnt", "fieldType": "INTEGER", "value": 3, "defaultValueUsed": false }
  ],
  "resolvedScript": "SELECT user_id, overdue_cnt FROM risk_user_info WHERE user_id = ? AND biz_date = ? LIMIT 1\n-- params: [U20261012001, 2026-10-12]"
}
```

失败响应要求（两种都要支持，前端按 `success` 字段和 HTTP 异常分别展示）：

1. **业务可执行但验证不通过**（脚本语法错误、类型转换失败、必填缺失、数据源连不通等）：HTTP 200 + `{ "success": false, "error": "人类可读的失败原因" }`
2. **系统异常**：沿用现有全局错误格式即可，前端会展示 `请求失败（HTTP xxx）`

当前实现（`ViewService#validate`，基于 `JdbcTemplate`）：

- **MySQL 已支持**：`$name`/`${name}` 按出现顺序改写为 `?` 后 `PreparedStatement` 绑定；按 `varType` 做类型转换（INTEGER/LONG/DECIMAL/BOOLEAN，其余按字符串）；`TYPE_FORWARD_ONLY + CONCUR_READ_ONLY`、`queryTimeout=5s`、`LIST` 上限 100 行（实际取首行核对出参）
  - 仅允许单条 `SELECT` / `WITH` 语句（分号/非查询语句拦截，`allowMultiQueries` 默认关闭双保险）
  - 出参列按结果集 label（忽略大小写）匹配，缺失列返回 `success:false`；值为 NULL 时按出参 `defaultValue` 兜底并标记 `defaultValueUsed=true`
  - SQL 异常/连接失败统一返回 `success:false` + 截断后的错误原因（≤400 字符）
- **HTTP 已支持**（`HttpViewValidator`）：见下方「HTTP 脚本在线验证」小节
- **CSV / PMML 已支持**：按 `scriptContent` 内嵌的文件内容校验（见 §3.4），不依赖服务器磁盘文件
- `resolvedScript` 回显绑定后的语句 / 请求，便于核对
- 接口无任何写库副作用（未加 `@Transactional`）

#### HTTP 脚本在线验证（`HttpViewValidator`）

脚本形态即前端表单产出的请求配置 JSON。执行流程：内置函数（`${timestamp()}` / `${uuid()}` / `${nonce()}`）求值 → `$var` 全文本替换为测试值 → 按 JSON 解析配置 → 拼 URL / 请求头 / Body / 鉴权 → 发出 **1 次**真实请求 → 按 `successCriteria` 判定 → 按 `failurePolicy` 降级后取出参。

响应字段（MySQL 分支保持原结构，HTTP 分支为超集）：

| 字段 | 说明 |
| --- | --- |
| `success` / `elapsedMs` / `results[]` | 与 MySQL 一致，`results[]` 额外带 `jsonPath`（命中路径，如 `$.data.hit_blacklist`） |
| `httpStatus` | 真实响应状态码（`N/A` 表示连不上） |
| `requestLine` / `resolvedScript` | 实际请求回显，`Authorization` / `X-Signature` 等敏感头统一掩码为 `******` |
| `responsePreview` | 响应体前 1200 字符（去空白压缩，仅用于核对） |
| `policyApplied` | 实际生效的降级策略，如 `BUSINESS:NULL`；未降级为 `NONE` |
| `warnings[]` | 非致命提示：配置了重试但在线只发一次、LIST 仅取首条、出参未取到已降级等 |

三层失败语义（与表单「失败语义」三选一一一对应）：

| 层级 | 触发条件 | 验证结果 |
| --- | --- | --- |
| transport 传输失败 | 连接失败 / 超时 / 状态码不在白名单 / 非 JSON、非 http(s) 协议、配置非法 | **一律 `success:false`**，错误信息里提示运行时会按 `failurePolicy.transport` 处理——验证阶段必须暴露接口不通 |
| business 业务失败 | 状态码在白名单内，但 `businessCodePath` 取值 ≠ `businessCodeValue` | `ABORT` → `success:false`；其余按策略降级（填默认值 / 置 null），仍 `success:true` 并写入 `warnings` |
| parse 解析失败 | 业务判定通过，但某个出参在响应中取不到 | 同上，`ABORT` 判失败，其余按 `failurePolicy.parse` 降级 |

出参取值规则：先按 `readMode` 定位数据根（`SINGLE` 取 `data` 对象、`LIST` 取 `data[0]`、`SCALAR` 取整体），再按出参名精确匹配；兼容驼峰 / 下划线互写（`hitBlacklist` ↔ `hit_blacklist`）与大小写差异；最后在 4 层以内递归兜底，并记录命中的 `jsonPath`。

安全约束：

- 只允许 `http` / `https`，URL 中禁止携带 userinfo（须走「鉴权方式」）
- 不跟进重定向（`Redirect.NEVER`），避免绕过主机校验
- 连接超时上限 3s、读取超时上限 8s；响应体 > 256KB 直接拒绝解析
- 配置项 `risk.http-validator.block-private-host`（默认 `false`，便于内网联调）打开后会拦截解析到回环 / 私有网段的 Host，公网部署建议开启
- 在线验证只发 1 次请求，不执行重试，避免对目标服务放大流量

### 3.4 CSV / PMML 文件内容内嵌（✅ 已实现，替代落盘 + 上传校验）

**背景**：原实现把上传的 CSV / PMML 文件存到服务器磁盘（uploads/），`script_content` 里只保留 `file` 指针，验证时按指针读盘——服务器一次文件清理就会让视图验证全部失效（实际发生过）。现改为**上传即把文件内容内嵌进 `script_content`**，视图配置自带数据，验证不再依赖任何服务器文件。

#### 3.4.1 expr（`script_content`）新结构

CSV：

```json
{
  "fileName": "user_credit.csv",
  "sizeBytes": 40213,
  "content": "user_id,overdue_cnt\nU0001,3\n...",
  "delimiter": ",",
  "encoding": "UTF-8",
  "hasHeader": true,
  "inputColumns": ["user_id"]
}
```

PMML：

```json
{
  "fileName": "scorecard.pmml",
  "sizeBytes": 18422,
  "content": "<?xml version=\"1.0\"?><PMML>...</PMML>",
  "modelName": "CreditScore",
  "algorithm": "LogisticRegression",
  "functionName": "classification",
  "inputs": { "$credit_score": "double", "$overdue_cnt": "double" }
}
```

- `script_content` 列已由 `MEDIUMTEXT` 升级为 **`LONGTEXT`**（单文件上限 20MB，JSON 转义后需留余量；init.sql 已同步，线上库已 ALTER）
- 上传接口 `POST /api/views/upload`（CSV / PMML 分支）**不再写盘**：原 `saveToDisk / toRelativePath / sanitizeFileName` 逻辑已移除，内容直接进 JSON
- 旧数据里的 `file` 指针仅作**历史回退**：验证时 content 优先、`file` 仅在 content 缺失时读盘；前端对这类行显示提示「旧格式：依赖服务器文件，建议重新上传」

#### 3.4.2 上传时的格式 / 大小校验（保护系统）

| 检查 | 行为 |
| --- | --- |
| 扩展名白名单 | 非 `.csv` / `.pmml` / `.xml` 直接拒绝 |
| 大小上限 20MB | `spring.servlet.multipart.max-file-size=20MB`（max-request-size 22MB）；超限经 `GlobalExceptionHandler` 返回「文件过大（上限 20MB），请压缩或拆分后重新上传」 |
| 二进制嗅探 | 前 8KB 出现 NUL 空字节即拒绝（UTF-16 BOM 文件豁免），防止把 xlsx / zip 改名成 .csv 传入 |
| XML 安全 | `DocumentBuilderFactory` 关闭 DOCTYPE / 外部实体（防 XXE） |
| 编码兼容 | 自动识别 UTF-8 / GB18030 / UTF-16 并剥离 BOM；解码异常返回友好 400 |
| 解析失败 | CSV 无表头、PMML 无模型结构等返回可读 400 错误，不落库 |

#### 3.4.3 验证内容优先（`ViewFileService`）

1. `cfg.content` 非空 → 内存解析：CSV 走 `parseCsvText(content, encoding)`，PMML 走 `parseXmlText(content)`（`InputSource` 包 `StringReader`，XML 声明的编码不再影响解析）
2. content 为空 → 回退读 `cfg.file` 磁盘文件；文件缺失返回「文件不存在：…（可能已被清理），请重新上传」
3. `resolvedScript` 回显 `file: 内嵌内容（fileName）…` / `model: 内嵌内容（fileName）…`，一眼可辨验证走的是内嵌内容

#### 3.4.4 列表与解析适配

- `GET /api/views` 列表**统一排除 `script_content` 列**（逐字段 select），避免每行最大 20MB 的 JSON 进列表响应
- `parseViewVars` 对 CSV / PMML 改为 **JSON 感知**：CSV 读 `inputColumns`、PMML 读 `inputs` 键名（去 `$` 前缀），不再对整段脚本做 `$var` 正则扫描（内嵌 CSV 数据里可能出现 `$` 字符，会误判出入参）

#### 3.4.5 存量回填

已对库中 7 个 CSV/PMML 视图（id 17/18/21/22/23/24/25）按原磁盘文件回填 `fileName / sizeBytes / content`；回填前原 `script_content` 备份在 `.backup-view-content-20261013/script-content-backup.json`。id 4 的 `script_content` 为 NULL，未动。

### 3.5 列表按状态过滤（✅ 已实现）

`GET /api/views?page=&size=&status=` —— `status` 为可选参数，传入时按状态过滤（大小写不敏感）：

- `GET /api/views?status=PUBLISHED` → 只返回**已发布上线**的视图，供「基础特征」的视图下拉使用；
- 不传 `status` → 返回全部，视图管理列表页保持原行为（向后兼容）。

配合发布态写保护，形成「只能选已发布视图 → 已发布视图不可改」的闭环：基础特征引用的视图口径因此具备版本稳定性。

### 4.（建议新增）脚本解析接口

让解析规则以后端为权威，前端实时解析只做预览，保存前/失焦时可调用：

```
POST /api/views/parse-script
Body: { "sourceType": "MySQL", "scriptContent": "SELECT ... $user_id ..." }
返回:
{
  "inputs": [
    { "varCode": "user_id",  "varType": "STRING" },
    { "varCode": "biz_date", "varType": "STRING" }
  ]
}
```

> 非必须，前端已有本地解析；如果做，注意与保存时使用同一个正则常量。

### 5.（可选）`GET /api/views` 列表补充统计

列表表格展示「字段」列，目前用 `field_count`。如还想展示入参数，可加冗余列 `input_count` 或在列表 VO 里聚合。

---

## 四、运行时取数契约（实现执行引擎时注意）

1. **SQL 严禁字符串拼接替换 `$name`**，必须用预编译参数绑定：
   - 把 `$name` / `${name}` 统一改写成 `?`（按出现顺序记录参数名），执行时用 `PreparedStatement` 按序绑定，避免 SQL 注入。
2. 参数取值顺序：调用上下文传入值 → 入参 `default_value`（按 `var_type` 做类型转换）→ `required=1` 且无值则该视图取数失败，走视图配置的降级链。
3. HTTP/CSV/PMML 类型：`$name` 在 url / body / 路径中做占位符替换，同样需要对替换值做对应编码（URL encode 等）。
4. 出参 `default_value`：查询结果列为 NULL 或取数异常被降级时，按 `field_type` 转换后兜底返回。

---

## 五、改动清单速览

| 层 | 文件/对象 | 改动 |
|----|-----------|------|
| DDL | `data_source_view` | 加 `script_content` |
| DDL | 新建 `view_input_var` 表 | 入参持久化 |
| DDL | `view_field` | 加 `description`、`default_value` |
| Entity | `DataSourceView` | 加 `scriptContent` |
| Entity | 新建 `ViewInputVar` + Mapper | 参考 `FeatureInputVar` |
| Entity | `ViewField` | 加 `description`、`defaultValue` |
| DTO | 新建 `ViewCreateRequest`；扩展 `ViewDetailDTO` | 出入参嵌套结构 |
| Controller | `ViewController#create` | 入参换 DTO |
| Controller | `ViewController#update` | 入参换 `ViewCreateRequest`，支持脚本/出入参修改 |
| Service | `ViewService#create` | 事务内：服务端正则解析 + 校验 + 三表写入 |
| Service | `ViewService#update` | 事务内：校验 + 全量替换出入参子表 + 更新脚本 |
| Service | `ViewService#detail` | 返回 inputs |
| 删除 | `POST /api/views/{id}/offline` | **已存在，无需改动**，前端已对接 |
| 验证 | 新增 `POST /api/views/validate` | **前端已联调**，只读试跑脚本，返回出参结果/耗时/参数绑定语句 |
| 上传 | `POST /api/views/upload` | **已实现**：CSV/PMML 文件内容内嵌 `script_content`（不落盘）+ 格式/大小校验 |
| 验证 | `validate` 的 CSV/PMML 分支 | **已实现**：内嵌 content 优先内存解析，`file` 仅历史回退（§3.4） |
| DDL | `data_source_view.script_content` | `MEDIUMTEXT → LONGTEXT`（内嵌文件内容，最大 20MB） |
| 可选 | `POST /api/views/parse-script` | 权威解析 |
