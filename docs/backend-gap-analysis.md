# 风控策略平台 · 后端能力缺口文档

> 基于「风控平台 UI · 淡橙主题」设计稿（11 屏）对前端 UI 进行了全面重构。
> 本文档列出前端已按设计稿实现、但后端暂未提供对应能力的接口与字段，供后端补齐参考。

---

## 一、特征工作台 `/api/features`

### 现有接口
- `GET /api/features` 列表
- `GET /api/features/{id}` 详情
- `POST /api/features` 创建
- `PUT /api/features/{id}` 更新

### 缺口

#### 1. 列表需补充字段
前端特征列表表格需展示以下字段，当前 `Feature` 实体缺失：

| 字段 | 类型 | 说明 |
|------|------|------|
| `featureType` | String | BASE / DERIVED / COMPOSITE（基础/衍生/组合） |
| `computeType` | String | SQL / Java / Python / OP（计算引擎类型） |
| `inputCount` | Integer | 入参数量 |
| `outputCount` | Integer | 出参数量 |
| `nullRate` | Double | 空值率（百分比） |
| `verifyStatus` | String | VERIFIED / WARNING / ERROR / UNVERIFIED |
| `updatedAt` | DateTime | 最近更新时间 |

#### 2. 右侧统计面板接口
```
GET /api/features/stats
返回：
{
  "ioCoverage": 96.7,           // 出入参登记覆盖率
  "registeredOutputs": 478,      // 已登记出参数
  "totalFeatures": 486,          // 特征总数
  "inputRefTotal": 1284,         // 入参引用总数
  "unverifiedCount": 16,         // 未验证数
  "verifyDistribution": {        // 验证状态分布（近7日）
    "verified": 412,
    "warning": 47,
    "error": 21,
    "unverified": 6
  },
  "topInputRefs": [              // 入参引用热度 TOP 5
    { "name": "view.risk_user_info.overdue_cnt", "count": 128 }
  ]
}
```

#### 3. 批量验证
```
POST /api/features/batch-verify
Body: { "ids": [1, 2, 3] }
```

#### 4. 特征下线（逻辑删除）
```
POST /api/features/{id}/offline
```

---

## 二、特征创建流程

### 缺口

#### 1. 命名规范校验
```
POST /api/features/validate-name
Body: { "code": "user_overdue_level_cnt", "domain": "user" }
返回：
{
  "passed": true,
  "checks": [
    { "item": "域.实体.指标_命名模式", "pass": true },
    { "item": "全小写 + 下划线分隔", "pass": true },
    { "item": "窗口类指标建议带时间后缀", "pass": false, "suggest": true },
    { "item": "无保留字冲突", "pass": true },
    { "item": "同域内名称唯一", "pass": true }
  ]
}
```

#### 2. 相似特征查重
```
GET /api/features/similar?code=user_overdue_level_cnt
返回：[
  { "name": "user_overdue_cnt_7d", "similarity": "82%" },
  { "name": "user_overdue_days", "similarity": "64%" }
]
```

#### 3. 依赖与影响面分析
```
GET /api/features/{id}/impact
返回：
{
  "upstreamViews": ["view.risk_user_info"],
  "downstreamRefs": 12,
  "downstreamStrategies": 3,
  "downstreamFeatures": 9,
  "strategies": ["还款能力准入", "首贷额度", "反欺诈"]
}
```

#### 4. 从表达式自动解析入参
```
POST /api/features/parse-inputs
Body: { "sql": "SELECT ... FROM ods_loan_order WHERE user_id = :param.user_id" }
返回：[
  { "name": "param.user_id", "type": "STRING", "source": "决策请求 param" },
  { "name": "param.window_start", "type": "DATETIME", "source": "系统内置 sys" }
]
```

#### 5. 试算验证
```
POST /api/features/{id}/trial
Body: { "inputs": {...} }
返回：{ "outputs": {...}, "success": true, "elapsedMs": 12 }
```

