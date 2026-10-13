# 05 执行引擎设计

## 1. 定位与形态

执行引擎（`engine-service`）是平台唯一的**在线数据面**，职责是：拿到一个 Bundle，把它变成能跑的执行计划，然后以尽可能低的延迟和确定的行为执行它。

| 属性 | 设计 |
|---|---|
| 形态 | 无状态 Java 服务，可水平扩展 |
| 对外协议 | HTTP（JSON）为主，P1 增加 gRPC（内部高频调用） |
| 状态 | 无业务状态；仅本地缓存（Bundle / Plan / 字典 / 文件视图索引） |
| 部署 | 多副本 + 滚动发布；不依赖本地磁盘 |
| 失败假设 | 任何外部依赖（DB / Redis / 外数）都可能失败，必须有降级路径 |

## 2. 编译期（Compile）

编译期把 Bundle 转成 ExecutionPlan。**编译只发生在"配置变更"时，不在请求路径上**（请求路径上只做缓存查找）。

### 2.1 编译流水线

```mermaid
flowchart LR
    A[加载 Bundle] --> B[结构与语义校验]
    B --> C[构建变量依赖图]
    C --> D[图分析与拓扑排序]
    D --> E[变量槽位分配]
    E --> F[表达式预编译]
    F --> G[特征执行计划生成]
    G --> H[取数计划生成与合并]
    H --> I[生成 ExecutionPlan]
    I --> J[校验和验证 + 原子替换]
```

### 2.2 各阶段说明

| 阶段 | 内容 | 失败处理 |
|---|---|---|
| 加载 Bundle | 从 DB / 本地缓存读取 JSON，反序列化 | 加载失败保留旧版本，告警 |
| 结构与语义校验 | 图连通性、无环、边穷尽、类型检查 | 编译失败，拒绝激活新版本（**线上继续跑旧版本**） |
| 变量依赖图 | 每个变量的定义点集合 + 引用点集合 | |
| 图分析 | ① 拓扑排序 ② 支配树（Dominator Tree）用于变量可达性校验 ③ 分层（同一层的节点可并行） | |
| 变量槽位分配 | 把 `feat.xxx` / `var.xxx` / `node.x.result` 映射到 `Object[] slots` 的下标 | |
| 表达式预编译 | 所有条件/脚本表达式编译为 Aviator `Expression` 对象并缓存在 Plan 中 | 编译错误 → 阻止激活 |
| 特征执行计划 | 汇总所有 FEATURE 节点声明的特征 → 去重 → 按特征 DAG 拓扑排序 → 分层 | |
| 取数计划 | 汇总 DATA 节点与特征隐式依赖的视图 → 按数据源分组 → 可合并的合并（同源同 key） | |
| 生成 Plan | 组装不可变的 `ExecutionPlan` 对象 | |
| 激活 | 构建成功且校验和匹配后，`AtomicReference.set(newPlan)` | |

### 2.3 ExecutionPlan 结构（设计说明）

```
ExecutionPlan {
    planId       : String           // strategyCode + version
    checksum     : String           // 与 Bundle 一致，用于一致性校验
    layers       : List<Layer>      // 拓扑分层，层内可并行

    Layer {
        nodes : List<NodePlan>
    }

    NodePlan {
        nodeId      : String
        nodeType    : NodeType
        executorKey : String        // 对应的 NodeExecutor 注册键
        inputs      : SlotRef[]     // 需要读取的槽位
        outputs     : SlotRef[]     // 写出的槽位
        config      : CompiledConfig // 节点类型专有的编译后配置
        edges       : List<EdgePlan> // 出边（含预编译条件表达式）
        timeoutMs   : int
        onError     : ErrorPolicy
    }

    EdgePlan {
        targetNodeId : String
        priority     : int
        condition    : CompiledExpression | null   // null = DEFAULT
        conditionSrc : String                       // 原始表达式，用于 trace
    }

    SlotTable {
        size    : int
        byName  : Map<String, Integer>   // 变量名 → 槽位下标
        types   : DataType[]             // 槽位类型
    }

    FeatureExecPlan {
        stages : List<FeatureStage>      // 分层，层内并行
        cacheRules : List<CacheRule>
    }

    FetchPlan {
        groups : List<FetchGroup>        // 按数据源分组
        FetchGroup { dsId, views: List<ViewFetchPlan>, parallel: bool }
        ViewFetchPlan { viewCode, viewVersion, adapterKey, binding: ParamBinding, fieldSelection }
    }

    CrossTableIndex {
        tableId : String
        levels  : List<LevelIndex>       // 多级索引，见 04 章 §8.4
        defaultCell : DecisionCell
    }
}
```

