# 策略执行引擎设计 · 画布编译规则脚本 · 自建引擎解析运行（v2）

| 项 | 内容 |
| --- | --- |
| 文档版本 | v2（2026-10-15 重写，替代同日 v1「试算中心 · 编排画布执行引擎」） |
| 状态 | **待评审 —— 评审通过后进入实现** |
| 适用范围 | 平台执行引擎（在线数据面 + 离线运行时）；试算中心为第一消费者 |
| 需求来源 | 用户 2026-10-15 两条消息（见下） |

> **需求原文①**：设计 SA/SB 双 ClassLoader 热加载架构：底层 SDK jar 由 SA 全局加载、跨策略复用，策略 jar 由 SB 按版本隔离加载，利用双亲委派保证入口类全局单实例，配合 ClassLoader 回收，实现策略发布/回滚不重启，Metaspace 零增长；加载态/运行态分离 + 预编译 DAG：加载期完成资源搬运、config.json 解析、决策流 PolicyModel（Flow/Node/Edge）构建并缓存；运行期仅做反射实例化与 DAG 遍历，配合「每次执行新建 Session + ThreadLocal 隔离」实现无锁并发，元信息一次加载、N 次执行；设计推拉结合的策略命令同步机制：DRM 推送仅作触发信号，数据按 seqNo 增量拉取，volatile 游标 + 业务键粒度锁保证单策略命令串行幂等消费，ACK 异步批量上报防重复下发，实现万级策略状态最终一致；执行与超时解耦：决策任务同步执行的同时投递 DelayQueue 超时队列，到期强制取消，避免慢特征拖垮主链路；特征请求批量合并 + 懒加载按需取数，降低特征 RPC 开销；全链路 SPI 插件化：数据源（DataProvider）、执行器（Release/Batch/Af 等）、命令处理器、策略执行入口（MapFunction）均 SPI 装配，新数据源/执行模式零侵入接入；一套内核、双运行时：在线（Spring 长驻服务）与离线（ODPS Map-Only 任务）复用同一套 SA/SB + MapFunction + PolicyModel 内核，离线仅在 Mapper.setUp 完成引擎装配，避免双代码栈维护。项目成果（R）：策略发布从天级发版升级为分钟级热部署，支撑日均 XX 次策略变更、线上 XXX 条策略稳定运行；在线决策接口 P99 耗时 XX ms，单机 QPS XXXX，特征合并请求使特征调用量下降 XX%；离线单任务处理 X 亿行数据；类加载体系上线后 Metaspace 零增长，未发生因策略发布导致的 FullGC/OOM。
>
> **需求原文②**：将每个画布构建一段规则脚本，通过自建引擎去解析和运行当前脚本。

## 版本说明（v2 相对 v1 改了什么）

v1 的执行语义（变量别名、六类节点语义、赋值求值、输出组装、降级告警、试算验收样本）在 v2 **全部保留**；变化的是执行形态与外围体系：从「解释画布 JSON」升级为「画布 → 规则脚本 → 自建引擎运行脚本」，并补齐热加载 / 命令同步 / 超时 / SPI / 双运行时体系。

| # | v1（试算中心执行器） | v2（平台执行引擎） |
| --- | --- | --- |
| 1 | 引擎内嵌 TrialService，直接解释画布 JSON | 新增**编译层**：每个画布 → 一段规则脚本 `policy.rl`；引擎只解析/运行脚本（§三） |
| 2 | 单游标解释执行，无缓存 | **加载态/运行态分离 + 预编译 DAG（PolicyModel）**：一次加载、N 次执行（§四） |
| 3 | 无类加载体系 | **SA/SB 双 ClassLoader 热加载**：发布/回滚不重启、Metaspace 零增长（§五） |
| 4 | 无同步机制 | **推拉结合命令同步**：DRM 触发 + seqNo 拉取 + 游标/锁/ACK，万级策略最终一致（§六） |
| 5 | 同步执行，无超时兜底 | **DelayQueue 超时强制取消**；特征批量合并 + 懒加载（§七） |
| 6 | 写死调用链 | **全链路 SPI**：DataProvider / 执行器 / 命令处理器 / MapFunction（§八） |
| 7 | 只服务试算 | **一套内核双运行时**：在线 Spring + 离线 ODPS Map-Only（§九） |
| 8 | — | 新增「项目成果（R）」（§十二，指标占位待实测回填） |

---

## 一、现状与目标

### 1.1 现状（问题是什么）

系统里的「策略的图」有两份存储，互不相通：

| 存储 | 位置 | 谁在写 | 谁在读 |
| --- | --- | --- | --- |
| 版本决策图 | `strategy_version` + `decision_node`（+ `node_edge`） | 早期登记 / 迁移数据 | 试算 `execute()` 现链路 |
| 编排画布草稿 | `decision`（DRAFT 行）+ `rule`（节点）+ `decision_branch`（连线） | 编排页「保存画布」`PUT /api/decisions/{id}/graph` | 编排页打开画布；试算 `resolve-inputs`（2026-10-15 起草稿优先） |

以 loan_apply（策略 1）当前草稿（v13）为例，用户画布的真实配置是：

```
START  开始·入参声明     inputs: testInput   outputs: risk_score / finalDecision / reasonCode / o.testInput
  ↓
BRANCH 分支·风险初筛     条件1: f.dreiver_feature > 500      else: 其余客群
  ↓ c0（条件1）              ↓ else
  └────────→ END           NORMAL 普通决策·综合评分
                           规则1: f.dreiver_feature >= 750 →
                                o.finalDecision = 'PASS'
                                o.reasonCode = '评分优秀，直接通过'
                                o.risk_score = f.dreiver_feature
                                o.testInput = c.testInput
                                  ↓
                                END 结束·组装输出
```

问题两层：