#### 6. 发布前检查
```
GET /api/features/{id}/precheck
返回：[
  { "title": "验证结果必须全程", "desc": "...", "result": "阻断", "type": "block" },
  { "title": "入参与出参已登记", "desc": "...", "result": "通过", "type": "pass" },
  { "title": "影响面需人工确认", "desc": "...", "result": "待确认", "type": "pending" }
]
```

#### 7. 提交审批
```
POST /api/features/{id}/submit-approval
Body: { "remark": "..." }
```

---

## 三、交叉决策树 `/api/cross-trees`

### 现有接口
- `GET /api/cross-trees?strategyId=` 列表
- `GET /api/cross-trees/{id}?slice=` 详情

### 缺口

#### 1. 矩阵单元格批量更新
```
PUT /api/cross-trees/{id}/cells
Body: {
  "cells": [
    { "rowKey": "ONLINE", "colKey": "B", "conclusion": "REVIEW", "score": 60, "reasonCode": "R_MANUAL" }
  ]
}
```

#### 2. 维度定义维护
```
PUT /api/cross-trees/{id}/dimensions
Body: {
  "dimensions": [
    { "dimNo": "D1", "dimName": "渠道", "expr": "param.channel", "bins": ["ONLINE", "OFFLINE", "OTHER"] }
  ]
}
```

#### 3. 与线上版本对比
```
GET /api/cross-trees/{id}/compare?onlineVersion=v11
返回：[
  { "type": "add", "cell": "ONLINE·C·[1万,5万)", "newValue": "人工审核 60" },
  { "type": "modify", "cell": "OFFLINE·C·[1万,5万)", "oldValue": "通过 0", "newValue": "人工审核 60" }
]
```

#### 4. 保存校验
```
GET /api/cross-trees/{id}/validate
返回：
{
  "coverage": 88.3,
  "configuredCells": 53,
  "totalCells": 60,
  "checks": [
    { "item": "每维箱互斥完备", "pass": true },
    { "item": "覆盖率 >= 80%", "pass": true },
    { "item": "OTHER 行存在整列走默认", "pass": false, "warn": true }
  ]
}
```

#### 5. Excel 导入/导出
```
POST /api/cross-trees/{id}/import-excel  (multipart/form-data)
GET  /api/cross-trees/{id}/export-csv
```

#### 6. 提交审批
```
POST /api/cross-trees/{id}/submit-approval
```

---

## 四、试算中心 `/api/trial`

### 现有接口
- `POST /api/trial/execute` 单笔试算
- `GET /api/trial/recent` 最近记录
- `GET /api/trial/{id}` 详情

### 缺口

#### 1. 回归用例集 CRUD
```
GET    /api/trial/cases              # 用例列表
POST   /api/trial/cases              # 新增用例
PUT    /api/trial/cases/{id}         # 更新用例
DELETE /api/trial/cases/{id}         # 删除用例
POST   /api/trial/cases/run-all      # 运行全部用例
POST   /api/trial/cases/import-csv   # 导入用例
```

用例结构：
```
{
  "id": 1,
  "name": "case_001 线上中额B类",
  "inputs": {...},
  "expectConclusion": "REVIEW"
}
```

运行结果：
```
{
  "caseId": 1,
  "expect": "REVIEW",
  "actual": "REVIEW",
  "status": "一致"
}
```

#### 2. 批量回放
```
POST /api/trial/batch-replay
Body: { "traceIds": ["..."], "strategyId": 1, "version": "v12" }
```

#### 3. 影子对比
```
POST /api/trial/shadow-compare
Body: { "inputs": {...}, "strategyId": 1 }
返回：
{
  "onlineResult": { "conclusion": "REJECT", "elapsedMs": 80 },
  "candidateResult": { "conclusion": "REJECT", "elapsedMs": 82 },
  "sameConclusion": true
}
```

#### 4. 试算结果需返回矩阵落箱信息
当前 `/api/trial/execute` 返回结果需补充：
```
{
  ...
  "matrixBoxing": {
    "D1": { "value": "APP", "box": "ONLINE" },
    "D2": { "value": "B", "box": "B" },
    "D3": { "value": 50000, "box": "[1万,5万)" },
    "cellConclusion": "REVIEW",
    "cellScore": 60,
    "finalConclusion": "REJECT",
    "finalScore": 85
  }
}
```

