# 试算中心 · 按切流判定命中版本执行（v1）

> 需求原文：**「试算只试算当前选中的策略，判断策略流量并打到哪个版本，运行对应版本的策略将里面的每个节点计算和特征计算和最后的输出值进行输出」**

本次把试算中心从「手动选版本」升级为：**按策略当前的切流配置，模拟判定本笔请求命中哪个版本 → 运行命中版本的决策图 → 把逐节点计算、特征真实计算、最终输出值完整展开**。

范围仅限试算中心（模拟判定）；生产运行时按份额路由执行的引擎侧接线仍不在本次范围（与 `strategy-traffic-switch.md` §五 口径一致）。

---

## 一、目标与最小改动原则

| # | 目标 | 现状 | 改后 |
| --- | --- | --- | --- |
| 1 | 流量判定 | 版本由人工在下拉里选 | 默认「自动」：按 `grayscale_rule` + 版本状态模拟判定命中版本 |
| 2 | 运行命中版本 | 手动版本直接跑 | 命中版本照现有链路真实执行（条件求值短路） |
| 3 | 逐节点计算 | 步骤行只有一行文字摘要 | 结构化节点明细：判定表达式 / 读取变量 / 结果 / 耗时 |
| 4 | 特征真实计算 | `feat.*` 全部来自内置硬编码派生 | 引用到的特征走 `FeatureService` 四引擎链**真实执行**取值 |
| 5 | 最终输出值 | 结论与返回报文卡 | 「最终输出」卡：结论 + 原因码 + 命中节点 + 执行版本 + 报文 |

**零 DDL、零新端点**：判定逻辑内嵌于 `POST /trial/execute`（未带 `version_id` 时自动触发），响应新增 `routing / featureRuns / nodeDetails` 三个字段。

---

## 二、交互设计

（挂真实 design.css 的改动稿见 `mockups/trial-traffic-route.html`）

### 2.1 版本选择（决策点 D1）

「试算策略」下拉旁「版本」下拉新增首项：

```
[ 自动 · 按切流判定（推荐） ▾ ]   ← 新增并设为默认
  v12 · 已发布 · 8 节点 · 流量 100%
  v11 · 已下线 · 未编排 · 流量 0%
```

- 选「自动」→ 请求不带 `version_id` → 后端按切流配置判定 → 前端在结果区回显命中版本；
- 选具体版本 → 保持现有手动调试能力（判定卡不展示），**解析入参 / 批量回放 / 影子对比不受影响**。
- 左栏「试算对象」标签：自动模式显示 `loan_apply · 自动（按切流判定）`，出结果后显示 `→ 命中 v11`。

### 2.2 结果区（右栏卡片）

| 卡 | 变化 | 内容 |
| --- | --- | --- |
| 流量判定 · 命中版本 | **新增**（仅自动模式） | 切流方式 + 试算键哈希槽位 + 候选版本表（份额区间 / 命中高亮 / 不参与原因）+ 判定结论行 |
| 执行路径 · 逐节点计算 | **升级**（原步骤表 → 结构化） | 节点（ID+名称+类型）\| 判定表达式 \| 读取变量 \| 结果 \| 耗时；命中行高亮；底部汇总行保留 |
| 特征计算 | **新增** | 按引用特征分块：引擎 / 真实耗时 / 入参取值 / @@ 引用实值 / 表达式预览 / 出参值；兜底时挂 ⚠ 与兜底来源 |
| 最终输出 | **重排**（原「结论与返回报文」） | 大号结论徽标 + 原因码 / 命中节点 / 执行版本（自动判定）/ 结论来源 / Bundle / traceId / 总耗时 / 特征真实计算比 + 报文 JSON |
| 变量快照 | 保留微调 | `feat.*` 分组标注「真实引擎值 / 内置派生兜底」来源 |

### 2.3 解析入参与自动模式的一致性

「解析入参」在自动模式下必须解析「本轮将命中版本」的入参（否则输入框键集与执行版本不匹配）。
做法：`resolve-inputs` 请求体增加 `params`（当前输入框 JSON）→ 后端同样走一次判定 → 返回 `routing`；前端提示行显示「按切流判定将执行 v0.6」。