1. **草稿画布没有任何运行时执行器**。`TrialService.execute()` 跑的是版本图（为空时回落默认规则集 REJECT/R007），画布里的节点条件、赋值（`o.x = c.y`）从未被求值过——试算结果与用户当前配置无关，「没有按照用户配置的进行运行」即指此（v1 已诊断）。
2. **平台没有「配置 → 可执行物」的编译层**，也没有热加载、命令同步、超时兜底、扩展装配体系——配置改完只能靠重启或进程内直读，无法支撑在线数据面对万级策略的分钟级发布。

### 1.2 目标

| # | 目标 | 落点 |
| --- | --- | --- |
| 1 | 每个画布（草稿 / 版本）编译为**一段规则脚本**；引擎只解析和运行脚本，不再解释画布 JSON | §三 |
| 2 | 加载态/运行态分离，**预编译 DAG**，元信息一次加载、N 次执行 | §四 |
| 3 | **SA/SB 双 ClassLoader**：发布/回滚不重启，Metaspace 零增长 | §五 |
| 4 | **推拉结合命令同步**：万级策略状态最终一致 | §六 |
| 5 | 执行与超时解耦；特征请求批量合并 + 懒加载 | §七 |
| 6 | **全链路 SPI**：数据源 / 执行器 / 命令处理器 / 策略入口零侵入扩展 | §八 |
| 7 | **一套内核双运行时**：在线 Spring 与离线 ODPS 复用同一内核 | §九 |
| 8 | 试算中心作为第一消费者：试算 = 编译并运行**当前画布** | §十 |

---

## 二、总体架构

```
                    配置台（React · 编排画布）
                            │ 保存草稿 / 发布版本
                            ▼
       ┌────────────────────────────────────────────────┐
       │ 元数据服务 meta-service（Spring 单体）             │
       │  发布时编译画布 → 策略包 artifact（不可变）          │
       │  命令表 strategy_command（seqNo 递增）· ACK 汇总   │
       └───────┬────────────────────────────┬───────────┘
               │ 写命令表                    │ DRM 触发信号（仅 seqNo 提示，可丢）
               ▼                            ▼
       ┌────────────────────────────────────────────────┐
       │ 执行引擎（在线 · Spring 常驻，多实例）              │
       │                                                  │
       │  命令同步(§六) → 拉取策略包 → 资源搬运               │
       │  ┌──────────────────────────────────────────┐   │
       │  │ SA（底层 SDK jar · 全局唯一 · 跨策略复用）    │   │
       │  │   ├─ SB₁ (loan_apply v13)                 │   │
       │  │   ├─ SB₂ (credit_check v9)                │   │
       │  │   └─ SBₙ …  按 (策略,版本) 隔离             │   │
       │  └──────────────────────────────────────────┘   │
       │  加载期(§四)：config.json + policy.rl → PolicyModel │
       │  运行期(§四)：new Session + ThreadLocal → DAG 遍历  │
       │  超时强制取消(§七) · 特征批量合并/懒加载(§七)         │
       └────────────────────────────────────────────────┘
       ┌────────────────────────────────────────────────┐
       │ 离线运行时（ODPS Map-Only）                       │
       │  Mapper.setUp 装配同一套内核（SA/SB + PolicyModel）│
       └────────────────────────────────────────────────┘
```

核心组件一览：

| 组件 | 职责 | 章节 |
| --- | --- | --- |
| 画布编译器 CanvasCompiler | 画布 JSON → 规则脚本（确定性、纯函数） | §三 |
| 规则脚本 policy.rl | 平台自有的规则脚本 DSL（编译产物，人可读） | §三 |
| 脚本解析器 ScriptParser | 脚本 → PolicyModel（校验跳转/块结构） | §四 |
| PolicyModel | 预编译 DAG：Flow / Node / Edge（一次构建、N 次执行） | §四 |
| 执行引擎 ScriptEngine | Session 遍历执行 + 变量模型 + 输出组装 | §四 |
| 类加载体系 SA/SB | SDK 全局复用 + 策略按版本隔离 + 热切换回收 | §五 |
| 命令同步器 | 触发信号 + seqNo 拉取 + 游标/锁/幂等 + 批量 ACK | §六 |
| 超时管理器 | DelayQueue 到期强制取消 + 兜底结论 | §七 |
| 特征批量器 | 懒加载 + 请求内批量 + 微批合并 | §七 |
| SPI 装配 ExtensionLoader | DataProvider / 执行器 / 命令处理器 / MapFunction | §八 |
| 双运行时适配 | 在线 Spring / 离线 ODPS Mapper 共用内核 | §九 |

---

## 三、画布 → 规则脚本（编译层）

### 3.1 编译时机与产物

| 时机 | 输入 | 产物 | 去向 |
| --- | --- | --- | --- |
| 草稿保存 / 试算触发 | 草稿画布（`decision` + `rule` + `decision_branch`） | 规则脚本（内存） | 试算中心直接运行（P1） |
| 策略发布 | 画布快照（版本不可变） | 策略包 artifact | 制品库 → 命令表 → 引擎装载（P2） |

策略包结构（发布产物，不可变）：

```
policy-package-{policyCode}-{version}.jar
├── config.json      # 策略元信息：policyId / version / 入口类(MapFunction) / 脚本路径 /
│                    #              超时配置 / 数据源声明 / 依赖清单
├── policy.rl        # 规则脚本（画布编译产物，人可读，可 diff）
└── classes/…        # 可选：策略自定义执行器 / DataProvider 实现（SB 加载）
                     # 注意：SDK 类一律 provided，不打进策略包（§5.1）
```

编译是**纯函数 + 确定性**：同一画布 JSON → 字节级相同的脚本；`checksum = sha256(config.json + policy.rl)` 用于装载校验、热更新 diff 与问题定位。

### 3.2 脚本语法（DSL，P0）

```text
# 注释行

[nodeId] TYPE 标题            ← 节点块头（锚点：trace / 报错定位 / 可视化）

requires a, b                 ← 入参契约（来自 START.inputs），供引擎预检

if <expr> then goto L         ← 条件跳转（BRANCH 端口去向 / 编译后的判定）
if <expr> then goto L else goto M
goto L                        ← 无条件跳转

if <expr> then                ← 守卫赋值块（NORMAL / CASE 规则、CROSS 单元格）
  o.name = <rhs>
  …
end

emit o.a, o.b, …              ← END 组装输出（按 START 出参契约）
```

