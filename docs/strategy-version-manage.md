# 策略管理「版本管理」独立页设计说明 · v1

> 需求（原文）：策略管理点击「版本」按钮是进入版本管理的页面，同样以列表的形式展示，上方可以展示一些当前策略的信息，编排按钮也只有版本列表才有，策略管理删除。
> 解读：①「版本」按钮改为**跳转独立页**（不再在右侧展开面板）；② 版本以**列表（表格）**形式展示；③ 页顶展示当前策略信息；④「编排」按钮**只保留在版本列表**，策略管理内的编排入口（行内按钮 + 页头按钮）删除。
> 结论：**纯前端改动**（新增路由 + 新页面 + StrategyManage 减负），无后端接口、无 DDL。
> 配套 mockup：`mockups/strategy-version-manage.html`（挂真实 design.css，数据为当前线上真实值）。

---

## 一、导航结构改动（前 → 后）

```
改动前                                   改动后
┌ 策略管理 ────────────────────┐         ┌ 策略管理 ────────────────────────┐
│ 页头: [刷新] [编排当前策略]   │         │ 页头: [刷新]                      │
│ 列表行: [版本][编排][切流]    │         │ 列表行: [版本][切流]              │
│   │ 点击「版本」= 选中        │         │   │ 点击「版本」/ 行 = 跳转        │
│   ▼                          │         │   ▼                               │
│ 右侧 420px 面板:             │         └───────────────────────────────────┘
│  策略详情卡 + 版本卡列表      │          /strategy/:id/versions（新页面）
│  (版本卡内: 切流/发布/下线/   │         ┌ 版本管理 ────────────────────────┐
│   查看编排)                   │         │ ← 策略管理 · 标题「版本管理」     │
└──────────────────────────────┘         │ 策略信息卡（原详情卡搬上来）      │
                                         │ 版本列表（表格）行操作:           │
                                         │  [编排][切流][发布][下线]         │
                                         └───────────────────────────────────┘
```

### 1.1 三个设计决策点（评审确认用）

| # | 决策 | 处理 | 理由 |
| --- | --- | --- | --- |
| D1 | 右侧「策略详情 + 版本列表面板」去留 | **整体移除**；策略列表回到全宽；行点击 = 与「版本」按钮同效（进入版本管理页） | 版本已搬到独立页，面板职责被完全取代；行保留 `clickable` 却无点击行为会变死区 |
| D2 | 页头「编排当前策略」按钮 | **删除** | 用户原话「编排按钮也只有版本列表才有」；行内「编排」与页头按钮一并删 |
| D3 | 版本行操作集 | **全保留**：编排 / 切流 / 发布 / 下线（显示条件同现状） | 版本卡搬进列表，操作不丢；「编排」= 原「查看编排」改名 |

> 空版本列表时「编排」没有可挂的行，空态里保留一个「去编排」按钮作为独家入口（见 §3.2）。

## 二、版本管理页结构

### 2.1 页面布局（路由 `/strategy/:strategyId/versions`）

```
┌ [← 策略管理]  版本管理                      [刷新] ┐
│   决策中心 / 策略管理 / 版本管理 · 还款能力准入策略（loan_apply）
├────────────────────────────────────────────────────┤
│ 策略信息卡（= 原右侧详情卡）                          │
│  还款能力准入策略  loan_apply          [已发布]      │
│  借款申请还款能力准入决策                             │
│  业务域 决策中心 | 责任人 王敏 | 版本数 2 个 | 线上版本 v11
├────────────────────────────────────────────────────┤
│ 版本列表（表格）                                      │
│ 版本 | 状态 | 流量份额 | 切流方式 | 运行状态 | 操作       │
│ v11  | 已发布 | ▓100% | 百分比切流 | 生产运行中    | [编排][切流][下线]
│      | rev36 |       | 不限渠道   | 10-08 · 王敏  |
│ v12  | 草稿  | ▓ 10% | 百分比切流 | 候选·待审批   | [编排][切流][发布]
│      | rev38 |       | APP_IOS·VIP |             |
└────────────────────────────────────────────────────┘
```

### 2.2 策略信息区（页顶）

内容照搬原右侧「策略详情」卡，不增删字段：名称 + 编码 + 状态标签、描述、四宫格（业务域 / 责任人 / 版本数 / 线上版本）。
数据来源：`GET /api/strategies` 列表项按 id 匹配（现有 `StrategyItemDTO` 字段已够，无接口改动）。

