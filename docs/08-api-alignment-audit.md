# 08 · 前后端接口对齐检查报告

> 检查时间：2026-10-12
> 范围：`backend/src/main/java/com/riskstrategy/**`（9 个 Controller / 9 个 Service）
> 对照：`frontend/src/api/client.js` + `frontend/src/pages/*.jsx`（10 个页面）
> 结论：**后端接口 44 个，前端已封装 16 个，实际调用 12 个**；存在 2 个阻断级问题、5 个数据错位、6 类未接线接口。

---

## 一、总览

| 模块 | 后端接口数 | 前端已封装 | 页面实际调用 | 状态 |
|---|---|---|---|---|
| dashboard | 1 | 1 | 1 | ✅ 对齐 |
| monitoring | 1 | 1 | 1 | ✅ 对齐 |
| views | 11 | 6 | 6 | ⚠️ 缺 `updateInputs`（页面在用） |
| features | 13 | 2 | 1 | ❌ 大量未接线 |
| strategies | 3 | 3 | 3 | ✅ 对齐 |
| trial | 10 | 3 | 1 | ❌ 字段名不匹配 + 大量未接线 |
| cross-trees | 7 | 2 | 0 | ❌ 页面全静态 |
| track | 10 | 6 | 0 | ❌ 页面全静态 |

已验证对齐的部分（无需改动）：
- `DashboardDTO` / `MonitoringDTO` 的 `metrics[].metricKey` 与种子数据一致
  （`online_version_count` / `decision_count` / `p99_ms` / `fallback_pct` / `degrade_rate`）
- `StrategyGraphDTO(nodes/edges)` ↔ `StrategyEditor.jsx`（`nodeId/nodeType/title/conditionExpr/paramsJson`、`fromNode/toNode/label`）
- `ViewCreateRequest` / `ViewValidateRequest` ↔ `ViewCreate.jsx` 的提交载荷（字段名完全一致）
- 统一响应 `ApiResponse{code,message,data}` ↔ 前端拦截器 `body.code !== 0` 判定
- Vite proxy `/api → http://localhost:8080` ↔ 后端 `@RequestMapping("/api/...")`

---

## 二、阻断级问题（P0）

### P0-1 · `api.views.updateInputs` 未定义，编辑视图保存必失败

- 位置：`frontend/src/pages/ViewCreate.jsx:287` ↔ `frontend/src/api/client.js:29-37`
- 现象：编辑视图点「保存修改」时，前两步（PUT `/views/{id}`、PUT `/views/{id}/fields`）成功，
  第三步抛 `TypeError: api.views.updateInputs is not a function`，页面显示「保存失败：…」，
  **入参的类型/必填/默认值永远存不进去**（前端 UI 上用户却以为保存成功了）。
- 根因：`client.js` 的 `views` 对象只声明了 `list/detail/create/update/updateFields/offline/validate`，
  缺 `updateInputs`；后端 `PUT /api/views/{id}/inputs`（`ViewController:51`）早已实现。
- 修复：在 `client.js` 的 `views` 中补一行
  ```js
  updateInputs: (id, inputs) => http.put(`/views/${id}/inputs`, inputs),
  ```

### P0-2 · 试算结果步骤字段名不匹配，后端正常时页面白屏

- 位置：`frontend/src/pages/Trial.jsx:124-126` ↔ `TrialExecutionStep` 实体
- 现象：后端 `TrialExecutionStep` 返回 `{ stepNo, stepTitle, elapsedMs }`，
  前端按 `s.title.split(' ')[0]` / `s.ms` 渲染 → `s.title` 为 `undefined`，
  `undefined.split()` 抛 `TypeError`，**React 渲染中断，试算页白屏**。
- 加剧因素：`Trial.jsx:16-36` 的 `catch` 分支用写死的 mock 兜底（mock 恰好用 `title`/`ms`），
  所以「后端挂了反而能显示，后端正常反而白屏」，极容易漏测。
- 修复（二选一，推荐改前端）：
  - 前端：`s.stepTitle` / `s.elapsedMs`
  - 或后端：`TrialDTO` 增加 `title`/`ms` 别名（`@JsonProperty`）