命中版本**自身未编排决策图**时（如 loan_apply 线上 v11 未编排、决策图在待审批的 v12 上）：输入模板回退按「配置最完整带图版本」的引用解析，返回 `emptyGraph=true` + ⚠ 说明模板来源；试算执行语义不变（仍跑命中版本的默认规则集）。各版本均未编排时不回退、前端保持输入框原样，仅展示 ⚠（2026-10-14 修复「空模板把输入框清成 `{}`」问题）。

---

## 三、流量判定算法

### 3.1 触发条件

| 请求 | 行为 |
| --- | --- |
| 带 `version_id` | 跳过判定，`routing = null`，完全走现有逻辑 |
| 不带 `version_id` | 走判定；`routing` 返回判定过程与结果，后续执行使用命中版本 |

### 3.2 候选集

1. 取该策略全部版本；**不参与**：`DRAFT` / 待审（`PENDING_REVIEW`）/ 已下线（`ROLLED_BACK` / `OFFLINE`）——在判定卡中灰显并给出原因。
2. 参与版本 = 状态 ∈ {`PUBLISHED`, `GRAYSCALE`}，且取该版本 `grayscale_rule` 中 `status = ACTIVE` 的规则（无 ACTIVE 规则时按版本自身口径：`grayscale_pct > 0` 取之，`PUBLISHED` 且为 0 视为 100）。
3. **限定条件过滤**：规则配了 `channel` / `user_tag` 时，需与试算入参里的 `channel` / `user_tag` 一致（大小写不敏感）；不一致 → 本轮不参与并注明原因。全部被过滤空 → 忽略限定条件继续判定（置 ⚠ 告警）。

### 3.3 模式与命中

| 情形 | 判定 |
| --- | --- |
| 仅 1 个参与版本 | 直接命中（无论什么模式），结论行注明「仅一个线上版本，全量命中」 |
| `PERCENT` / `RANDOM` | 见 3.4 哈希口径；参与版本按 新→旧（id 倒序）排区间 |
| `WHITELIST` | 白名单**用户名单未存库**（规则只存人数）→ 无法模拟具体用户命中 → **执行兜底版本**（`fallback=true`，即 pct=100 那个），置 ⚠「白名单明细未存库，模拟按兜底版本执行」；脏数据无兜底时取新→旧第一个 |
| 模式混存（脏数据） | 取新→旧第一个参与版本的切流方式口径执行，置 ⚠ |

### 3.4 哈希口径（PERCENT / RANDOM，决策点 D2）

- 试算键：`user_id`（入参缺失时用固定缺省串并把提示写进结论行）；
- `slot = floorMod(md5/字符串哈希(user_id), 100)` → 0–99；
- 区间：按 新→旧 依次 `[start, start+pct)` 累加，末段自动兜到 99；
- 份额合计 ≠ 100（历史脏数据）→ 按比例归一化并置 ⚠；
- 结论行示例：`user_id = U100823 → 哈希槽位 47 ∈ [30, 100) → 命中 v11（份额 70%）`。

> 详细取舍见文末决策点：推荐 **RANDOM 与 PERCENT 试算统一按 user_id 哈希（可复现）**；若选真随机则每轮重掷并明确标注「本轮随机」。

### 3.5 无候选兜底

策略没有任何在线版本 → 按现有 `pickDefaultVersion`（配置最完整）执行，`routing.fallback = true` + 原因「该策略没有在线版本，按配置最完整的版本试算」。

### 3.6 输出结构（响应 `routing`）

```json
{
  "auto": true,
  "mode": "PERCENT", "modeText": "百分比切流",
  "bizKey": "user_id", "bizValue": "U100823", "slot": 47,
  "versionId": 1, "versionNo": "v11", "statusText": "已发布",
  "reason": "user_id = U100823 → 哈希槽位 47 ∈ [30, 100) → 命中 v11（份额 70%）",
  "candidates": [
    { "versionId": 13, "versionNo": "v13", "statusText": "已发布", "pct": 30,
      "rangeText": "hash 0–29", "eligible": true, "hit": false, "note": null },
    { "versionId": 1,  "versionNo": "v11", "statusText": "已发布", "pct": 70,
      "rangeText": "hash 30–99", "eligible": true, "hit": true, "note": null },
    { "versionId": 2,  "versionNo": "v12", "statusText": "待审批", "pct": 10,
      "rangeText": "—", "eligible": false, "hit": false, "note": "待审批，不参与切流" }
  ],
  "warnings": ["v12 份额 10% 与状态不一致，已按不参与处理"]
}
```