> **编译期做重活的收益**：请求路径上零解析、零反射、零字符串比较（除必要的 trace 记录）。所有 Map 查找都退化为数组下标访问。这是达到"引擎自身开销 ≤ 20ms"的基础。

### 2.4 图分析的两个关键算法

**1）拓扑分层（Topological Layering）**
用于最大化并行度。同一层的节点之间无依赖，可以并行执行。

```
Runnable: [START]                    ← Layer 0
Runnable: [DATA_A] [DATA_B]          ← Layer 1（并行取数）
Runnable: [FEATURE_1] [FEATURE_2]    ← Layer 2
Runnable: [BRANCH_1]                 ← Layer 3
...
```

**2）支配树（Dominator Tree）**
用于变量可达性校验。如果变量 v 的定义节点 D 不一定支配引用节点 R（即存在一条从 START 到 R 的路径不经过 D），则编译期报错。

```
算法：Lengauer-Tarjan（O(E log V)），或对中小规模图用简单迭代算法
注意：这里的"强制终止"（ACTION.TERMINATE）会让节点不可达，
      支配性分析需要考虑 TERMINATE 造成的路径截断 —— 
      保守做法：忽略 TERMINATE 做支配分析（宁可多报错，不可漏报）
```

## 3. 运行期（Runtime）

### 3.1 请求处理流程

```
1. 认证与租户解析（网关已完成，引擎侧二次校验租户一致性）
2. 限流检查（按租户 + 策略维度）
3. 加载 Plan：LocalPlanCache.get(tenantId, strategyCode, version?)
     ├─ 命中 → 直接使用
     └─ 未命中 → 从 BundleStore 加载 + 编译 + 放入缓存（带 singleflight 防击穿）
4. 版本选择：无 version 参数时按灰度规则路由
5. 创建 DecisionContext（分配 slot 数组 + 写入 param.*）
6. 执行：逐层执行节点，层内并行
7. 组装结果
8. 异步写决策日志（采样）
9. 返回响应
```

### 3.2 DecisionContext

```
DecisionContext {
    traceId     : String
    tenantId    : String
    strategyCode: String
    planId      : String
    bizKey      : String          // 用于灰度路由与幂等（如 userId 或 applyId）

    slots       : Object[]        // 变量槽位（核心，替代 Map 查找）
    slotTable   : SlotTable

    deadline    : long            // 全局截止时间（System.nanoTime 基准）
    decisionState: DecisionState  // 累积的结论/分数/原因码/标签/输出
    trace       : TraceRecorder   // trace 记录（采样时可空实现）
    fetchCache  : Map<...>        // 本次请求内的取数结果缓存（同一视图只取一次）
    featureCache: Map<...>        // 本次请求内的特征值缓存

    flags       : BitSet          // 降级标记、超时标记、短路标记
}
```

**为什么用 slot 数组而不是 `Map<String, Object>`**：
- 数组下标访问 ≈ 1ns，HashMap 查找 ≈ 20~50ns + 装箱开销
- 一次决策可能读写几百次变量 → 累计差异显著
- 类型安全：`slotTable.types[]` 让编译器可以做类型检查

**对外暴露 Map 视图**：trace 与调试需要按名字取值，通过 `slotTable.byName` 做反向查找，仅用于 trace 路径（不影响热路径）。

### 3.3 调度器与并行

```
执行器线程池（舱壁隔离，各池独立，防相互影响）:
  decision-worker  : 主决策线程池（核心执行）
  view-fetch       : 取数 IO 池（大，因为大部分时间在等网络）
  feature-compute  : 特征计算池（CPU 密集，需限制并发保护 CPU）
  python-call      : Python 调用池（受 runner 容量约束）
  external-call    : 外部动作调用池（P1）

线程池参数按 CPU 核数与下游容量配置，全部通过配置中心热调。
```