表达式 `<expr>`（P0）与现有 `engine/ConditionEvaluator` **同一套语义**（复用同一求值器，不引入第二套语法）：

- 字面量：数字 / 单双引号字符串 / `true` / `false` / `null`；
- 变量引用：`c.<name>`（策略入参）、`f.<code>`（特征）、`o.<name>`（已有输出）；兼容 `param.` / `feat.` 长前缀别名；
- 比较：`== = != <> >= <= > <`；逻辑：`and / &&`、`or / ||`、`not`；
- 不支持：算术 / 函数调用 / 嵌套表达式（求值 UNKNOWN → 不命中 + ⚠，**绝不当成成立**；P2 扩展，见 D6）。

赋值 `<rhs>`（P0）= 字面量 | 变量引用（含裸名，按现有查找口径：精确 → 末段名 → 忽略大小写）。

脚本由编译器生成、**不面向手写**；但引擎解析器仍严格校验（标签存在、块结构闭合、单出口一致），防止脏数据。

### 3.3 节点 → 脚本映射（六类节点）

| 画布节点 | 语义（编辑器口径） | 编译为 |
| --- | --- | --- |
| START | inputs / outputs 契约 | `requires` 行 + 登记出参槽（o.*）；无执行语句；`goto` 唯一出边 |
| BRANCH | conditions 顺序求值，首个 TRUE 走端口 `c{i}`；全 FALSE 走 `else` | 逐条 `if <cond_i> then goto <c_i 边目标>`，末尾 `goto <else 边目标>`；端口未连线 → **编译错误** |
| CASE | rows 首行优先，空 cond = 兜底行，命中行执行其 outputs | 守卫赋值块序列：`if <row.cond> then … end`（空 cond 直接编译为赋值块）；块后 `goto` 唯一出边 |
| NORMAL | rules 同 CASE 结构（用户画布最常用） | 同 CASE |
| CROSS | rowVar / colVar 取实值 → 首个匹配行列 → `cells[ri][ci]` 赋值 | 编译为「行判定 × 列判定」守卫块链（首命中）；与 04 章「矩阵统一编译成规则集执行」一致；行列值比较：两边可解析为数字按数值比，否则 trim 后字符串相等 |
| END | 组装输出 | `emit <START 出参契约>`；结论提取约定见 §4.5 |

编译期同时为每个节点标注运行期元信息（写进 PolicyModel Node）：

- `featureRefs[]`：本节点引用到的特征（供懒加载 / 批量合并 / nodeDetails）；
- `varReads[]`：本节点表达式与赋值引用到的变量（供 nodeDetails / trace）；
- `jump[]`：跳转目标（已解析为节点引用，运行期不再查表）。

### 3.4 loan_apply 真实画布编译示例（草稿 v13）

原始画布片段（`rule.config_json` 实拍）：

```json
// n_001 START
{"inputs":[{"name":"testInput","type":"STRING","required":false,"defaultValue":"","desc":""}],
 "outputs":[{"name":"risk_score ","type":"INTEGER","desc":""},
            {"name":"finalDecision ","type":"STRING","desc":""},
            {"name":"reasonCode ","type":"STRING","desc":""},
            {"name":"o.testInput","type":"STRING","desc":""}]}

// n_002 BRANCH
{"conditions":[{"expr":"f.dreiver_feature > 500","label":"条件 1"}],"elseLabel":"其余客群"}

// n_005 NORMAL
{"rules":[{"cond":"f.dreiver_feature >= 750",
           "outputs":"o.finalDecision = 'PASS'\no.reasonCode = '评分优秀，直接通过'\no.risk_score = f.dreiver_feature\no.testInput = c.testInput"}]}

// 连线 decision_branch：n_001→n_002；n_002(c0)→n_006；n_002(else)→n_005；n_005→n_006
```

编译产物 `policy.rl`：

```text
# policy: loan_apply   source: draft v13   checksum: <sha256 前 12 位>
# params: testInput
# features: dreiver_feature

[n_001] START 开始·入参声明
requires testInput
goto n_002

[n_002] BRANCH 分支·风险初筛
if f.dreiver_feature > 500 then goto n_006     # 条件 1（端口 c0）
goto n_005                                     # 其余客群（端口 else）

[n_005] NORMAL 普通决策·综合评分
if f.dreiver_feature >= 750 then               # 规则 1
  o.finalDecision = 'PASS'
  o.reasonCode = '评分优秀，直接通过'
  o.risk_score = f.dreiver_feature
  o.testInput = c.testInput
end
goto n_006

[n_006] END 结束·组装输出
emit o.risk_score, o.finalDecision, o.reasonCode, o.testInput
```

> 如实说明：当前连线是「条件 1 → END、else → 普通决策」，且普通决策条件是 `>= 750`——所以 `f > 500` 时直达 END、`f ≤ 500` 时规则不命中，`o.*` 赋值在当前连线**不可达**。这不是引擎问题，是画布现状；引擎**如实执行用户配置**，用户在编排页调好端口后，无需改任何代码、重新试算即生效——这正是「按用户配置运行」的含义。

### 3.5 编译期归一化与校验

**归一化**（画布存在真实脏写法，编译期静默修正 + ⚠ 提示）：

| 情形 | 处理 |
| --- | --- |
| 名称尾空格（`"risk_score "`、`"finalDecision "`） | `trim()` |
| 把命名空间前缀写进名字（`"o.testInput"`） | 剥掉前缀 → `testInput` |
| 输出未在 START 契约声明 | 自动登记出参槽 + ⚠「出参 x 未在开始节点声明」 |
| 重名冲突（归一后） | **编译错误**（阻断发布 / 试算报错面板） |

**校验清单**：

