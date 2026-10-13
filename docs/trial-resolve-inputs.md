# 试算中心 · 试算输入「解析入参」按钮 设计说明 · v1

> 需求：试算输入的 JSON 编辑框旁加一个「解析入参」按钮；点击后**按当前试算对象（策略 + 版本）自动解析**该策略需要填的入参，回填成 JSON 模板，用户只改值不用猜键名。
> 解析两部分：**① 策略自定义的入参（`param.*` 引用）② 引用到的特征的对应入参（`feat.*` 引用 → 特征入参清单）**。
> 结论：后端新增 1 个查询接口（不改表、不写库）；前端 `Trial.jsx` 增加按钮、回填与解析结果提示。
> 配套 mockup：`mockups/trial-resolve-inputs.html`（挂真实 `design.css`）。

---

## 一、数据来源（全部为既有数据，零 DDL）

| 解析项 | 数据来源 | 说明 |
| --- | --- | --- |
| ① 策略自定义入参 | 所选版本决策图的**引用变量清单** `decision_node.ref_vars_json` 里 `param.*` 条目的并集 | 例：loan_apply v12 → `n_001` 的 `param.user_id / param.apply_amount / param.channel`、`n_006` 的 `param.channel / param.risk_score` → 去重 4 个 |
| ② 引用到的特征 | 同一清单里的 `feat.*` 条目（按节点归属去重） | 例：v12 → `n_003`、`n_005` 的 `feat.user_overdue_level` |
| ② 的「对应入参」 | 已存在的能力：`FeatureService.resolvedInputsOf(特征)` | 自动展开 `@@ 基础特征` 链、`:param.` 决策入参，并**叠加用户在「入参定义」里的调整**（启停、类型、必填、默认值、手工补充行） |

兼容与兜底（同「特征改名」扫描口径）：

- 表达式里除 `param.` / `feat.` 外，兼容编排编辑器风格 `c.`（视作策略入参）、`f.`（视作特征引用）——从 `condition_expr` 文本扫描；
- `ref_vars_json` 为空的节点（如 BRANCH/ACTION），用 `condition_expr` 正则兜底；FEATURE 类型节点取表达式最左侧标识符（如 `user_overdue_level = H`）；
- 特征编码本身带前缀的历史写法（如 `feat.user_overdue_cnt`）：先按去前缀查找，找不到再用全名查找。

**明确不做**（本次范围外，后续可扩展）：`view.*` 视图入参不解析、不进模板；不触发试算、不写任何表。

## 二、接口设计

`POST /api/trial/resolve-inputs`（与 `/trial/execute` 同款定位参数：`strategy_code` 或 `strategy_id` + `version_id` 可选，缺省用默认版本解析逻辑）

**响应**（`data` 部分，示意 loan_apply v12）：

```json
{
  "strategyCode": "loan_apply",
  "strategyName": "还款能力准入策略",
  "versionId": 2,
  "version": "v12",
  "nodeCount": 8,
  "params": [
    { "name": "user_id",      "refNodes": ["n_001 开始 START"] },
    { "name": "apply_amount", "refNodes": ["n_001 开始 START"] },
    { "name": "channel",      "refNodes": ["n_001 开始 START", "n_006 交叉决策树"] },
    { "name": "risk_score",   "refNodes": ["n_006 交叉决策树"] }
  ],
  "features": [
    {
      "code": "user_overdue_level", "featureId": 17, "name": "逾期等级", "status": "PUBLISHED",
      "refNodes": ["n_003 特征·逾期等级", "n_005 决策·逾期准入"],
      "inputs": []                                   // ← resolvedInputsOf 结果
    }
  ],
  "template": { "user_id": "", "apply_amount": "", "channel": "", "risk_score": "" },
  "counts": { "paramCount": 4, "featureCount": 1, "featureInputCount": 0 },
  "warnings": [],
  "emptyGraph": false
}
```

要点：

- `template` = `params` 与各特征 `inputs` 的键并集；样例值优先 `defaultValue → sampleValue`，其余按类型给占位值（数值 `0`、字符串 `""`、布尔 `false`）；策略入参无类型信息，给 `""`。
- `params[].refNodes` / `features[].refNodes` 带 `节点ID + 标题`，前端提示「这个入参是谁引用的」，可审计。
- 特征不存在（已删除/编码对不上）→ 仅进 `warnings`；特征已下线 → 照常解析其入参，同时 `warnings` 提醒「试算时不会计算它」。
- 版本无决策节点（未编排）→ `template: {}`、`emptyGraph: true`、`warnings` 提示「该版本未编排决策图，试算会走默认规则集」。

## 三、前端交互

### 3.1 按钮位置

「试算输入」卡片，预设场景按钮行**行首**新增「解析入参」（描边高亮 + 「新」角标，与 4 个预设按钮同行）；`disabled` 条件与「试算 →」一致（未选策略不可点）。点击中显示「解析中…」。

### 3.2 回填语义（**待确认项 D1，推荐 A**）

| 方案 | 行为 |
| --- | --- |
| **A. 重建键集（推荐）** | 以解析结果为新键集重建 JSON：**同名键沿用输入框当前值，新键补占位值，与解析无关的旧键移除**。<br/>语义最直白：「按钮输出 = 这个策略真正需要的入参清单」。被移除的键（如 loan_apply 的 `overdue_cnt`）随时可用「上次示例」一键恢复。 |
| B. 仅合并缺失键 | 不动现有键值，只把解析出的缺失键补进去。对已填满的输入框点击后视觉无变化，仅下方提示行说明解析结果。 |

输入框为空或非法 JSON 时，两方案都直接用解析结果生成模板（不受影响）。

### 3.3 解析结果提示行

按钮行下方新增一行（沿用页面小字风格），点击后出现，例：

> ✓ 解析完成：策略入参 4 个（user_id、apply_amount、channel、risk_score）· 引用特征 1 个（user_overdue_level → 0 个入参）
> ⚠ 该版本未编排决策图，试算会走默认规则集

- 特征带出入参时列出键名：`引用特征 1 个（dreiver_feature → 2 个入参：a、b）`；
- 有 `warnings` 时逐条以小字展示（琥珀色），不弹窗；
- 再次点击按钮刷新该行。

## 四、边界与错误

| 场景 | 行为 |
| --- | --- |
| 未选策略 | 按钮禁用（同「试算 →」） |
| 版本未编排 / 无引用 | 空模板 + 提示行说明，不报错 |
| 特征已下线 | 仍解析其入参 + 提示行告警 |
| 特征不存在 | 跳过并告警（引用节点一并列出，方便回编排页修） |
| 接口失败 | 沿用页面顶部错误横幅（现有 `error` 机制），输入框**不动** |

## 五、验收清单

1. `loan_apply v12`：点击 → 输入框重建为 `user_id / apply_amount / channel / risk_score` 4 键（同名值保留），提示行列出 4 入参 + 1 特征；试算仍正常出结论。
2. 特征入参演示：在**一次性测试策略**（`traffic_demo`，可随意改删）上建一个临时版本，令其节点引用 `feat.dreiver_feature` → 点击解析应带出 `a`、`b` 两个 DECIMAL 入参（来源标注「基础特征 demo_pmml_base_feature → 视图 demo_pmml 入参」）；验收后删除临时数据。
3. 未编排版本（如 `loan_apply v11`）：空模板 + 提示行"未编排"说明，无报错。
4. 未选策略时按钮禁用；接口异常时顶部横幅报错且输入框内容不变。