**并行策略**：

| 场景 | 并行方式 |
|---|---|
| 同层节点 | `CompletableFuture.allOf` 并行提交，等待全部完成 |
| 同层取数 | 并行发起，各自超时 |
| 同层特征 | 并行按 stage 执行（stage 内并行） |
| 分支路径 | **不预测执行**（风控场景预测执行浪费资源且可能触发副作用） |

**上下文传递**：并行任务需要上下文（租户、trace、deadline），使用 `TransmittableThreadLocal` 或显式传参（推荐显式传参，避免 ThreadLocal 泄漏与串号）。

### 3.4 死亡线与超时

```
请求进入
  ├─ 计算 deadline = now + globalTimeoutMs（默认 200ms，按策略配置）
  ├─ 每次外部调用前检查剩余预算
  │    ├─ 剩余 < 节点最小需要 → 直接走降级，不发起调用
  │    └─ 剩余充足 → 发起调用，超时时间 = min(节点超时, 剩余预算)
  └─ 任何环节超过 deadline → 触发全局超时处理
```

| 层级 | 默认值 | 说明 |
|---|---|---|
| 全局 | 200ms | 策略级配置 |
| 节点 | 无（继承全局） | 可覆盖 |
| 取数 | 单视图 300ms（MySQL 500ms） | 视图级配置 |
| 特征 | 50ms | 特征级配置 |
| Python | 100ms | runner 硬超时 |

**超时降级链**：

```
正常执行
   │ 超时/异常
   ▼
使用陈旧缓存（stale-while-error，若视图允许）
   │ 无缓存
   ▼
使用视图/特征的默认值 + 记原因码
   │ 若策略配置为强依赖（ABORT）
   ▼
中断决策，返回预定义的兜底结论（如 REVIEW / REJECT）
```

### 3.5 节点执行器（NodeExecutor）

```
interface NodeExecutor {
    NodeType supportType();
    NodeResult execute(NodePlan plan, DecisionContext ctx);
}
```

| 执行器 | 要点 |
|---|---|
| `StartExecutor` | 入参校验（类型、必填、正则、枚举），失败直接返回参数错误结论 |
| `DataExecutor` | 按 FetchPlan 并行取数；结果写 `view.*` 槽位；单项失败按降级策略处理并打标记 |
| `FeatureExecutor` | 按 FeatureExecPlan 分层执行；缓存查询（请求内 + 跨请求 TTL）；失败降级 |
| `BranchExecutor` | 按边优先级求值预编译表达式；记录命中边；未命中的边标记 SKIPPED（trace 用） |
| `DecisionExecutor` | 按命中策略求值规则；产出结论/分数/原因码；`COLLECT_ALL` 需要遍历全部规则 |
| `ScriptExecutor` | 求值表达式序列并写 `var.*` 槽位 |
| `ActionExecutor` | 执行动作；`TERMINATE` 设置短路标志 |
| `DecisionTreeExecutor` | P1：树遍历（编译期已把树转成嵌套的分组结构） |
| `CrossTreeExecutor` | P1：多级索引查找（见 04 章 §8.4） |
| `SubStrategyExecutor` | P1：递归调用内层 Plan，共享 deadline 与 trace |
| `EndExecutor` | 组装输出结构 |

**注册表**：`Map<NodeType, NodeExecutor>`，Spring 启动时自动装配。新增节点类型 = 新增一个 Executor + 一个编译器分支，引擎主流程零改动（**开闭原则落地**）。

### 3.6 短路与提前终止

```
检测到 TERMINATE 标志：
  ├─ collectReasons = false → 立即跳到 END（跳过所有剩余节点）
  └─ collectReasons = true  → 继续执行剩余节点，但只收集原因码，不改变已定结论

剩余时间内可能仍要执行的节点：
  - 已在并行执行中的节点无法取消（不做强制中断，避免资源浪费与状态污染）
  - 未启动的节点直接跳过
```

### 3.7 结果组装

