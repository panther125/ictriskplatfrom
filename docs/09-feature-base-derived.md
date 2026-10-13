# 09 特征创建：基础特征 / 衍生特征（含 @@ 引用与验证入参自动解析）

> 实现日期：2026-10-13 ｜ 对应需求：新建特征时可选「基础特征 / 衍生特征」两条分支，
> 基础特征绑定视图出参并在验证时复用视图入参；衍生特征用 `@@` 引用基础特征，验证时自动解析出被引用特征的入参。

## 1. 需求与设计要点

| 分支 | 配置方式 | 验证时的入参从哪来 |
|---|---|---|
| **基础特征 BASE** | 选 **1 个视图** → 选该视图的 **1 个出参** 作为特征输出值（严格 1:1，不加工） | **该视图登记的入参**，直接复用，用户只填测试值 |
| **衍生特征 DERIVED** | 写加工表达式，用 `@@基础特征编码` 引用上游基础特征 | 表达式里 `:param.x` 的决策入参 **＋** 所有 `@@` 引用所带入的（被引用基础特征 → 其视图）入参，**按变量名合并去重** |

设计的核心取舍：**基础特征保持"纯引用"**，它的价值是命名与治理，不是计算。一旦允许在基础特征上写表达式，血缘图会退化成几百个散落逻辑，所以基础特征只做 `视图字段 → 特征` 的 1:1 语义映射，且数据类型强制继承视图字段（不允许转换）。

## 2. 数据模型变更

`database/migration-2026-10-13-feature-binding.sql`（已执行，`init.sql` 同步）：

```sql
ALTER TABLE `feature`
  ADD COLUMN `view_id`    BIGINT       NULL COMMENT '【基础特征】绑定的视图 id',
  ADD COLUMN `view_code`  VARCHAR(128) NULL COMMENT '【基础特征】绑定视图编码（创建时快照）',
  ADD COLUMN `view_field` VARCHAR(128) NULL COMMENT '【基础特征】引用的视图出参字段名',
  ADD COLUMN `expr`       TEXT         NULL COMMENT '【衍生特征】表达式，@@base_code 引用基础特征';
ALTER TABLE `feature` ADD INDEX `idx_feature_view` (`view_id`, `deleted`);
```

- 仅新增列，历史数据不受影响（历史行新列为 NULL，仍按原 `sql_expr` 逻辑工作）。
- `view_code` 是**创建时快照**：视图改名/下线后仍能反查影响面，并支撑"视图字段删除 → 基础特征置不可用"的告警链路。
- 入参登记改落 `feature.input_vars_json`（JSON 列），`source` 语义为 `VIEW / FEATURE / PARAM / BUILTIN_TIME / MANUAL`。
  自 2026-10-13 起 `feature_input_var` / `feature_output_var` 两张子表已删除，入参、出参（含各入参的默认测试值 `defaultValue`）
  统一以 JSON 数组存在 `feature` 表的两列里，随特征一起读写，见 `database/migration-2026-10-13-feature-vars-json.sql`。
  - `input_vars_json`：`[{varCode, varType, source, required, defaultValue, enabled}]`
  - `output_vars_json`：`[{varCode, varType, description, sortNo}]`（第 1 个出参的类型即特征 `dataType`）

## 3. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/features/base-features?limit=50` | 可用于 `@@` 引用的基础特征清单（含其绑定视图与出参） |
| POST | `/api/features/resolve-refs` | `{expr}` → 逐条解析 `@@` 引用：是否可解析、引用到谁（含视图.出参）、带入哪些入参 |
| POST | `/api/features/resolve-inputs` | `{featureType, viewId/viewCode, viewField, expr}` → **按表单态**自动解析验证所需入参（BASE 取视图入参；DERIVED 取 `:param` + 引用带入入参的并集），并附 `warnings` |
| GET | `/api/features/{id}/resolved-inputs` | 已创建特征当前应填的入参清单 |
| POST | `/api/features/{id}/sync-inputs` | 入参登记：按当前绑定重新解析并写入 `feature.input_vars_json`，回写 `inputCount` |
| POST | `/api/features/{id}/inputs` | 入参定义全量保存（含 `enabled=false` 已移除标记、`source=MANUAL` 手工补充行）→ 写 `input_vars_json` |
| POST | `/api/features/{id}/outputs` | 出参定义全量保存（基础特征固定 1 个）→ 写 `output_vars_json`，并对齐 `outputCount` / `dataType` |
| POST | `/api/features/{id}/rename` | 特征改名：改 `code` 并同步下游引用（衍生特征 `@@编码`、策略节点/规则/交叉树里的 `feat.编码`、裸编码；`track_*` 历史不改） |
| POST | `/api/features/{id}/trial` | 验证（见 §5），返回体新增 `mode / resolvedInputs / refs / refValues / exprPreview / engine / derivedValue / execElapsedMs / viewValidation / warnings` |
| POST/PUT | `/api/features` `/api/features/{id}` | 创建/保存时执行绑定校验（见 §4） |