### 2.3 版本列表列定义（表格形式）

| 列 | 内容 | 来源字段 |
| --- | --- | --- |
| 版本 | `v11`（mono 加粗）+ `rev 36` 标签 | `versionNo` / `rev` |
| 状态 | StatusTag（已发布 / 草稿 / 已下线 / 待审批…） | `statusText` |
| 流量份额 | 迷你进度条 + 百分比（白名单模式显示「白名单」） | `trafficPct` / `trafficMode` / `fallback` |
| 切流方式 | 方式文案 + 渠道 / 客群副行（或「白名单 N 人 / 兜底」） | `trafficModeText` / `channel` / `userTag` / `whitelistUsers` |
| 运行状态 | stateLabel 主行 + 发布时间 · 发布人副行 | `stateLabel` / `publishedAt` / `publishedBy` |
| 操作 | 编排 / 切流 / 发布 / 下线（条件见 2.4） | — |

排序：沿用版本接口返回顺序（现状行为，不新增排序逻辑）。

### 2.4 行操作矩阵（与现有版本卡一致）

| 操作 | 显示条件 | 行为 |
| --- | --- | --- |
| 编排 | 任何状态 | 跳现有编排页 `/strategy/:id/editor`（原「查看编排」改名） |
| 切流 | 任何状态 | 打开决策级切流配置弹窗，该行预高亮（原版本卡行为） |
| 发布 | `status = DRAFT` | 打开发布确认弹窗 → `POST /api/strategies/versions/{id}/publish` |
| 下线 | `status = PUBLISHED` | `POST /api/strategies/versions/{id}/offline`（沿用现有确认逻辑） |

## 三、交互与状态

### 3.1 进入 / 返回

- 进入：策略列表行任意处点击、行内「版本」按钮 → `navigate('/strategy/{id}/versions')`。
- 返回：「← 策略管理」按钮 → `navigate('/strategy')`（回列表并重新拉取）。

### 3.2 空态 / 失败态

| 状态 | 处理 |
| --- | --- |
| 版本列表为空 | EmptyState「该策略还没有版本」+「去编排」按钮（跳编排页）—— 编排入口在无版本时的独家兜底 |
| 版本接口失败 | warn-bar 红字 + 重试按钮（沿用现有模式）；策略信息卡不受影响 |
| 策略 id 不存在 | 页头信息区显示「策略不存在」+ 返回按钮（容错，不白屏） |

### 3.3 弹窗迁移

切流配置（决策级，`PUT /api/strategies/{id}/traffic`）与发布确认两个弹窗从右侧面板**原样迁到本页**；弹窗本体、校验、文案全部不变。策略行「切流」与本页行「切流」打开同一弹窗（切流逻辑抽为共享组件，避免两份实现）。

## 四、改动清单

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 前端 | `frontend/src/App.jsx` | 新增路由 `/strategy/:strategyId/versions` |
| 前端 | `frontend/src/pages/StrategyVersions.jsx`（**新增**） | 版本管理页：策略信息卡 + 版本表格 + 行操作（编排 / 切流 / 发布 / 下线）+ 发布确认弹窗 + 空态 / 失败态 |
| 前端 | `frontend/src/pages/StrategyManage.jsx` | 删除：右侧面板、selected 状态、页头「编排当前策略」、行内「编排」、发布确认弹窗、版本卡；保留：列表 / 筛选 / 行「切流」；行点击与「版本」按钮改跳转；列表卡片文案更新 |
| 前端 | `frontend/src/components/StrategyTrafficModal.jsx`（**新增**，自 StrategyManage 抽出） | 决策级切流弹窗（份额表 / 合计校验 / 白名单兜底）供两个页面复用 |
| 前端 | `frontend/src/api/client.js` | **无改动**（`strategies.list / versions / publishVersion / offlineVersion / updateStrategyTraffic` 均已有） |
| 后端 / DDL | — | **无改动** |

## 五、边界（本次不做）

- 策略行「切流」保留不动（策略级入口，不在本次需求内）。
- 编排语义不变：编辑器仍是策略级决策草稿（`decision` / `rule` / `decision_branch`），**不做按 `strategy_version` 打开编排**，不涉及「两套版本系统」合并。
- 不新增后端接口、不改 DDL、不改版本状态机与发布 / 切流逻辑。