候选表包含**全部版本**（不参与的灰显 + note），保证判定卡一眼能看出「为什么是它」。

---

## 四、特征真实计算

### 4.1 链路（复用特征模块既有能力，零新引擎）

```
决策图节点引用扫描（feat./f. → 编码）           ← 复用 resolve-inputs 的扫描
  → 按编码查特征（在线优先，离线兜底）
  → FeatureService.trial(featureId, values)     ← 四引擎真实执行（SQL / Java / Python / OP）
  → 出参值回填 vars["feat.*"] → 条件求值使用真实值
  → 结构化执行明细进响应 featureRuns → 「特征计算」卡
```

- `values` = 本次试算入参平铺（含 `varCode` 与 `param.varCode` 双写），与 `FeatureService` 既有查找约定一致；
- 衍生特征的 `@@` 引用链由 `FeatureService` 内部真实展开（引用基础特征也真实取数）。

### 4.2 出参回填规则

| 特征形态 | 回填 |
| --- | --- |
| 有出参定义（`outputResults` 非空） | 每项 `feat.<出参名> = 值`（同名裸键已存在则不覆盖） |
| 衍生单值（无出参定义） | `feat.<特征编码> = derivedValue` |
| 执行失败 | **内置派生兜底**（如 overdue_cnt → user_overdue_level H/M/L），`fallback = true` + ⚠ 告警写明失败原因 |

> 兜底只影响 `feat.*` 的取值来源，字段名与现有 `buildVars` 完全一致——Python runner 未启动等环境情况下，试算结果与现状完全同值，只是卡上会明确标注「引擎失败·兜底」。

### 4.3 featureRuns 输出结构

```json
{
  "code": "user_overdue_level", "featureId": 17, "name": "逾期等级",
  "engine": "PYTHON", "success": true, "fallback": false,
  "elapsedMs": 18.2, "execElapsedMs": 12.1,
  "valuesUsed": [{ "name": "user_id", "value": "U100823", "provided": true }],
  "refValues": { "url_json_dashboard": { "metrics": [{ "value": 128.0 }] } },
  "exprPreview": "…",
  "outputs": [{ "name": "user_overdue_level", "type": "STRING", "value": "H", "missing": false }],
  "warnings": []
}
```

---

## 五、逐节点明细与最终输出

### 5.1 nodeDetails（响应新增，与落库 steps 并存）

对命中版本决策图的**每个节点**产出一条（按现有顺序）：

```json
{
  "nodeId": "n_005", "title": "决策·逾期准入", "nodeType": "DECISION", "priority": 20,
  "expr": "feat.user_overdue_level == 'H' and view.risk_user_info.overdue_cnt >= 3",
  "varsRead": [
    { "name": "feat.user_overdue_level", "value": "H", "missing": false },
    { "name": "view.risk_user_info.overdue_cnt", "value": 3, "missing": false }
  ],
  "result": "HIT", "resultText": "命中·短路",
  "detail": "REJECT · RISK_OVERDUE_HIGH",
  "elapsedMs": 0.4
}
```

结果枚举：`HIT` 命中短路 / `COND_TRUE` 条件满足但被更高优先级短路 / `COND_FALSE` 未命中 / `EVAL_FAIL` 表达式未求值 / `INFO` 信息节点（START/DATA/BRANCH/CROSS/ACTION/END）/ `OUTPUT` 结束组装。

- 读取变量取自 `ref_vars_json`，逐项从变量表取值（缺失标 `missing`，前端灰显）；
- FEATURE 节点 `detail` 附真实特征值（如 `feat.user_overdue_level = H（真实特征计算）`）；
- 节点耗时沿用现有稳定模板（可复现优先，不做真实计时）；**总耗时 = 节点模板耗时合计 + 特征引擎真实耗时合计**。

### 5.2 最终输出卡口径

结论徽标 + 字段行：原因码 / 命中节点 / 执行版本（自动判定时标注「按切流判定」）/ 结论来源 / Bundle / traceId / 总耗时 / 降级次数 / 特征真实计算 `n/m`，下方保留返回报文 JSON。
数据全部来自现有 `response` + `record` + 新字段，无额外后端改动。

---

## 六、接口变更