| 检查项 | 级别 | 处理 |
| --- | --- | --- |
| 跳转目标不存在 / 块未闭合 | 错误 | 编译失败（发布拦截） |
| 端口未连线（BRANCH 条件端口或 else 无对应边） | 错误 | 同上 |
| 非 END 节点无出边 / 多出边（脏数据） | 错误 / 取 id 最小 + ⚠ | 同上 |
| 环（画布非 DAG） | 错误 | 编译失败（运行期另有步数上限双保险） |
| 不可达节点 | 警告 | 照常编译 + ⚠ |
| 引用未声明的 `c.*` / 未上线 `f.*` | 警告 | 照常编译；运行期按缺失语义（不命中 / 兜底 ⚠） |

---

## 四、加载态 · 运行态分离（预编译 DAG）

### 4.1 加载期（一次）——「元信息一次加载」

对每个 `(tenantId, policyCode, version)` 只做一次：

| 步骤 | 内容 | 产物 |
| --- | --- | --- |
| ① 资源搬运 | 策略包从制品库下载到本地工作目录（或直接持内存字节）；jar 句柄登记（供回收时 close） | 本地包 |
| ② config.json 解析 | PolicyConfig：policyId / version / 入口类 / 脚本路径 / 超时 / 数据源声明 | PolicyConfig |
| ③ 脚本解析 | ScriptParser：tokenize → 块结构 → 标签表 → 语句 AST；校验跳转目标、块闭合、单出口 | ScriptUnit |
| ④ PolicyModel 构建 | Flow / Node / Edge 图；条件表达式预解析缓存；节点 featureRefs / varReads 标注 | PolicyModel |
| ⑤ 缓存 | `PolicyRuntime = { PolicyConfig, ScriptUnit, PolicyModel, checksum }`，按 key 缓存，`AtomicReference` 持有 | 缓存项 |

### 4.2 PolicyModel 结构（设计说明）

```
Flow   { entry : NodeId, nodes : Map<NodeId, Node> }

Node   { nodeId : String, nodeType : START|BRANCH|CASE|CROSS|NORMAL|END,
         title  : String,
         statements : List<Stmt>,        // 赋值 / 守卫块（条件已预解析）
         jumps      : List<Jump>,        // 条件跳转（expr + 目标 Node 引用）
         featureRefs: List<String>,      // 编译期标注
         varReads   : List<String> }

Edge   { from : NodeId, port : String?, to : NodeId }   // 供 trace / 可视化 / 热更新 diff
```

跳转目标在编译/解析期已解析为 Node 引用，**运行期零字符串比较、零图查询**。

### 4.3 运行期（N 次）——「N 次执行」

```
run(input, deadline):
  session = new Session(input, deadline)         ① 每次执行新建 Session
  ThreadLocal.set(session)                       ② 单线程路径取用；跨线程池显式传引用（不依赖跨池传播）
  fn = reflect(entryClass, sb)                   ③ SB 反射实例化入口（MapFunction）
  return fn.apply(session)                       ④ 执行 PolicyModel：
                                                    从 Flow.entry 起，执行当前节点语句块；
                                                    jump 推进执行点；END 的 emit 汇总输出
  finally: ThreadLocal.remove()                  ⑤ 防池线程残留（§5.3）
```

- **无锁并发**：PolicyRuntime 与 PolicyModel 构建后不可变，多线程只读共享；全部执行态都在私有的 Session 里；发布 = `AtomicReference` 原子替换，切换与执行互不阻塞。
- **单路径语义**：每次执行走一条路径（与画布交互一致）；并行分支不在本期。
- **防护**：编译期保证 DAG + 运行期步数上限（节点数 × 2 + 16），双保险，越界截断 + ⚠。

### 4.4 变量模型（命名空间映射）

画布表达式用短前缀（编辑器 chips 与引用校验口径），引擎在绑定层做**别名双写**（v1 语义保留）：

| 画布写法 | 含义 | 引擎变量表键 |
| --- | --- | --- |
| `c.<name>` | 策略入参（START inputs 声明） | `c.name` + `param.name` |
| `f.<code>` | 特征（已上线特征编码） | `f.code` + `feat.code` |
| `o.<name>` | 出参（START outputs 声明；节点赋值写入） | `o.name` |

绑定规则：

- **入参**：请求 JSON 键 → `c.<key>`；START 声明但 JSON 未给 → 取声明 `defaultValue`；两者都无 → 不绑定（求值走缺失语义）；
- **出参**：START 契约每个 name 先注册空槽（null）；赋值写入 / 覆盖；
- **特征**：沿用现有回填口径（出参定义优先、衍生单值兜底、失败挂 ⚠），额外写 `f.<code>` 别名；
- **名称归一化**：注册与匹配前统一按 §3.5 归一。

### 4.5 输出组装与结论口径

画布输出 = START 出参契约 × `o.*` 赋值快照：

```json
"outputs": { "risk_score": 812, "finalDecision": "PASS", "reasonCode": "评分优秀，直接通过", "testInput": "abc" }
```

画布输出名不受约束，结论三件套按约定提取：

| 字段 | 取法 | 找不到时 |
| --- | --- | --- |
| `record.conclusion` | outputs 里名为 `finalDecision`（归一化匹配）的值 | `—`（如实显示无结论） |
| `record.reasonCode` | outputs 里名为 `reasonCode` 的值 | `DRAFT_OUTPUT`（有输出但无原因码） |
| `record.hitRule` | 最后一个产生过赋值的节点（NORMAL / CASE / CROSS）：`nodeKey + 标题`；一个都没命中 → 记路径上的 BRANCH / START 节点 | `—` |
| `response.hitNode` | 同上节点的 nodeKey | `null` |
| `response.execSource` | **`DRAFT_SCRIPT`**（草稿编译运行） | 输出全空 → **`DRAFT_NO_OUTPUT`**，前端「编排画布·未产出输出」 |

**关键行为变化：移除默认规则集兜底**。旧链路在无图 / 无命中时替用户虚构 REJECT/R007 结论；v2 一律如实报告「未产出输出」，不替用户下结论（决策点 D5）。

### 4.6 降级与告警