## 六、确认与实施记录（2026-10-14）

三个决策点均已确认，并按本方案实施完成：

| # | 决策 | 确认结果 |
| --- | --- | --- |
| D1 | 右侧面板整体移除、行点击直接进版本管理页 | ✅ 已确认并实施 |
| D2 | 页头「编排当前策略」一并删除（编排只留版本列表） | ✅ 已确认并实施 |
| D3 | 版本行操作保留 编排 / 切流 / 发布 / 下线 四项 | ✅ 已确认并实施 |

实施改动：`App.jsx`（新增路由）、`pages/StrategyVersions.jsx`（新增）、`components/StrategyTrafficModal.jsx`（新增，切流弹窗从 StrategyManage 抽出供两页复用）、`pages/StrategyManage.jsx`（减负）。
浏览器验证：列表新布局（无编排、无右侧面板）、「版本」按钮与行点击跳转、版本页信息卡与表格数据、编排跳转编辑器、行「切流」弹窗（v11 预高亮、v12 灰显不可参与）、发布确认弹窗、不存在策略兜底页、console 无报错。发布 / 下线的真实执行未对策略 1/2 做写入验证（用户真实数据禁写），代码为原逻辑迁移。

## 七、已上线版本定版：前后端双重拦截（2026-10-15）

需求：策略版本管理中，已上线（含灰度）的版本不允许修改，前后端都校验。

### 口径

与视图 / 特征的「发布即冻结」对齐，但**冻的是版本、不是策略**：线上跑着的时候仍要能编排新草稿版本
（灰度发布的前提），冻的只是已上线的那个版本本身。切流与下线不受限——切流是发布后的正当运营动作
（发布弹窗即引导「发布后用切流重新分配」），下线是唯一解锁途径。

### 后端（DecisionService.saveGraph）

- 原判定 `!"DRAFT".equals(decision.getStatus())` **恒真失效**：`decision.status` 全项目只有建草稿时
  写过一次 `DRAFT`，没有任何代码改过它，拦截从未真正生效过。
- 升级为版本级冻结 `isVersionFrozen(decision)`：该画布的 `(strategyId, versionNo)` 在
  `strategy_version` 中对应 `PUBLISHED` / `GRAYSCALE` 版本即拒绝（`StrategyStatus.isPublished` 口径）。
- 版本号大小写不敏感；`versionNo` 空/空白不冻（防御）。
- 冻结矩阵：PUBLISHED ✓冻 / GRAYSCALE ✓冻 / DRAFT ✗ / PENDING_REVIEW ✗（未上线）/ ROLLED_BACK ✗（可重编）。
- 编辑器保存失败时后端原文透传（`保存失败：版本 vXX 已发布上线…`）。

### 前端（pages/StrategyVersions.jsx）

- 已上线版本行「编排」不再跳转：按钮保留可发现性，点击弹出 warn-bar 说明
  （版本号 + 定版原因 + 「先下线」解锁方式），`title` 同步禁用语义；页面不跳转、不加载编辑器。
- 草稿 / 已下线行的「编排」正常跳转；「切流」「下线」对已上线行保持不变。
- 卡片副标题注明「已上线版本内容定版不可编排（前后端均拦截），切流与下线不受限制」。

### 验证

- 后端离线验证 **13/13**（真实连库、零写库）：已上线版本画布保存被拒 + 12 条冻结矩阵。
- `mvn -o compile` / `package` 通过；fat jar 启动自退校验通过（`isVersionFrozen` 已在 jar 内）。
- 前端 `vite build` 通过；jsdom 交互冒烟 **14/14**（禁跳转与提示闭环、切流/下线不受影响、草稿正常跳转）。

> 本节口径已被第八节取代：已定版版本不再用「点编排弹解释」，改为直接给**「查看」**按钮进只读画布。

---

## 八、已定版版本「查看」（只读画布）+ 新增版本继承上一个画布（2026-10-15）

需求（原文）：**发布的版本不要编排按钮，需要查看按钮查看画布的能力不能修改；并且版本管理这里可以新增版本，新增的版本继承上一个画布的配置。**

### 8.1 先说一个必须解决的前提

