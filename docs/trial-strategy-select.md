# 试算中心 · 按策略试算

> 变更日期：2026-10-14 ｜ 涉及 `TrialController` / `TrialService` / `ConditionEvaluator` / `Trial.jsx`

## 为什么改

原 `Trial.jsx` 里写着 `const STRATEGY_ID = 1`，`TrialService.execute()` 也是固定剧本：
读 `overdue_cnt` 判断完就返回，`version` 恒为 `v12`。**试算中心没法选策略**，
选谁跑都一样，结论与实际编排配置无关。

现在：试算中心页头可下拉选择**策略编码（code）**与该策略下的**版本**，后端按所选策略/版本
读取 `decision_node` 条件**真实求值**。

## 请求协议（`POST /api/trial/execute`）

请求体在原有试算入参基础上，新增三个可选控制字段（它们只参与调度，不进变量表）：

| 字段 | 说明 |
|---|---|
| `strategy_code` | **推荐**。策略编码，如 `loan_apply`；不存在的编码会明确报错「策略编码不存在：xxx」 |
| `strategy_id` | 兼容用；`strategy_code` 优先 |
| `version_id` | 指定版本 ID（须属于该策略） |
| `version_no` | 指定版本号，如 `v12` |

三者都不传时：取第一个策略 + 该策略的默认版本。

### 默认版本怎么选

按加权分排序取最大（`pickDefaultVersion`）：

1. **登记了决策图的版本优先**（`decision_node` 有记录）—— 这样一进页面就能看到真实执行链路；
2. 其次看版本状态权重：`PUBLISHED > GRAYSCALE > PENDING_REVIEW > DRAFT > ROLLED_BACK/OFFLINE`；
3. 最后比 `rev`。

例：`loan_apply` 有 v11(线上, 无图) 与 v12(待审批, 8 节点) → 默认选 **v12**。

## 结论从哪来（`response.execSource`）

| 取值 | 含义 |
|---|---|
| `GRAPH` | 命中编排图里的决策节点（优先按 `priority` 升序短路），用该节点的 `conclusion` / `reason_code` |
| `DEFAULT_RULES_NO_MATCH` | 该版本有决策图，但条件都没命中 → 回落默认准入规则集 |
| `DEFAULT_RULES` | 该版本未编排决策图 → 直接走默认准入规则集 |

默认准入规则集（与旧行为一致，保证历史用例期望值不变）：
`overdue_cnt ≥ 3 → REJECT(R007)`；`risk_score ≥ 800 → REJECT(R015)`；其余 `→ REVIEW`。

## 条件求值（`engine/ConditionEvaluator`）

决策条件以字符串存在库里（如 `feat.user_overdue_level == 'H' and view.risk_user_info.overdue_cnt >= 3`），
求值器刻意保持最小语法集：

- 逻辑：`and` / `&&`（全真）、`or` / `||`（一真）；**引号内的 and/or 不参与切分**
- 比较：`==` `=` `!=` `<>` `>=` `<=` `>` `<`
- 操作数：数字、单/双引号字符串、布尔字面量、变量名（`feat.x` / `view.a.b` / `param.x` / 裸名）

**三态返回**：`TRUE` / `FALSE` / `UNKNOWN`。表达式解析不了或引用了未知变量 → `UNKNOWN`，
由调用方决定降级，**不把「解析不了」误判成「条件不成立」**。

试算变量表由入参摊平而来，并补齐表达式常用的派生特征：

| 入参 | 派生变量 |
|---|---|
| `overdue_cnt` | `feat.user_overdue_level`（H/M/L）、`view.risk_user_info.overdue_cnt` |
| `risk_score` | `feat.risk_level`（HIGH/MID/LOW） |
| `apply_amount` | `param.apply_amount` |
| 全部入参 | 裸名 + `param.` 前缀 |

变量快照按 `view.*` / `feat.*` / `param.*` / `node.*` 四组返回。

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/trial/strategies` | **新增**。策略 + 各策略版本（状态/流量/节点数/默认版本），供下拉渲染 |
| POST | `/api/trial/execute` | 支持 `strategy_code` / `version_id` / `version_no` |
| GET | `/api/trial/cases?strategyId=` | **改**。用例集按策略过滤 |
| POST | `/api/trial/cases/run-all` | **改**。body 可带 `{strategyId}`，按该策略跑 |
| POST | `/api/trial/batch-replay` | `version` 会真正按目标版本回放（版本不存在则退化默认版本） |
| POST | `/api/trial/shadow-compare` | 按所选策略执行 |

## 前端

`Trial.jsx` 去掉硬编码策略：页头两个下拉（策略 / 版本），下方一条摘要条显示
策略编码、名称、状态、版本、流量、节点数、用例数；切策略会联动重载用例集、
清空上一次结果并同步批量回放的目标版本。结论区新增「结论来源」显示。

## 验证

- `ConditionEvaluator` 单测 24 例（真实节点/规则表达式 + 正负例 + 变量名边界 + UNKNOWN 降级）全过。
- 离线集成测试 39 例：手动引导 MyBatis-Plus 连真实库跑 `execute` / `listStrategyOptions` /
  `listCases`，`loan_apply`（默认 v12、命中 n_005 → REJECT/RISK_OVERDUE_HIGH）、
  `credit_check`（默认 v8、走默认规则集）、指定 `v11`、非法编码报错、用例按策略过滤；
  **Hikari 必须 `setAutoCommit(false)`**，否则 `SqlSession.rollback()` 形同虚设会被真提交。
- 前端 `vite build` 通过（663 modules）+ SSR 冒烟通过。