| 场景 | 行为 |
| --- | --- |
| 条件表达式 UNKNOWN（语法不支持 / 变量缺失） | 不命中 + 节点明细 `EVAL_FAIL` + ⚠ 注明原因 |
| 赋值行不合法 / RHS 不支持 | 跳过该行 + ⚠「第 N 行：不支持的表达式 …」 |
| 出参未声明 | 自动注册 + ⚠ |
| 环 / 步数超限 | 截断 + ⚠ |
| 特征引擎失败 | 内置派生兜底 + ⚠（现状不变） |
| 执行超时（§七） | 强制取消 + 兜底结论（策略级配置，默认 REVIEW，D9）+ 降级标记 |

所有 ⚠ 归集到响应 `warnings` 数组；`record.degraded = 有 ⚠ ? 1 : 0`。

---

## 五、SA/SB 双 ClassLoader 热加载

### 5.1 装载切分与双亲委派

| 加载器 | 装载内容 | 生命周期 | 作用域 |
| --- | --- | --- | --- |
| **SA** | 底层 SDK jar：执行契约与基础设施（MapFunction / PolicyModel / Session / SPI 接口 / 工具类） | 进程级，启动装载 | 全局唯一，**跨策略复用** |
| **SB** | 策略包（classes/ 内业务类） | 按 (策略, 版本) 一个实例 | **按版本隔离**，随版本切换回收 |

- **双亲委派保证入口类全局单实例**：`SB.parent = SA`；SB 收到加载请求先委派 SA——凡 SDK 内已有的类（MapFunction 接口、执行器基类、SPI 契约）一律由 SA 加载，全平台 Class 身份唯一，跨策略转型 / instanceof / SPI 查找全部成立；SA 找不到的策略私有类才由 SB 加载，天然隔离。
- **包装约定**：

| | 做法 |
| --- | --- |
| ❌ | 策略包把 SDK 类 shade / 重复打进去 → SA、SB 各有一份同名类 → `ClassCastException`、入口类转型失败 |
| ✅ | SDK 依赖一律 `provided`；发布流水线校验策略包内不得出现 SDK 包名前缀的类，构建期直接失败 |

### 5.2 发布 / 回滚流程（不重启）

```
发布：meta 编译打包 → 制品库（不可变）→ 写命令表（§六）
      → 引擎拉命令 → 加载期（§4.1，含 checksum 校验）
      → AtomicReference.set(new PolicyRuntime{ 新 SB })
回滚：指针切回旧 PolicyRuntime（旧 SB 未被回收时——秒级）
      或按命令重新装载历史版本包（幂等，与发布同路径）
```

- **不断流**：切换在请求间隙原子完成；正在执行的 Session 持有旧 PolicyRuntime 引用，自然跑完，结果不受影响；
- **多版本并存**：允许 per-version PolicyRuntime 同时缓存（灰度 / 定点调试 / 离线对账），由路由选版本执行。

### 5.3 ClassLoader 回收检查清单（Metaspace 零增长的关键）

| 泄漏点 | 规避 |
| --- | --- |
| ThreadLocal 残留（池线程的 ThreadLocalMap 持有 Session → 持有策略类） | 执行入口 `finally ThreadLocal.remove()`；Session 不跨请求存活 |
| 全局缓存持有 Class / 策略实例 | 只缓存 PolicyRuntime（随替换整体可回收）；禁止静态 Map 存 Class/实例 |
| 策略代码自注册（线程 / 定时器 / ShutdownHook / JDBC Driver / 静态单例） | SDK 提供托管 API；沙箱禁止（后续按 05 章沙箱清单执行） |
| jar 句柄未关 | SB 回收时 `URLClassLoader.close()`，释放文件句柄与映射 |
| 日志上下文引用对象 | MDC 只放字符串（traceId 等），不放对象 |

回收时机：`PolicyRuntime` 被替换后无执行引用（引用计数归零 / 活跃 Session 清零）→ 关闭 SB → 等待 ClassUnloading（`-XX:+ClassUnloadingWithConcurrentMark` 默认开）释放 Metaspace。

### 5.4 验证方法（对应 R 指标）

发布 / 回滚循环压测（连续百次以上），观测：

- `jcmd <pid> VM.metaspace` 曲线平稳（不随发布次数阶梯上涨）；
- GC 日志中无由发布触发的 FullGC；堆内无 SB 相关的 ClassLoader 实例滞留；
- 发布 → 生效端到端时长（命令写入 → 全部实例 ACK）进入分钟级以内。

---

## 六、推拉结合的策略命令同步

### 6.1 命令模型

| 项 | 设计 |
| --- | --- |
| 存储 | 元数据表 `strategy_command`（P3 DDL）：`id / seq_no（单调递增）/ tenant_id / policy_code / action(PUBLISH·ROLLBACK·DELETE·RELOAD) / version / artifact_uri / created_at` |
| 推送（DRM） | **仅作触发信号**：`{提示 seqNo}` 极简消息；丢了无害（拉取兜底），到了加速（立即唤醒拉取） |
| 拉取 | 引擎按 `seqNo > cursor` 增量拉取命令数据（批量分页），数据以拉取为准 |

### 6.2 拉取与消费循环（引擎侧单实例）

```
loop（~1s 轮询 + 收到 DRM 信号立即唤醒）:
  cmds = pull(seqNo > cursor, batch = 500)
  for cmd in cmds:
      lock = lockOf(tenantId + policyCode)          ← 业务键粒度锁
      sync(lock):
          if cmd.seqNo <= perPolicySeq[policyCode]: skip     ← 幂等（重复拉取/重复下发无害）
          执行 action（装载 / 切指针 / 删除）→ perPolicySeq[policyCode] = cmd.seqNo
  cursor = max(seqNo)（volatile long，单写者）
  持久化 cursor（本地文件 / DB，重启续传）
  异步批量 ACK：{instanceId, 已消费 seqNo 区间} → meta 汇总（防重复下发；ACK 丢失靠幂等重放）
```