## 4. 绑定校验（创建 / 保存时）

| 编号 | 规则 | 违反时提示 |
|---|---|---|
| C1 | 基础特征只能引用**一个视图的一个出参** | 「基础特征必须选择该视图的一个出参作为特征输出值」 |
| C2 | 基础特征不提供表达式入口（配置台不给） | — |
| C3 | 数据类型**强制继承**视图字段类型 | 前端置灰、后端直接覆盖为视图字段类型 |
| C3.5 | 视图状态必须是 **PUBLISHED（已发布上线）** | 前端下拉只列已发布视图；后端二次校验「基础特征只能绑定「已发布上线」的视图，视图 xxx 当前状态为「草稿」…」；precheck 判阻断 |
| C4 | `readMode=LIST` 的视图**禁止**建基础特征 | 「取数模式为 LIST（返回多行），不能直接建基础特征；请改建成聚合类衍生特征」 |
| C5 | 视图字段被删除/改名 → 引用失效 | 创建时拒绝；发布前检查（precheck）`基础特征引用关系有效` 判阻断 |
| C6 | 业务口径说明 ≥10 字 | 「业务口径说明至少 10 个字，当前 N 字」 |
| D1 | 衍生特征的 `@@` 引用必须全部可解析 | 「表达式中存在无法解析的引用：@@xxx（平台内不存在该编码的特征）」 |
| D2 | 只有**基础特征**可被 `@@` 引用 | 「只有基础特征可以被 @@ 引用，该特征是「衍生特征」」 |
| D3 | 衍生特征不得引用自身 | 「衍生特征不能引用自身：@@xxx」 |

## 5. 验证（trial）行为

- **基础特征** `mode=VIEW_EXEC`：用绑定的视图脚本 + 该视图登记的全部入参，走 `ViewService.validate` **真实执行一次只读取数**
  （MySQL / HTTP / CSV / PMML 各走各自适配器），只核对特征选定的那一个出参；失败原因、`resolvedScript`、HTTP 请求/响应预览一并回显。
- **衍生特征** `mode=EXPR_EXEC` / `simulated=false`：先解析全部 `@@` 引用，再用**同一份测试值**跑每个被引用基础特征所在的视图做只读取数，
  回显 `refValues`（引用编码 → 实测值）；随后把实测值代入表达式（`@@编码` 与 `:param.x` 替换为对应语言字面量），
  按加工方式交给 **SQL / Java / Python / OP 四引擎真实执行一次**，回显 `engine`（引擎标签）、
  `derivedValue / derivedValueType`（实算输出与类型）、`execElapsedMs`（引擎耗时，与引用取数总耗时 `elapsedMs` 分开）、
  `exprPreview`（@@ 已替换为字面量的可执行表达式回显）。表达式本身不是真实执行结果的情况已不存在，`simulated` 恒为 false（保留字段兼容前端标注）。
  四引擎契约、各引擎实现点与边界见 [10-feature-engines.md](10-feature-engines.md)。

## 6. 前端改动

- 新增 `frontend/src/pages/FeatureCreate.jsx`：4 步流程（基本信息与类型 → 入参与出参 → 验证 → 发布前检查）。
  - **类型选择**：两张可选卡片（基础特征 / 衍生特征），切换即换掉后续配置与步骤文案。
  - **步骤 1 只选视图**：视图下拉（仅已发布上线）→ 自动拉详情 → 右侧展示「验证用入参（取自视图）」与候选出参数量，LIST 视图即时标红警告。
    **出参不在这一步选**，页面内直接提示去下一步。
  - **步骤 2 选出参**：「出参定义 · Output」里从该视图的**出参清单**下拉选 1 个作为特征输出值（类型自动继承、不可转换），
    选定后回显 `出参名 / 类型 / 来源`；未选时给出「待选择」提示并拦住下一步。
  - **草稿创建时机后移到步骤 2 → 3**：步骤 1 → 2 只做命名校验与查重（`validate-name` / `similar`），**不落库**；
    出参确定后点「下一步」才 `POST /features` + `sync-inputs`。从步骤 1 回退改过绑定再回来，走 `PUT /features/{id}` 就地更新，不产生重复草稿。
    （原因：后端 `validateBaseBinding` 要求基础特征必须有合法出参，步骤 1 创建时出参尚未确定，会被直接拒绝。）
  - **衍生特征**：表达式编辑器（`ref` 定位光标支持点选插入），下方"可引用的基础特征"chips 一键插入 `@@code`；
    右侧双栏实时显示 **@@ 引用解析** 与 **自动解析出的入参**（构建列表：先解析后填值）。
  - **验证步骤**：入参表由解析结果驱动（带来源标签：视图入参 / 引用特征入参 / 决策入参），测试值自动带上视图默认值；
    基础特征执行真实取数、衍生特征展示引用实测值与表达式回显。