| 接口 | 变更 |
| --- | --- |
| `POST /trial/execute` | 请求语义：不带 `version_id` = 自动判定（原「挑配置最完整版本」语义由判定算法接管，无候选时才回落该口径）；响应 `TrialDTO` 新增 `routing`（可空）/ `featureRuns` / `nodeDetails` |
| `POST /trial/resolve-inputs` | 请求体新增可选 `params`（当前试算入参）；自动模式下按同一判定算法解析命中版本入参，返回追加 `routing`（可空） |
| 批量回放 / 影子对比 / 用例回归 | 不改代码；自动模式保存的用例不绑版本，回放时按当时切流判定执行（文档中注明此语义） |
| DDL | **无**（`grayscale_rule` / `strategy_version` / `decision_node` 全部复用） |

---

## 七、边界（本次不做）

- **生产运行时路由接线**：按份额在线上引擎选版本执行不在本次范围（与切流 v2 文档一致）；
- **白名单成员名单**：名单未存库，试算按兜底版本模拟（只展示人数与 ⚠）；
- **DATA 节点真实取数**：`view.*` 变量仍为内置派生口径（试算不直连数仓）；特征引用的视图由其引擎内部真实取数；
- 不做份额小数、灰度自动推进 / 自动回滚；
- 批量回放 / 影子对比不加判定入口（沿用各自现有版本口径）。

---

## 八、改动清单

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 后端 | `service/TrafficRouter.java`（新增） | 判定算法纯逻辑：候选集 / 过滤 / 模式 / 哈希 / 白名单兜底 / 脏数据归一 |
| 后端 | `dto/TrialRoutingDTO.java`（新增） | 3.6 结构 |
| 后端 | `dto/TrialDTO.java` | 新增 `routing` / `featureRuns` / `nodeDetails` |
| 后端 | `service/TrialService.java` | `execute`：未带版本时先判定再执行；特征真实计算段（扫描抽公共方法，复用 resolve-inputs 的 `addFeatRef/lookUpFeature`）；`nodeDetails` 组装；`resolveInputs` 同步判定 |
| 后端 | `service/FeatureService.java` | **不改**（复用 `trial` / `resolvedInputsOf`） |
| 前端 | `frontend/src/pages/Trial.jsx` | 版本下拉加「自动 · 按切流判定」默认项；试算对象标签回显命中版本；右栏新增「流量判定」「特征计算」卡；「执行路径」升级逐节点明细；「结论与返回报文」重排为「最终输出」；解析入参带 `params` 并显示判定结果 |
| 前端 | `frontend/src/api/client.js` | `resolveInputs` 透传 `params`（现有签名即 Map，无需改） |
| DDL | 无 | — |
| 测试 | `traffic_demo`（策略 4，可随意改删） | 复活/新建 2 个版本做 70/30 切流演示；**不触碰策略 1 / 2（用户真实数据）**，策略 1 仅只读验证 |

---

## 九、验收与演示

1. **单版本全量（loan_apply 真实现状）**：自动模式 → 判定卡「仅 v11 一个线上版本，全量命中」→ 执行 v11（未编排 → 默认规则集，结论来源如实标注）；
2. **多版本切流（traffic_demo 70/30 演示态）**：按 user_id 换键值命中不同版本（哈希可复现），判定卡区间高亮随之切换；命中版本有图 → 逐节点明细完整；
3. **特征真实计算**：`traffic_demo` 版本引用 `dreiver_feature`（a/b 入参，真实引擎出值）；`user_overdue_level`（Python 引擎，runner 起停两种情况分别验证「真实值」与「兜底 ⚠」两种形态）；
4. **手动版本回归**：选具体版本试算，`routing = null`，其余全部保持现状；
5. **解析入参一致性**：自动模式解析后输入框键集 = 命中版本所需入参；命中版本未编排时按带图版本引用解析（⚠ 标注来源），各版本均未编排时输入框保持原样且挂 ⚠；
6. 结果演示后 `traffic_demo` 恢复幂等初始态（`demo-traffic-switch.sql` 口径）。

---

## 附 · 已确认决策（2026-10-14 送审通过）

| # | 问题 | 结论 |
| --- | --- | --- |
| D1 | 版本选择 UX | **保留下拉 + 新增「自动 · 按切流判定」为默认**（手动选版本保留，用于定点调试 / 回放） |
| D2 | RANDOM 模式试算行为 | **按 user_id 哈希固定**——RANDOM / PERCENT 试算口径统一为可复现哈希（用例回归不抖动；生产 RANDOM 的真随机语义不受影响） |