「查看画布」要求画布能按版本读到，但排查后发现**已发布版本的画布此前没有落库载体**：

| 表 | 角色 | 实际数据 |
| --- | --- | --- |
| `rule` + `decision_branch`（按 `decision_id`） | **编排画布**：编排页唯一读写的那套 | 3 行 decision，全是 DRAFT（策略1 v13 / 策略2 v9 / 策略5 v1） |
| `strategy_version`（按 `strategy_id`） | 版本与发布记录 | v11/v12/v8/v0.1…v0.5，**不含画布** |
| `strategy_version.bundle` | 快照标识 | 仅 `sb_v11_r36` 这样的字符串，不是画布 JSON |
| `decision_node` + `node_edge`（按 `version_id`） | **执行侧决策图**：试算 `execute()` / `resolve-inputs` 在读（`TrialService` 按 `strategy_version.id` 取图求值） | 只有 v12（version_id=2）有 8 节点种子数据；**无任何写入代码**——编排画布不会同步过来 |

> 前两套正是 `docs/trial-canvas-engine.md` §1.1 所述的「系统里的『策略的图』有两份存储，互不相通」。
> 本次不碰这个收敛（v2 引擎在做），只是明确：**「查看版本画布」看的是编排态画布（`rule`）**——因为原「编排」按钮指向的就是编排页，用户看到的东西必须一致。

也就是说 v11 这类历史版本的**编排画布**内容**当时就没存过**，无法凭空还原。

**处理**：不新增库表，把 `decision` 确立为画布的唯一载体，版本与画布用 `(strategy_id, version_no)` 对齐（两边版本号本来就是同一套编号：`nextVersionNo` 取两边 max+1）。读取分三级：

1. **EXACT** —— 该版本自己留存的画布（新增版本生成的都有）；
2. **FALLBACK** —— 版本没有画布快照，取该策略**最新且有内容**的编排作参照，并在页面上如实说明「与线上口径未必一致，仅供参照」；
3. **EMPTY** —— 连草稿都没有，展示空态。

### 8.2 版本管理页按钮矩阵（改前 → 改后）

| 版本状态 | 改前 | 改后 |
| --- | --- | --- |
| 已发布 / 灰度（三态 PUBLISHED） | 「编排」→ 弹提示「不可编排」 | **「查看」→ 只读画布**（另保留 切流 / 下线） |
| 草稿（三态 DRAFT） | 「编排」→ 编辑器 | 「编排」→ 编辑器（带该版本 id）（另保留 切流 / 发布） |
| 已下线（三态 OFFLINE） | 「编排」→ 编辑器 | 「编排」→ 编辑器；该版本若无画布，后端**自动派生新草稿**承接 |

页头新增 **「+ 新增版本」**；页头副标题改为说明这条路径：「已上线版本给「查看」（画布只读定版），草稿给「编排」；修改请用「新增版本」继承后发布」。

### 8.3 只读画布（编辑器 `?mode=view`）

编辑器（`pages/StrategyEditor.jsx`）复用同一套画布，靠 URL 参数切只读：

- `?versionId=x` —— 打开指定版本；`?mode=view` —— 只读；`?inherited=vX` —— 新增版本跳转过来时携带的继承来源（见 8.5）。
- 只读下**保留**：缩放、平移、缩略图、点选节点/连线查看规则配置、Esc 取消选中。
- 只读下**禁用**：
  - 节点清单拖入画布（`startPaletteDrag` 直接 return 并提示）；清单灰显 + `not-allowed` 光标；
  - 拖拽节点改坐标（`startNodeDrag` 只选中不进入拖拽）；连线（`startConnect` return）；
  - Delete/Backspace 删除；保存按钮（不渲染，`onSave` 也加 guard）；
  - 配置面板整块用 **`<fieldset disabled>`** 包住 —— 原生覆盖 input/select/textarea/button，比逐个控件加 disabled 可靠（连同「删除此节点」等按钮一起锁住）。
- 页头换成「策略编排 · 查看」+ **只读** 标签 + 「← 返回版本管理」；正文顶部一条说明条：版本号、已定版、能做什么不能做什么、**以及出路**（回版本管理点「新增版本」），带「去新增版本」动作。
- FALLBACK / EMPTY 时把 `canvasNote` 一并显示，避免用户误以为看到的就是线上口径。