```
响应 = {
    strategyCode, version, traceId,
    decisionCode,                      // 最终结论
    score,                             // 总分
    reasonCodes: [{code, msg, vars}],  // 原因码 + 渲染后的文案
    outputs: {...},                    // 自定义输出字段
    hitPaths: [nodeId...],             // 命中路径（可配置是否返回）
    features: {...},                   // 特征快照（可配置是否返回，风控回溯常用）
    degradeFlags: [...],               // 降级标记（哪些数据没取到）
    costMs                                // 总耗时
}
```

**响应内容可裁剪**：不同调用方需要的信息不同（有的只要结论，有的要完整特征），通过 Bundle 中的 `responseProfile` 配置返回粒度，避免大响应拖慢链路。

## 4. 取数层（View Adapter）

### 4.1 接口

```
interface ViewAdapter {
    ViewType supportType();
    ViewMeta inspect(ViewDef def);                          // 编译期
    ViewResult fetch(ViewDef def, FetchParam p, Deadline d); // 运行期
}
```

### 4.2 通用能力（抽象基类统一实现）

| 能力 | 实现 |
|---|---|
| 超时 | 参数化超时 + deadline 传递 |
| 重试 | 可配置（仅幂等视图默认开启），指数退避，最多 2 次（**总耗时不能突破 deadline**） |
| 熔断 | 按 `tenant+viewCode` 维护滑动窗口（60s），错误率 > 50% 且样本 ≥ 20 → 打开 30s |
| 限流 | 按 `tenant+dsId` 信号量，保护下游 |
| 缓存 | L1 Caffeine → L2 Redis → 数据源（见 §5.1） |
| 降级 | 按 `cachePolicy.fallbackMode` |
| 指标 | 耗时分布、成功率、超时率、熔断次数、缓存命中率、降级次数 |
| 脱敏 | 结果按字段 `sensitivity` 做日志脱敏（不影响返回值） |

### 4.3 特殊实现要点

| 适配器 | 要点 |
|---|---|
| MySQL | HikariCP 池按 `tenant+dsId` 隔离；`PreparedStatement` 参数绑定；强制 `maxRows` 与超时；结果集流式读取 + 字段裁剪 |
| CSV/文件 | 内存索引（HashMap / 跳表）；不可变数据（加载后只读）；刷新时 `AtomicReference` 原子切换；防 OOM（size 上限 + 引用计数） |
| HTTP | 连接池复用（HTTP Client 池化）；并发信号量；响应流式解析 + 只提取声明字段；响应体大小硬限制 |
| PMML | JPMML `Evaluator` 实例缓存（线程安全）；输入字段缺失统一填充；输出映射 |

## 5. 缓存与热加载

### 5.1 缓存层次

| 层 | 内容 | 存储 | TTL | 失效 |
|---|---|---|---|---|
| L1 本地 | Bundle / ExecutionPlan | Caffeine（堆内，size 限制） | 永久（LRU 淘汰） | Pub/Sub 通知 |
| L2 分布式 | Bundle 原始 JSON | Redis | 24h | 主动删除 |
| L1 本地 | 取数结果 | Caffeine | 视图 TTL | 到期 |
| L2 分布式 | 取数结果 | Redis | 视图 TTL（更长） | 到期 / 主动失效 |
| L1 本地 | 特征值 | Caffeine | 特征 TTL | 到期 |
| L1 本地 | 字典 | Caffeine | 永久（随 Bundle 冻结） | 配置变更 |
| L1 本地 | Python 脚本编译结果 | runner 进程内 | 永久 | 脚本变更时失效 |

### 5.2 热加载机制

```
配置变更（meta-service 发布）
    │
    ├─ 写 DB（strategy_version 新行）
    ├─ 删除 Redis L2 缓存中的旧 key
    └─ Redis Pub/Sub 广播
            channel: rp:config:changed
            payload: {tenantId, type: STRATEGY, code, version, action: ACTIVATE}
                │
                ▼
        各引擎实例收到消息
            ├─ 本地缓存标记失效
            ├─ 异步加载 Bundle + 编译 Plan
            │    ├─ 成功 → AtomicReference 原子替换
            │    └─ 失败 → 保留旧 Plan + 告警 + 上报状态（meta-service 可查）
            └─ 回 ACK（用于发布流程判断"是否全部实例就绪"）
```