- **volatile 游标**：单实例内单写多读；全局游标只由同步线程写，读侧无锁；
- **业务键粒度锁**：同一策略的命令**串行消费**（保证 PUBLISH v2 → v3 不乱序），不同策略**并行消费**（万级策略互不阻塞）；
- **串行 ≠ 全局锁**：锁只护单策略的装载指针切换，毫秒级临界区。

### 6.3 幂等、防重与 ACK

| 机制 | 说明 |
| --- | --- |
| 幂等 | 每条命令带 seqNo；per-policy 已消费水位 `perPolicySeq`，seqNo ≤ 水位直接跳过——重复投递、重复拉取、ACK 丢失重放全部安全 |
| 防重复下发 | 消费后异步批量 ACK；meta 侧按实例 ACK 水位判断是否需要重推 |
| ACK 异步 | 攒批上报（时间 / 条数阈值触发），不打在消费热路径上 |

### 6.4 一致性与恢复

- **最终一致**：万级策略不做同步阻塞发布；发布完成判定 = 全实例 ACK 覆盖该 seqNo（超时仅告警，不算失败）；
- **重启 / 断点**：从持久化 cursor 续传；
- **对账兜底**：cursor 丢失或长期落后实例 → 启动时拉取「各策略当前生效版本」清单与本地 PolicyRuntime 比对，不一致的按清单装载（不依赖命令历史）；运行期靠拉取循环自愈。

> 首版无 DRM 也能跑（D8）：DB 轮询 + 同一 seqNo 协议即为完整通路；DRM 就绪后只是加一个「立即唤醒」的加速信号，协议不变。

---

## 七、执行与超时解耦

### 7.1 DelayQueue 超时队列（强制取消）

```
执行侧:
  future = 提交决策任务（同步执行）
  t = new TimeoutTask(deadlineNanos, future, session)
  delayQueue.offer(t)
  任务正常完成 → t.done = true                    ← 懒删除
超时线程（单守护线程）:
  t = delayQueue.take()                           ← 到期出队
  if !t.done:
      future.cancel(true)                         ← 中断执行线程
      session.flags |= TIMEOUT
      兜底结论（策略级配置，默认 REVIEW，D9）+ 降级标记 → 正常返回
```

- **目的**：慢特征 / 慢数据源到期被强制掐断，**不拖垮主链路**；等待下游的阻塞调用被中断后立即释放线程；
- **双层超时**：deadline 协作式检查（每次外部调用前判断剩余预算，见 05 章预算口径）+ 本队列强制式兜底；
- **不可中断段**：已提交但无法中断的操作，结果作废丢弃 + 释放配额；
- **与 Session 的关系**：超时标记写进 Session.flags，输出组装时如实挂降级标记（§4.6）。

### 7.2 特征请求批量合并 + 懒加载

| 机制 | 说明 |
| --- | --- |
| 懒加载（按需取数） | 编译期已给每节点标注 `featureRefs`；运行期**首次走到引用缺失特征的节点**才触发取数（节点级懒加载）；试算 / 离线 = 脚本级全量批量（一次调用取全） |
| 请求内批量合并 | 同一执行内多个特征 → **一次批量调用** `getFeatures(bizKeys[], codes[])`，替掉逐特征 N 次 RPC；响应按 (bizKey, code) 拆分回填各 Session |
| 跨请求微批合并（P4 可选） | 小窗口（≤5ms）+ 容量阈值触发，多笔决策合并一发；在线风控默认关闭（谨慎引入延迟），批量回放 / 离线默认开启 |

对应 R 指标「特征合并请求使特征调用量下降 XX%」——上线后以调用计数对比回填。

---

## 八、全链路 SPI 插件化

统一装配器 ExtensionLoader（`META-INF` 声明，SA 启动扫描；策略包内声明随 SB 生效/回收）：

| 扩展点 | 接口 | 内置实现 | 装载域 |
| --- | --- | --- | --- |
| 数据源 | DataProvider | 在线：MySQL / HTTP / CSV / 本地文件…；离线：ODPS 表 | SA 全局（策略可自带 → SB） |
| 执行器 | StrategyExecutor / NodeExecutor | **Release**（在线单笔决策）/ **Batch**（批量回放）/ **Af** 等 | SA 全局 |
| 命令处理器 | CommandHandler | PUBLISH / ROLLBACK / DELETE / RELOAD | SA 全局 |
| 策略执行入口 | MapFunction | SDK 默认实现（运行 PolicyModel 脚本）；策略可自带实现类（config.json 指定） | 策略包，SB 加载 |
| 装配器本体 | ExtensionLoader | 扫描 / 注册 / 查找 / 卸载钩子 | SA |

- **零侵入**：新数据源 / 新执行模式 / 新命令类型 = 新增一个实现类 + 一条声明，内核与主流程不动；
- **类身份一致**：所有 SPI 接口由 SA 加载 → 策略包（SB）里的实现能正确转型为接口（§5.1 的直接收益）。

---

## 九、一套内核 · 双运行时

| 维度 | 在线（Spring 常驻服务） | 离线（ODPS Map-Only 任务） |
| --- | --- | --- |
| 装配时机 | 应用启动 + 命令同步热加载 | **仅 Mapper.setUp 一次性装配** |
| 类加载 | SA 全局 + SB 按版本，AtomicReference 热切换 | SA 从作业资源加载 + SB 装载作业内策略包 |
| 执行入口 | MapFunction（HTTP 请求路径） | MapFunction（`map()` 每行调用一次） |
| 一次执行 | 一笔决策 = 一个 Session | 一行数据 = 一个 Session |
| 超时 | DelayQueue 强制取消（§七） | 不启用强超时（作业 SLA 宽松），或复用同一管理器 |
| 数据源 | 在线（DB / Redis / HTTP）DataProvider | 离线（ODPS 表）DataProvider |
| 内核复用 | **同一套 SA/SB + MapFunction + PolicyModel 内核** | 同上——无业务逻辑分叉 |