- 附带：`TrialDTO` 的变量快照字段是 `variableSnapshot`，前端 mock 里写的是 `variables`，契约不一致（当前未渲染，属隐患）。

---

## 三、数据错位（P1）

### P1-1 · 特征列表「空值率」字段不存在，恒显示 0.0%

- 位置：`frontend/src/pages/Features.jsx:89` ↔ `Feature` 实体
- 前端读 `f.nullRate`，后端字段是 **`emptyRate`**（另有 `emptyBase` 基线）。
- 后果：空值率恒为 `0.0%`，且「>3% 标红告警」永不触发；
  种子数据里 `feat.user_overdue_cnt` 空值率 12.4%、基线 3.1%（超基线 4 倍），本应标红。
- 修复：前端改 `f.emptyRate`（并建议同时展示 `emptyBase` 做「空值率/基线」对比）。

### P1-2 · 试算入参不参与决策，任何输入恒为 REJECT

- 位置：`TrialService.execute()` ↔ `Trial.jsx:14`
- 前端传 `{user_id, apply_amount, channel, user_level, debug, strategy_id}`；
  后端只读 `overdue_cnt`（默认 3）与 `apply_amount`（仅回显到变量快照）。
- 后果：`overdue_cnt` 未传 → 恒取 3 → `level='H'` → **任何输入都是 REJECT**；
  `channel` / `user_level` / `apply_amount` 完全不影响结论。
- 修复：前端补传 `overdue_cnt`、`risk_score`；或后端按 `user_level`/`channel` 推导逾期等级与风险分。

### P1-3 · 交叉决策树页面全静态，且数据契约与后端完全不符

- 位置：`frontend/src/pages/CrossTree.jsx`（`import api` 但零调用）
- 后端真实数据（`database/init.sql`）：
  - 切片 `slice_name` = `APP_IOS`；前端 `SLICES` 写的是 `(∞,-1万)` / `[1万,5万)` …
  - 行 `row_key` = `白金·PLATINUM` / `黄金·GOLD`；前端 `ROWS` 写 `ONLINE · 线上` …
  - 列 `col_key` = `A·优 0-60` …；前端 `COLS` 写 `A · 优质` …
  - 维度是 **D1 渠道 / D2 客户等级 / D3 申请金额 / D4 风险分档（4 维）**；前端写死「3 维 · 60 格」
- 后果：一旦接 `GET /api/cross-trees/{id}?slice=...`，矩阵行列全对不上，「覆盖率 88.3%」也是假的。
- 修复：以 `CrossTreeDTO(tree, dimensions, sliceName, cells)` 驱动渲染，行/列/切片从 `dimensions` + `cells` 推导。

### P1-4 · 决策追踪 Track 页面全静态

- 位置：`frontend/src/pages/Track.jsx`（`RECORDS` 常量写死 7 条，统计条、Trace 树、采样策略、降级 Top 全为常量）
- 后端已实现且可用：`recent / search / detail / replay / invoke / trial / stats / sampling-config(GET+PUT) / degrade-sources`
- 修复：列表接 `track.recent()`（或 `search` 带 traceId/结论筛选），详情接 `track.detail(id)`，
  采样策略接 `sampling-config`，降级 Top 接 `degrade-sources`。

### P1-5 · 在线视图无法下线删除

- 位置：`Views.jsx:104-111` —— `disabled={v.status === 'PUBLISHED'}`，提示「在线视图请先下线再删除」，
  但页面上**没有独立的「下线」按钮**，导致在线视图永远删不掉。
- 后端 `POST /api/views/{id}/offline` 已实现。
- 修复：在线视图行改为「下线」按钮（调用 `api.views.offline`，二次确认），下线后再允许删除。

---

## 四、未接线接口清单（后端已实现，前端未封装/未调用）

### features（11 个未封装）
`POST /features`、`PUT /features/{id}`、`GET /features/stats`、`POST /features/batch-verify`、
`POST /features/{id}/offline`、`POST /features/validate-name`、`GET /features/similar`、
`GET /features/{id}/impact`、`POST /features/parse-inputs`、`POST /features/{id}/trial`、
`GET /features/{id}/precheck`、`POST /features/{id}/submit-approval`