**兜底机制**（Pub/Sub 不可靠）：
- 请求携带 `version` 参数时，引擎校验本地版本，不匹配则同步加载
- 定时巡检（60s）核对本地 Plan 版本与 DB 中"应激活版本"，不一致则重载
- 发布流程等待所有实例 ACK 后才标记"发布完成"（超时则告警但不算失败）

### 5.3 缓存一致性风险与对策

| 风险 | 对策 |
|---|---|
| 新旧版本短暂共存（Pub/Sub 延迟） | 可接受（灰度场景本来就允许）；如需强一致，调用方传 `version` |
| 本地缓存击穿 | singleflight（同一个 key 只有一个线程回源） |
| 缓存穿透（查询不存在的 key） | 空结果也缓存（短 TTL） |
| 缓存雪崩 | TTL 加随机抖动 |
| 内存泄漏 | 所有本地缓存必须有 size 上限；Bundle 缓存按 LRU + 租户级配额 |

## 6. Trace 与可观测

### 6.1 Trace 树结构

```
DecisionTrace (root)
  ├─ span: REQUEST (总耗时)
  ├─ span: PLAN_LOAD
  ├─ span: NODE[START]        (in: params, out: validated params, 0.1ms)
  ├─ span: NODE[DATA n_002]   (out: view.*, 32ms, cacheHit=true)
  │    ├─ span: VIEW_FETCH[risk_user_info]   (28ms, from=DB)
  │    └─ span: VIEW_FETCH[loan_order]       (45ms, from=HTTP)
  ├─ span: NODE[FEATURE n_003]
  │    ├─ span: FEATURE[user_overdue_level]  (2.1ms)
  │    └─ span: FEATURE[user_apply_cnt_7d]   (1.8ms)
  ├─ span: NODE[BRANCH n_004]  (hitEdge: e_007, evaluatedEdges: 2)
  ├─ span: NODE[DECISION n_005] (matchedRule: R007, evaluated: 12)
  └─ span: NODE[END]
```

### 6.2 采样策略

| 策略 | 说明 |
|---|---|
| 比例采样 | 默认 1%（可配），按 traceId 哈希（保证同一请求的所有 span 一致采样） |
| 错误必采 | 任何降级/超时/异常 → 强制采样（**这是最有价值的样本**） |
| 慢请求必采 | 超过阈值（如 200ms）→ 强制采样 |
| 指定采样 | 通过 `debug=true` 参数强制采样（配置台试算即用此路径），带权限校验 |
| 灰度期全采 | 灰度版本可配置高采样率（如 100%），便于观察 |

### 6.3 指标体系

| 类别 | 指标 |
|---|---|
| 请求 | QPS、P50/P95/P99/P999 耗时、成功率、错误码分布 |
| 版本 | 各版本调用量占比（灰度观察）、各版本结论分布 |
| 节点 | 各节点类型耗时、各具体节点耗时 TOP N、执行次数 |
| 视图 | 各视图耗时/成功率/超时率/缓存命中率/熔断状态/降级次数 |
| 特征 | 各特征耗时/失败率/降级率/空值率/缓存命中率 |
| 决策 | 各策略结论分布（通过/拒绝/人工占比）、各原因码 TOP N、规则命中次数 TOP N |
| 资源 | 各线程池活跃度/队列长度/拒绝数、连接池使用率、JVM（GC/堆） |
| 配置 | Bundle 加载次数/失败次数、Plan 编译耗时、版本就绪实例数 |

> **"规则命中次数 TOP N"是风控运营的核心指标**：哪条规则天天在拒人、哪条规则从来没命中过，直接决定策略调优的方向。这个指标必须默认开启并可视化。

### 6.4 决策日志