---

## 五、决策追踪 `/api/track`

### 现有接口
- `POST /api/track/invoke` 在线调用
- `POST /api/track/trial` 单笔试算
- `POST /api/track/{id}/replay` 回放
- `GET /api/track/recent` 最近记录
- `GET /api/track/search` 条件检索
- `GET /api/track/{id}` 详情

### 缺口

#### 1. 统计接口
```
GET /api/track/stats?range=1h
返回：
{
  "total": 12846,
  "degraded": 47,
  "slowRequests": 128,
  "exceptions": 6
}
```

#### 2. 采样与留存策略配置
```
GET  /api/track/sampling-config
PUT  /api/track/sampling-config
Body:
{
  "ratioSampling": "1%",
  "errorAutoCapture": true,
  "slowThresholdMs": 200,
  "retention": "热7天+冷对象存储"
}
```

#### 3. 降级来源 Top 统计
```
GET /api/track/degrade-sources?range=1h
返回：[
  { "name": "ds_ext_blacklist · 熔断降级", "count": 37 },
  { "name": "feat.device_risk_score", "count": 12 }
]
```

#### 4. Trace 详情需补充字段
当前 `GET /api/track/{id}` 返回需补充：
- `totalScore` 总分
- `hitRule` 命中规则
- `businessKey` 业务主键
- `channel` 渠道
- `channelCode` 渠道码
- `samplingReason` 采样原因
- `degradeCount` 降级次数

---

## 六、视图管理 `/api/views`

### 现有接口
- `GET /api/views` 列表
- `GET /api/views/{id}` 详情
- `POST /api/views` 创建
- `PUT /api/views/{id}` 更新
- `POST /api/views/{id}/offline` 下线

### 缺口

#### 1. 列表需补充字段
| 字段 | 类型 | 说明 |
|------|------|------|
| `successRate` | Double | 接成功率（百分比） |
| `fieldCount` | Integer | 字段数 |
| `refCount` | Integer | 被引用数 |

#### 2. 数据源健康度接口
```
GET /api/views/datasource-health?range=1h
返回：[
  {
    "name": "ds_risk_mysql · 风控主库",
    "poolUsage": "42%",
    "avgLatencyMs": 12,
    "status": "正常"
  }
]
```

#### 3. 血缘影响面分析
```
GET /api/views/{id}/lineage
返回：
{
  "totalRefs": 18,
  "baseFeatures": 6,
  "derivedFeatures": 9,
  "strategies": 3,
  "tree": "view.risk_user_info.overdue_cnt → 基础特征 user_overdue_cnt → 衍生 user_overdue_level → 策略 loan_apply_v3"
}
```

#### 4. 字段脱敏信息
视图详情 `fields` 需补充：
- `piiLevel` PII 等级
- `masking` 脱敏配置

#### 5. 降级与缓存配置
```
GET /api/views/{id}/degrade-config
PUT /api/views/{id}/degrade-config
Body:
{
  "degradeMode": "DEFAULT_VALUE",
  "onFallback": "CONTINUE",
  "cacheL1": "60s",
  "cacheL2": "300s",
  "circuitBreaker": { "enabled": false, "errorRate": 3.2 }
}
```

---

## 优先级建议

| 优先级 | 模块 | 说明 |
|--------|------|------|
| **P0** | 特征列表字段补充 | 前端表格展示依赖 |
| **P0** | 视图列表字段补充 | 前端表格展示依赖 |
| **P0** | 特征统计接口 | 右侧面板数据 |
| **P1** | 特征创建流程接口 | 命名校验/解析/试算/发布前检查 |
| **P1** | 交叉树单元格更新 + 校验 | 矩阵编辑核心功能 |
| **P1** | 回归用例集 CRUD | 试算中心核心功能 |
| **P1** | Track 统计接口 | 顶部统计条 |
| **P2** | 血缘影响面分析 | 视图/特征影响面 |
| **P2** | 数据源健康度 | 视图管理底部 |
| **P2** | 降级与缓存配置 | 视图详情 |
| **P2** | 版本回滚/灰度调整 | 发布运维 |