### 8.4 新增版本（`POST /api/strategies/{strategyId}/versions`）

请求体 `{ baseVersionNo?, description? }`，`baseVersionNo` 留空即继承**最新版本**。

后端 `DecisionService.createVersion` → `deriveDraft`：

1. 定继承源：显式 `baseVersionNo` → 否则该策略最新版本；**源版本没有画布时退到该策略最新有画布的编排**（`latestCanvas`）；
2. `newDraftRow()` 建 `decision` 画布行（版本号 = 两边 max+1）+ **同步建一条 `strategy_version` 草稿行**（关键：不成对落库的话版本列表看不到这个草稿，也就无法切流与发布）；
3. `copyGraph()` 整体复制节点与连线（`nodeKey`/`nodeType`/`title`/`x`/`y`/`configJson` 与 `fromNode`/`fromPort`/`toNode`/`label` 逐项原样）——**这就是「继承上一个画布的配置」**；
4. 无源可用时种入开始/结束骨架。

返回 `DecisionGraphDTO`，额外带 `versionId`（新版本记录 id）与 `derivedFromVersionNo`。

前端「新增版本」弹窗不是盲操作：打开即真实读一遍继承源的画布（`GET /versions/{id}/graph`），显示**将继承 vX 的画布：N 个节点 · M 条连线**；继承源可下拉切换（默认最新版本）；FALLBACK/EMPTY 时如实提示。确认后跳到新版本编排页。

### 8.5 一处跳转上下文丢失的修复

新增版本后是**整页重新加载**到编辑器，DTO 里的 `derivedFromVersionNo` 丢失，用户看不到「已继承」提示。改为让提示随 **URL** 传递：跳转带 `&inherited=v12`，编辑器优先读 URL、其次读 DTO 里的 `derivedFromVersionNo`（后者用于「按版本编排时自动派生」这种同请求场景）。

### 8.6 顺带统一的两处口径

- **首次自动建草稿也落版本行**：`createDraft` 现在走 `newDraftRow`，所以编辑器自动创建的草稿也会出现在版本列表里（此前 decision 有草稿、版本列表却没有，两边对不上）。
- **`GET /strategies/versions/{versionId}/graph` 语义升级**：原来读的是执行侧的 `decision_node`/`node_edge`（`StrategyService.graph()`），现改为返回上面「只读画布」结构（含 `canvasSource`/`canvasNote`/`readOnly`）。前端此前无人调用该接口，无兼容负担。
  `StrategyService.graph()` 与 `StrategyGraphDTO` 因此**失去调用者但未删除**——`decision_node` 这套仍是试算链路在读，等 `trial-canvas-engine.md` 的两图收敛方案落地时一并处置，避免现在动了牵到试算。

### 8.7 冻结口径保持不变

上一节（第七节）的 `isVersionFrozen` 不动 —— **冻的是版本、不是策略**：已上线版本的画布写入仍被拒（`PUT /decisions/{id}/graph` → 「版本 vX 已发布上线，编排内容已定版不可修改」），而线上跑着的时候依然可以新增版本继续编排。本次只是把前端的「拦截提示」换成了更好用的「只读查看 + 新增版本继承」。

### 8.8 验证

- **后端离线验证 42/42**（真实连库、单个手动事务、跑完 rollback，零写库残留）：只读三级来源（EXACT/FALLBACK/EMPTY）、版本号以版本记录为准、不存在的版本/策略/继承源均抛异常、新增版本版本号推算（v2 / v14 / v15）、**节点键·类型·标题·坐标·规则配置逐项一致**、连线一并继承、新版本画布可被「查看」精确命中、按版本编排自动派生新草稿、有画布则不重复派生、置为已发布后保存被拒且查看仍只读、草稿版本保存正常、全新策略走空骨架。
- **前端 jsdom 交互冒烟 45/45**：已发布行按钮为 查看/切流/下线（无编排）、草稿行 编排/切流/发布；点「查看」跳 `?versionId=1&mode=view` 且只调 `strategies.graph` 不调 `byStrategy`；只读页无保存按钮、fieldset disabled、节点清单灰显、拖拽不产生浮标、Delete 不删节点、全程无 saveGraph；点「编排」带 versionId 进可编辑；新增版本弹窗默认继承最新版本、预览出节点/连线数、创建后跳新版本并提示继承来源。
- `mvn -o compile` / `package` 通过；fat jar 启动自退校验通过（`versionGraph`/`createVersion`/`deriveDraft` 与 DTO 新字段均已确认在 jar 内）。
- 前端 `vite build` 通过。