- `frontend/src/pages/Features.jsx`：列表页保持原样，创建流程改为引用新文件；`client.js` 增加 5 个新接口封装。

## 7. 验证结果

- `mvn -o compile`、`vite build` 均通过。
- 后端端到端自检 **45/45 通过**（真实实例 + 真实 MySQL）：
  - 基础特征：`resolve-inputs` 返回视图入参且 `source=VIEW`；创建时 `dataType` 继承为 `LONG`、`computeType` 归一为 `REF`、`inputCount=1`；
    `trial` 走 `VIEW_EXEC` 并真实取到出参值 `probe_cnt=2`。
  - 反例：出参不存在 / LIST 视图 / 口径 <10 字 / 未选视图 全部被拒且提示准确。
  - 衍生特征：`resolve-refs` 解析出基础特征并带入其 `user_id` 入参；`resolve-inputs` 结果为 `['param.channel','user_id']`，
    来源分别标注 `PARAM` / `FEATURE` 且 `fromRef` 可追溯到 `e2e_user_probe_cnt`；`trial` 回显 `refValues` 与 `exprPreview`；
    `precheck` 出现「衍生特征引用关系有效：全部可解析」。
- 前端 SSR 冒烟：`FeatureCreate` 与 `Features` 均正常渲染，无运行时异常。
- **发布态约束（追加）**：`GET /api/views?status=PUBLISHED` 只返回已发布视图；已发布视图的
  `PUT /api/views/{id}`、`/{id}/fields`、`/{id}/inputs` 一律被拒；基础特征绑定未发布视图被拒、
  `resolve-inputs` 给出告警、`precheck` 判阻断；草稿视图编辑不受影响。端到端 **15/15 通过**。
- **出参选择下移到步骤 2（追加）**：jsdom 真实点击冒烟 **24/24 通过**（stub 掉 api client，走完步骤 1→2→3）——
  步骤 1 不再出现出参下拉且不创建草稿；步骤 2 出参候选取自视图、未选时被拦下；选出参后 `POST /features`
  携带 `viewId=13 / viewField=overdue_cnt / dataType=LONG / computeType=REF` 并紧接 `sync-inputs`；
  衍生特征分支同样只在步骤 2→3 创建草稿。
- **四引擎真实执行（追加，2026-10-13）**：衍生特征 trial 接入 SQL / Java / Python / OP 四引擎，配置台浏览器全链路联调
  四引擎全部 ✓ 验证通过（SQL `sql_ok`、Java `java_ok`、Python `3392`、OP `HAS_DATA`，均回显引擎标签与实算耗时），
  冒烟特征验证后已逻辑下线。详见 [10-feature-engines.md](10-feature-engines.md) §8。

## 8. 已知取舍 / 后续

- **视图发布态即冻结**：视图一旦发布上线，基本信息 / 脚本 / 出参 / 入参均不可再改，编辑页对已发布视图自动转为只读。
  如需修改必须先「下线」转草稿，编辑后重新发布 —— 这样基础特征引用的视图口径才有版本稳定性。
  代价：线上视图的紧急订正要走一次下线 → 编辑 → 发布，不能就地热改。
- 降级配置（`PUT /api/views/{id}/degrade-config`）**不受**发布态冻结限制：那是运行时容灾参数（缓存 / 降级 / 熔断），不是视图口径本身。
- `@@` 引用只支持**编码字符串**引用，不支持带参数的函数式引用（如 `@@f(x)`）；表达式仍由各语言引擎在运行时解释。
- 衍生特征验证已接入四引擎**真实执行**（`simulated` 恒为 false，字段保留供前端兼容标注）；
  各引擎的黑名单、超时与边界取舍见 [10-feature-engines.md](10-feature-engines.md) §9。
- 衍生特征**直接引视图字段**（`view.xxx`，见 03-feature.md §4.2）本期未实现，仅支持经基础特征引用，保持血缘清晰。
- 视图字段被删改时的**自动置失效 + 告警**目前只在创建与 precheck 两个时点校验，尚未做后台巡检任务。