| 项 | 设计 |
|---|---|
| 内容 | traceId、租户、策略、版本、入参（脱敏）、用到的特征值、命中路径、结论、分数、原因码、耗时、降级标记 |
| 写入 | 异步（MQ 或内存队列 + 批量落库），**不阻塞主流程** |
| 采样 | 全量 or 采样可配（按租户配额与存储成本） |
| 存储 | 按时间分表（月表），冷热分离（热数据 7 天在 MySQL，历史转对象存储） |
| 用途 | ① 一键回放复现问题 ② 批量回放的样本来源 ③ 运营分析（结论分布趋势） |
| 合规 | PII 字段脱敏存储；留存期可配；支持按主体删除（个人信息保护要求） |
| 大小控制 | 单条日志大小限制（如 64KB），超限截断并标记 |

## 7. 安全与隔离

### 7.1 脚本沙箱

| 语言 | 沙箱手段 | 兜底 |
|---|---|---|
| 表达式（Aviator） | 关闭反射、关闭类加载、只暴露注册的函数 | 执行超时中断（表达式本身无循环，风险低） |
| Java | 编译期 AST 黑名单 + ClassLoader 隔离 + 导入白名单 | 执行超时（`Future.cancel` + 中断标志） |
| Python | 独立进程 + 硬超时 kill + 依赖白名单 | 进程池重建 |
| SQL | AST 白名单（仅 SELECT）+ 无外部表访问（只允许 `t_input` / `t_view_*`） | 执行超时 |

**禁止事项清单（所有语言统一）**：文件系统读写、网络访问、进程创建、线程创建、反射、类加载、JVM/Runtime 终止、无限循环、超大内存分配。

### 7.2 租户隔离

| 隔离点 | 手段 |
|---|---|
| 请求 | 网关鉴权后注入 `X-Tenant-Id`，引擎二次校验（防内部越权调用） |
| Plan 缓存 | key 含 tenantId，不同租户的 Plan 物理隔离 |
| 数据源 | 连接池按租户隔离，不跨租户复用 |
| 缓存 | key 前缀含 tenantId |
| 线程池 | 共享池，但上下文通过显式传参隔离（不用 ThreadLocal 承载租户） |
| 配额 | 租户级 QPS 限流、并发限制 |
| 日志 | 结构化日志带 tenantId，便于审计与排查 |

### 7.3 引擎自身防护

| 风险 | 对策 |
|---|---|
| 单个慢策略拖垮引擎 | 每租户/每策略的并发限制 + 全局 deadline + 单请求不占满线程池 |
| 恶意大入参 | 入参大小限制（如 64KB）、数组长度限制 |
| 响应过大 | 输出字段数限制、响应大小限制 |
| 正则灾难性回溯 | 正则超时（Aviator 函数层内置超时保护） |
| 循环引用（子策略） | 编译期检测 + 运行期调用深度限制 |
| 内存 OOM | 所有缓存 size 上限；单请求中间数据 size 上限 |

## 8. 性能设计与预算

### 8.1 耗时预算分解（单次决策，目标 P99 ≤ 200ms）

| 阶段 | 预算 | 说明 |
|---|---|---|
| 网络与网关 | 5ms | 内网 |
| 鉴权/租户/限流 | 2ms | 本地校验 + Redis（可缓存） |
| Plan 加载 | 1ms | 本地缓存命中（未命中时 20~50ms，属于冷启动，不应出现在稳态） |
| 上下文创建 | 0.5ms | |
| 取数 | **120ms** | 并行，取最慢者；这是预算的大头，也是不确定性来源 |
| 特征计算 | 20ms | 分层并行；SQL 特征占大部分 |
| 决策执行 | 5ms | 规则/树/矩阵查找 |
| 结果组装与序列化 | 3ms | |
| 日志与指标（异步） | 0.5ms | 主流程只做入队 |
| **引擎自身开销合计** | **≤ 20ms** | 不含取数 |
| **总计** | **≤ 200ms** | P99 |

> **关键结论：引擎自身开销必须控制在 20ms 以内，否则在取数耗时波动时会迅速突破 SLA。** 这也是为什么用 slot 数组、预编译表达式、编译期重活前置、日志异步化。

### 8.2 优化手段清单