离线作业流程：作业启动 → `Mapper.setUp` 装配引擎（SA/SB + 策略包 + PolicyModel）→ 逐行 `map()` 执行同一份脚本内核 → 结果写回行。**避免双代码栈维护**：在线验证过的策略脚本与执行语义，离线原样运行。

---

## 十、试算中心（第一消费者，P1 落地）

### 10.1 试算执行链改动

```
现状（v1 前）：execute() 读版本图 → 条件短路 → 默认规则集兜底（虚构结论）
v2 目标链  ：
  解析入参（已草稿优先，不变）
    → 有草稿：CanvasCompiler.compile(草稿) → policy.rl
              → ScriptParser → PolicyModel → ScriptEngine.run(vars)
              → outputs + nodeDetails + featureRuns + steps/trace
    → 无草稿：回落版本图现状（execSource = GRAPH），后续 P2 并入同一编译链
```

- 试算中心 = 编译层 + 引擎内核的**进程内直用**（P1 不依赖策略包 / SA/SB / 命令同步——这些在 P2/P3 接入，试算届时切到正式制品链路）；
- **移除 DEFAULT_RULES**：无图可跑 / 无输出时如实报告 `DRAFT_NO_OUTPUT`，不替用户下结论；
- resolve-inputs **不改**（已草稿优先）。

### 10.2 响应与前端

| 接口 / 文件 | 变更 |
| --- | --- |
| `POST /trial/execute` | 草稿存在时走编译 + 引擎；`execSource` 新增 `DRAFT_SCRIPT` / `DRAFT_NO_OUTPUT`（原 `DRAFT_GRAPH` 命名调整）；`response.outputs`、`response.warnings` 新增；可选 `scriptPreview`（编译产物文本 + checksum） |
| `frontend/src/pages/Trial.jsx` | `execSourceText` 映射新枚举（「编排画布·脚本执行」/「编排画布·未产出输出」）；「最终输出」卡渲染 outputs 字段（不再只有结论徽标）；「执行路径」升级为脚本步骤视图（节点块 + 判定结果 + 赋值明细）；新增折叠「编译脚本」面板（显示 policy.rl + checksum，评审 / 排错用）；nodeDetails 徽章新增 `SKIPPED`（未走到）映射 |
| DDL | **无**（P1） |

### 10.3 nodeDetails 口径（沿用现结构 + 一处新增）

- 覆盖**全图节点**：走到的按实际结果（`HIT / COND_FALSE / EVAL_FAIL / INFO / OUTPUT`），未走到的 `SKIPPED` +「未走到·路径不含此节点」；
- `varsRead` = 该节点表达式 / 赋值引用到的变量实时值（取不到标 missing 灰显）；
- BRANCH 明细写全条件求值过程（`条件 1 true → c0`；全部未命中 → `else`）；
- NORMAL / CASE / CROSS 命中写出 `o.x = 值` 序列（如 `o.finalDecision = 'PASS' · o.risk_score = 812 · o.testInput = 'abc'`）。

### 10.4 验收（真实数据，只读执行）

| # | 场景（样本） | 期望 |
| --- | --- | --- |
| 1 | loan_apply 草稿：调 `a` / `b` 使 `f.dreiver_feature` 走 c0 直达 END（v13 条件 `> 500` 时 a=10,b=20；v14 条件改为 `< 500` 后反向，a=1,b=2 → 300） | BRANCH 条件 1 TRUE → 直达 END；nodeDetails：n_002 `HIT`（c0）、n_005 `SKIPPED`、n_006 `OUTPUT`；outputs 全空（赋值节点未走到）→ `execSource = DRAFT_NO_OUTPUT`——**如实反映当前连线，不出现默认规则集 REJECT** |
| 2 | loan_apply：走 else 到 n_005（v14：a=10,b=20 → f=3000） | `f ≥ 750` → n_005 `HIT`，outputs 全齐（`o.finalDecision='PASS'`、`o.risk_score=3000`、`o.testInput=入参原值`）→ `DRAFT_SCRIPT`；`f < 750` 则 n_005 `COND_FALSE`、outputs 全空 → `DRAFT_NO_OUTPUT` |
| 3 | traffic_demo 草稿（START test1/test2 → NORMAL `cond=true` → END，`o.test1=c.test1` / `o.test2=c.test2`） | outputs 原值透传——**用户报障样本（`o.test1=c.test1`）逐字核验项**；恒真条件验证守卫块执行 |
| 4 | 画布改连即生效演示（可选）：在 traffic_demo（演示策略，可随意改）上把 NORMAL 端口改连 / 增删节点后重试算 | 无需改代码、无需重启：重新编译 → checksum 变化 → 执行路径随新画布走。**loan_apply / credit_check 为真实数据，只读执行不得改动** |
| 5 | credit_check 草稿（START + END 均为 `{}`） | 「画布为空·未产出输出」+ `DRAFT_NO_OUTPUT`；解析入参同步提示空模板 |
| 6 | 无草稿策略 / 手动选版本路径 | 保持现状（`GRAPH`），回归不受影响 |
| 7 | 留痕 | steps / nodeDetails / trace（track_record / track_step / track_var，含 `c.*` / `o.*`）正常落库；重复执行同输入 → 同路径同输出（确定性） |

> 实测记录（2026-10-15，P1 落地）：#1 / #2 / #3 / #5 / #7 已通过——后端 `POST /trial/execute` 直连 + 前端浏览器双通道核验；
> 归一化告警（loan_apply 出参名尾空格 / `o.testInput` 前缀）按 D2「自动修正 + ⚠」如实回显；确定性以两次复跑 checksum / 路径 / 输出逐项一致为准。#4 为可选演示，样本改用 traffic_demo。
> 追加：loan_apply 草稿在核验后被并行编辑为 v14（条件 `> 500` → `< 500`，checksum 04cd08417686 → 68db22ba1989），
> 重跑即随新画布走（f=3000 → else → PASS 全出参；f=300 → c0 直达 END 全空）——「改画布即生效」在真实数据上得到二次印证。

### 10.5 边界（P1 不做）