---

## 九、策略管理「新增策略」（2026-10-15）

需求（原文）：**策略管理也需要新增策略。**

到这一版为止，「版本」可以由前端新建，「策略」却一条也建不出来：`StrategyController` 只有 list / versions / traffic / publish / offline，没有任何创建入口，策略全靠 `init.sql` 种子。本节补上这条缺失的起点。

### 9.1 一处必须先做的重构：种画布的口径收敛

新策略不能是「一个空壳」——列表进去没有版本、没有画布，用户还要再点一次新增版本才看到东西。因此**策略与它的 v1 草稿必须一起诞生**，而这条路径与已有的两条「建草稿」路径高度重合：

| 入口 | 原实现 | 说明 |
| --- | --- | --- |
| 首次进编排页（无草稿） | `createDraft` | 建 decision + 种开始/结束 |
| 「新增版本」 | `deriveDraft` → 内联种画布 | 建 decision + 复制源画布（无源则骨架） |
| **「新增策略」（本次）** | 新增 `bootstrap` | 建 decision + 复制继承源画布（无源则骨架） |

三处都是「建 decision 画布行 + 落 strategy_version 草稿行 + 种内容」，就地复制一份迟早会漏（漏种骨架、漏建版本行都会直接导致版本页看不到草稿）。所以抽出 **`DecisionService.seedDraft(strategy, description, source)`**，三条入口全部改走它：

```java
private Decision seedDraft(Strategy strategy, String description, Decision source) {
    Decision d = newDraftRow(strategy, description);          // decision 行 + strategy_version 草稿行（成对）
    if (source != null && !ruleMapper.selectByDecisionIdOrderById(source.getId()).isEmpty()) {
        copyGraph(source.getId(), d.getId());                 // 继承：节点 + 连线逐项复制
    } else {
        insertNode(START); insertNode(END); insertEdge();     // 骨架
    }
    return d;
}
```

对外新增 `bootstrap(Strategy, Long inheritFromStrategyId)`：继承源取该策略**最新有画布的编排**（`latestCanvas`，与「查看」的 FALLBACK 同一条取数口径），返回带 `versionId` 的画布，前端据此可直接跳编排。

### 9.2 后端 `POST /api/strategies`

请求体 `CreateStrategyDTO { name, code, category?, description?, owner?, inheritFromStrategyId? }`，返回新策略的列表项（`StrategyItemDTO`）。

校验（都在 `StrategyService.createStrategy`，事务内）：

| 规则 | 口径 | 拒绝文案 |
| --- | --- | --- |
| 名称必填 | 去空格后非空 | 策略名称不能为空 |
| 名称长度 | ≤ 128 | 策略名称最长 128 个字符 |
| 编码必填 | 去空格后非空 | 策略编码不能为空 |
| 编码格式 | **统一转小写**后匹配 `^[a-z][a-z0-9_]{2,127}$` | 需以小写字母开头，仅含小写字母、数字与下划线（如 loan_apply_v3） |
| 编码唯一 | 与库内 `code` 大小写不敏感比对（对应 `uk_strategy_code`） | 策略编码已被占用：xxx |
| 继承源存在 | `inheritFromStrategyId` 非空时必须能查到 | 继承源策略不存在: x |

**为什么编码要这么严**：策略 code 是这个平台对外的语义主键——试算按 `strategy_code` 定位、留痕 `track_record` 落的是它、发布记录里也是它（`loan_apply_v3 v12发布至 PRE 环境`）。放任意字符串进来，后续所有按 code 检索的地方都会长出脏数据。前端在输入时就把非法字符过滤掉（`toLowerCase()` + 去掉 `[^a-z0-9_]`），后端再做一次真校验。

落库顺序：`strategy` 行 → `bootstrap` 建 v1 草稿（画布 + 版本成对）→ **回读一次策略行**再组装返回（`created_at`/`updated_at` 是库默认值，插入后实体里还是 null）。

### 9.3 前端：策略管理页