| 手段 | 收益 |
|---|---|
| 编译期重活前置（表达式预编译、索引预构建） | 消除请求路径上的解析与规划 |
| 变量槽位数组 | 变量访问从 ~30ns 降到 ~1ns |
| 请求内去重（同视图/同特征只取一次） | 消除重复调用 |
| 跨请求缓存（视图/特征级 TTL） | 命中时取数耗时归零 |
| 取数并行 | 多视图场景耗时 = max 而非 sum |
| 字段裁剪（只取需要的列） | 减少反序列化与内存 |
| 响应裁剪（profile 配置） | 减少序列化开销 |
| 日志异步批量 | 主流程只入队 |
| 数据源连接池预热 | 消除首次连接延迟 |
| Plan 与 JIT 预热（发布后自动预热流量） | 消除冷启动毛刺 |

### 8.3 容量估算（需与实际业务确认）

| 项 | 估算方式 |
|---|---|
| 单实例 QPS | 取决于单笔耗时与并发模型。若单笔引擎耗时 10ms、线程池 200，则理论上限 20000 QPS；考虑取数等待与安全余量，**建议按 1000~2000 QPS/实例**规划 |
| 实例数 | 峰值 QPS / 单实例容量 × 安全系数（1.5） |
| 数据库 | 元数据表读写极低（配置台操作）；决策日志是主要压力 → 必须异步 + 分表 |
| Redis | 缓存 key 数量与环境变量规模相关；Pub/Sub 消息量极低 |
| 内存 | 主要是 Bundle 缓存 + 文件视图索引 + 本地取数缓存，需设上限 |

### 8.4 压测方案

| 场景 | 目标 |
|---|---|
| 基线压测 | 空策略（仅 START→END）测引擎自身开销 |
| 典型策略压测 | 3 视图（1 DB + 1 HTTP + 1 CSV）+ 5 特征 + 1 决策节点 |
| 复杂策略压测 | 含决策树 + 交叉矩阵（大矩阵） |
| 缓存命中压测 | 全部命中 vs 全部穿透的对比 |
| 降级压测 | 注入数据源故障，验证降级路径的耗时与正确性 |
| 长稳压测 | 24h 连续压测，观察 GC、内存、连接池、缓存淘汰 |

**验收标准**：P99 ≤ 200ms、错误率 ≤ 0.1%、无内存增长趋势、降级路径不阻塞。

## 9. 引擎配置项（可热调）

| 配置 | 默认 | 说明 |
|---|---|---|
| `engine.global-timeout-ms` | 200 | 全局 deadline |
| `engine.plan-cache.max-size` | 1000 | Plan 本地缓存上限 |
| `engine.trace.sample-rate` | 0.01 | 采样率 |
| `engine.trace.slow-threshold-ms` | 200 | 慢请求阈值 |
| `engine.pool.decision.size` | CPU×4 | 决策线程池 |
| `engine.pool.fetch.size` | CPU×8 | 取数线程池 |
| `engine.pool.feature.size` | CPU×4 | 特征计算池 |
| `engine.circuit-breaker.error-rate` | 0.5 | 熔断错误率阈值 |
| `engine.circuit-breaker.window-sec` | 60 | 熔断窗口 |
| `engine.degrade.cache-ttl-ms` | 3600000 | 陈旧缓存可用时长 |
| `engine.payload.max-request-bytes` | 65536 | 入参大小上限 |
| `engine.tenant.default-qps` | 500 | 租户默认 QPS 配额 |

## 10. 本章待确认项

- [ ] §3.3 线程池参数需要结合压测结果确定；当前数值是经验值。
- [ ] §6.4 决策日志是"全量"还是"采样"？全量对存储和写入压力大，但风控回溯强烈依赖完整日志。建议：**第一阶段全量 + 保留 7 天**，之后按需采样或归档。
- [ ] §7.1 Java 沙箱的**内存限制无法在 JVM 内硬性实施**（无法限制单个线程的堆分配）。当前依赖超时兜底。如果这个风险不可接受，需要考虑"Java 特征放进独立进程（类似 python-runner）"的方案 —— 代价是延迟上升到 ms 级。请确认风险容忍度。
- [ ] §8.3 单实例 QPS 目标需要业务给出真实量级，直接影响部署规格与压测目标。
- [ ] 是否需要**多机房/多活**部署？如果需要，Bundle 同步、配置一致性、决策日志回传的方案需要补充设计。