- 算术 / 函数 RHS（挂 ⚠ 跳过，P2 扩展）；
- 并行分支 / 子流程 / 回边循环（单路径 + 环检测）；
- 版本图并入编译链（P2）；策略包制品化 / SA/SB / 命令同步 / 在线 API / 离线运行时（P2-P5）；
- DATA 节点真实取数（试算不直连数仓，特征由引擎内部真实取数，沿用现状）；
- 画布静态校验增强（编辑器已有引用校验，引擎只做编译期 → 运行期告警）。

---

## 十一、落地分期与改动清单

### 11.1 分期

| 期 | 内容 | 交付标志 |
| --- | --- | --- |
| **P1** | 画布编译器 + 脚本 DSL/解析器 + 引擎内核（Session/PolicyModel）+ 试算中心集成 + 前端脚本视图 | 试算按当前画布真实运行（本报告验收 §10.4 通过） |
| P2 | 策略包制品化（config.json + policy.rl + jar）+ SA/SB 装载 + 发布/回滚热切换 + 版本图并入编译器 | 在线引擎雏形：发布不重启、版本可回滚 |
| P3 | 命令同步（seqNo 拉取 + 游标 + 业务键锁 + 批量 ACK） | 万级策略状态最终一致 |
| P4 | 在线决策 API 走新引擎 + DelayQueue 超时 + 特征批量合并/懒加载（含跨请求微批） | 在线数据面切换，P99/QPS 达标 |
| P5 | 离线 ODPS Map-Only 运行时（Mapper.setUp 装配同一内核） | 双运行时闭环，离线任务产出 |

### 11.2 P1 文件清单（本次实现范围）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 后端 新增 | `engine/script/ScriptCompiler.java` | 画布 JSON → policy.rl（六类节点映射 + 归一化 + 校验） |
| 后端 新增 | `engine/script/ScriptParser.java` | 脚本 → PolicyModel（Flow/Node/Edge + 校验） |
| 后端 新增 | `engine/script/PolicyModel.java` | 预编译 DAG 结构与节点元信息（featureRefs / varReads） |
| 后端 新增 | `engine/script/ScriptEngine.java` | Session 遍历执行 + 变量模型 + 输出组装 + 告警归集 |
| 后端 新增 | `engine/script/Session.java` | 执行态：vars / deadline / flags / trace |
| 后端 修改 | `service/TrialService.java` | `execute()` 草稿分支：编译 + 引擎运行；移除默认规则集兜底；响应扩展（outputs / warnings / scriptPreview）；steps / nodeDetails 按引擎产物重组 |
| 后端 不改 | `engine/ConditionEvaluator.java`（求值语义唯一来源）、`service/FeatureService.java`（`trial` 复用） | — |
| 前端 | `frontend/src/pages/Trial.jsx` | execSource 新映射；输出字段渲染；脚本折叠面板；SKIPPED 徽章 |
| DDL | 无 | P3 增 `strategy_command` |

**数据纪律**：策略 1（loan_apply）/ 策略 2（credit_check）为真实数据——只读执行，不写不改；traffic_demo（策略 5）为演示策略，可随意改删。

---

## 十二、项目成果（R）

| # | 成果 |
| --- | --- |
| R1 | 策略发布从天级发版升级为分钟级热部署，支撑日均 XX 次策略变更、线上 XXX 条策略稳定运行； |
| R2 | 在线决策接口 P99 耗时 XX ms，单机 QPS XXXX，特征合并请求使特征调用量下降 XX%； |
| R3 | 离线单任务处理 X 亿行数据； |
| R4 | 类加载体系上线后 Metaspace 零增长，未发生因策略发布导致的 FullGC/OOM。 |

> 说明：`XX` / `X` / `XXXX` 为**占位符**，上线后按实测回填：
> R1 ← §六 发布链路端到端时长与命令量统计；R2 ← §四+§七 压测（空策略基线 → 典型策略）与特征调用计数对比；R3 ← §九 离线作业运行日志；R4 ← §5.4 metaspace / GC 观测。

---

## 附 · 待确认决策

| # | 问题 | 推荐 |
| --- | --- | --- |
| D1 | 草稿存在时试算执行口径（与手动选版本的关系） | **一律执行草稿**（与解析入参同口径），版本标签显示「草稿 vN」，`routing = null`；无草稿才回落版本图（`GRAPH`）。v1 D1 保留 |
| D2 | 编译期归一化（尾空格 / `"o.testInput"` 前缀误写 / 未声明出参） | **自动修正 + ⚠**，不阻断（保证画布合法赋值永远有结果可看，同时明示契约不齐） |
| D3 | 赋值 RHS 的算术 / 函数支持 | P0 **不支持**（挂 ⚠ 跳过）；P2 随 DSL 扩展排期 |
| D4 | CROSS 行列值匹配口径 | **数值感知**（两边可解析为数字按数值比，否则 trim 后字符串相等） |
| D5 | 结论提取与兜底 | 约定 `finalDecision` / `reasonCode` 输出名（归一化匹配）；**移除默认规则集兜底**——无输出时如实显示 `—` / `DRAFT_NO_OUTPUT`，不替用户虚构结论 |
| D6 | 脚本 DSL 语法范围 | P0 见 §3.2（跳转 + 守卫赋值 + 逻辑比较，复用 ConditionEvaluator）；扩展方向向后兼容（新增表达式能力不破坏既有脚本） |
| D7 | 策略包内容与依赖纪律 | `config.json + policy.rl + classes/`（SDK 一律 provided）；发布流水线校验策略包不得含 SDK 类，违规构建期失败 |
| D8 | 命令同步首版形态 | **无 DRM**（DB 轮询 + 同一 seqNo 协议即为完整通路）；DRM 接入仅是「立即唤醒」加速信号，协议不变 |
| D9 | 超时兜底结论 | 策略级可配，**默认 REVIEW**；离线运行时默认不启用强超时 |
| D10 | P1 试算中心是否直接切策略包链路（SA/SB + artifact） | **不切**：P1 进程内直用编译 + 引擎内核；P2 制品化后试算切正式链路。确认分期节奏 |