影响：特征工作台右栏（出入参登记覆盖率、验证状态分布、入参热度 TOP5）全为常量；
「+ 新建特征」的 4 步流程（基本信息→入参出参→验证→发布检查）**点「提交审批」不会发任何请求**。

### cross-trees（5 个未封装）
`PUT /{id}/cells`、`PUT /{id}/dimensions`、`GET /{id}/compare`、`GET /{id}/validate`、`POST /{id}/submit-approval`

影响：矩阵编辑器的「批量填充 / 应用到所选 4 格 / 保存草稿 / 提交审批 / 拒用 vs 线上对比」全部无动作。

### trial（7 个未封装）
`GET/POST/PUT/DELETE /trial/cases`、`POST /trial/cases/run-all`、`POST /trial/batch-replay`、`POST /trial/shadow-compare`

影响：试算中心的「批量回放」「影子对比」两个 Tab 是空壳；
「运行全部 12 条用例」「加入批量回放」「保存为用例」均无后端支撑；回归用例集 4 条为写死数据。

### track（3 个未封装）
`GET /track/stats`、`GET+PUT /track/sampling-config`、`GET /track/degrade-sources`

### views（4 个未封装）
`GET /views/datasource-health`、`GET /views/{id}/lineage`、`GET+PUT /views/{id}/degrade-config`、`POST /views/parse-script`

影响：`Views.jsx` 的「数据源健康度」「血缘影响面」「降级与缓存」三张卡均为前端常量数组，与库中数据无关。

---

## 五、健壮性与契约建议（P3）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| 1 | `StrategyEditor.jsx:26-28` | 硬编码 `list[0].id` + `versions[last].id` | 提供策略/版本选择器 |
| 2 | `StrategyEditor.jsx:180-188` | `nodePos()` 硬编码 `n_001~n_008` 的行列坐标；实体已返回 `x`/`y` 却没用 | 改用节点 `x`/`y` 做布局，节点数变化才不会重叠 |
| 3 | `Dashboard.jsx:35` | `t.priority === 'HIGH' ? <待审批> : <待审批>` 两个分支相同，优先级判断失效 | 按 priority 区分标签样式 |
| 4 | `Monitoring.jsx:68` | 健康度状态只映射 `NORMAL/CIRCUIT_BREAKER/DRIFT`，种子数据还有 `SURGE`（突增） | 补 `SURGE → 突增` 分支 |
| 5 | `Trial.jsx:16-36` | catch 分支用 mock 静默兜底，掩盖真实错误 | 开发/联调阶段改为显式报错，避免「假成功」 |
| 6 | 全局文案 | 灰度比例不一致：Dashboard 10%、Monitoring「灰度10%」、设计稿与 Trial 页写 50% | 统一为种子数据 `grayscale_pct=10` |
| 7 | `CrossTree.jsx:2` / `Track.jsx:1-2` | `import api / useEffect / useState` 未使用 | 接入接口或删除无用 import |
| 8 | `application.yml` | 未显式配置 Jackson 日期格式（当前依赖 Spring Boot 默认 ISO-8601，前端 `slice(5,16)` 恰好可用） | 显式配置 `spring.jackson.serialization.write-dates-as-timestamps=false`，避免升级后变成时间戳数组 |

---

## 六、建议修复顺序

1. **P0-1** 补 `client.js` 的 `updateInputs`（1 行，立刻见效）
2. **P0-2** 修 `Trial.jsx` 的 `stepTitle`/`elapsedMs`（含移除 mock 兜底）
3. **P1-1** `nullRate → emptyRate`
4. **P1-2** 试算入参补 `overdue_cnt` / `risk_score`
5. **P1-5** 视图「下线」入口
6. **P1-3 / P1-4** 交叉决策树、决策追踪接入真实接口（工作量最大）
7. 其余未接线接口按模块批量补 `client.js` 封装 + 页面调用
8. P3 硬编码与文案一致性清理