- 页头新增 **「+ 新增策略」**（primary），空列表时的 `EmptyState` 也给同一个入口。
- 新增策略弹窗（复用 `components/Common.jsx` 的 `Modal` + `FormField`，宽度 600）：

| 字段 | 控件 | 说明 |
| --- | --- | --- |
| 策略名称 * | input | 错误态即时提示 |
| 策略编码 * | input（`.mono`） | 输入即归一为小写并过滤非法字符；hint「发布 / 试算 / 留痕都按它定位，创建后不可修改」 |
| 业务域 | input + `datalist` | 候选项取现有策略里出现过的分类（避免自由发挥出五花八门的写法），默认「决策中心」 |
| 责任人 | input | |
| 策略说明 | textarea | 可选 |
| 初始画布 | select | 空画布 / 继承某个已有策略（选项显示「继承 XX（code）· N 个版本」） |

- **编码查重是前端本地做的**（`list` 已全量加载，无需新接口）：与现有 code 大小写不敏感比对，命中即提示「编码 xxx 已被占用」；提交按钮不做硬禁用（避免用户不知道为什么点不动），错误统一走 `FormField` 的 error 行。
- 提交成功后跳 **`/strategy/{id}/versions`** 并带上 `state.created`（策略名 / 编码 / 继承源）。跳版本页而不是直接进编辑器，是因为新建出来的东西是「策略」，用户应当先看到它的版本结构（v1 草稿）再决定编排。
- 版本管理页据此显示落地提示条：「策略 XX（code）已创建，v1 草稿版本已就绪，画布已继承「YY」最新编排的节点与连线」，带 **「进入 v1 编排」** 与「知道了」（可关闭）。

### 9.4 顺带的两处小改

- 策略列表补 **「责任人」列**（表格与搜索本来就已经支持按责任人过滤，只是没渲染出来）。
- 空列表态与「筛选无结果」态分开：前者是「还没有任何策略」+ 新增按钮，后者才是「换个关键词」。

### 9.5 边界（本次不做）

- 不做策略的编辑 / 删除 / 复制：code 是主键语义，改名会把试算、留痕、发布记录的引用一起打断，需要单独设计（对应「特征编码可改」那套同步机制的成本）。
- 不做初始画布的「跨策略深拷贝 + 特征引用校验」：继承只是画布结构的复制，若源策略引用了目标策略不可见的特征，由编辑器原有的未定义引用提示接住（`nodeIssues`）。

### 9.6 验证

- **后端离线验证 54/54**（真实连库、单个手动事务、跑完 rollback，零写库残留）：
  - 空画布新建：strategy 行落库、**画布与版本记录成对落库（decision v1 + strategy_version v1）**、开始/结束骨架 2 节点 1 连线、责任人从策略带到版本、`draftGraph` 取到 v1 且不重复建版本、`versionsWithTraffic`/`manageList` 均可见；
  - 继承新建：编码 `Offline_Check_B` 归一为 `offline_check_b`、**节点键/类型/标题/坐标/规则 JSON 与连线数逐项一致**、源策略画布未被改动、继承源无画布时退化为骨架；
  - 校验：名称空 / 名称超长 / 编码空 / 数字开头 / 含中划线 / 重复 / 大写重复 / 本事务内新策略重复 / 继承源不存在 / 空请求 —— 全部被拒且**未落任何行**；
  - rollback 后 strategy / strategy_version / decision / rule / decision_branch **五张表零残留**。
- **前端 jsdom 交互冒烟 33/33**（esbuild 打包 + stub `api/client.js` + 真实点击）：列表出现责任人列、弹窗字段齐全且业务域默认决策中心、空提交报两条错且**不发请求**、数字开头与编码占用均被前端拦下、正例提交的 body 正确（含 `inheritFromStrategyId`）、弹窗关闭、跳转 `/strategy/42/versions` 并显示落地提示（含继承来源文案）、「知道了」可关闭。
- **真实 Chrome 截图复核**：列表页（含责任人列）、弹窗初始态、校验错误态、选中继承源态，均无控制台/页面异常。
- `mvn -o compile` / `package` 通过；**fat jar（54.8 MB）**内已确认 `CreateStrategyDTO`、`StrategyService.createStrategy`、`DecisionService.bootstrap`/`seedDraft`，启动自退校验通过。前端 `vite build` 通过。

