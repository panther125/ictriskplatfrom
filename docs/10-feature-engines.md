# 10 衍生特征执行引擎：SQL / Java / Python / OP 四引擎（实现说明）

> 实现日期：2026-10-13 ｜ 对应需求：衍生特征验证从「只回显不执行」（`simulated=true` 的引用取值核对）
> 升级为**表达式真实执行**——`@@基础特征编码` / `:param.x` 先替换为各语言字面量，再按加工方式
> （SQL / Java / Python / OP）真实跑一次，回显引擎、输出值与执行耗时。
> 设计稿见 [03-feature.md](03-feature.md) §4.3–§4.6，本文记录**实现现状与取舍**。

## 1. 执行链路总览

```
POST /api/features/{id}/trial（衍生特征）
        │
        ▼
① 解析 @@ 引用 → 逐个用「被引用基础特征的绑定视图」真实取数 → refValues：{ 编码 → 实测值 }
        │
        ▼
② DerivedExecutor.execute(feature, inputs, testValues, refValues)
        │  按 feature.compute_type 分发（大小写不敏感）
        ├── SQL    → SqlFeatureEngine    （Calcite 内存执行）
        ├── JAVA   → JavaFeatureEngine   （Janino 编译执行）
        ├── PYTHON → PythonRunnerClient  （HTTP → python-runner 独立进程池）
        └── OP     → OpTreeEngine        （算子树 JSON 求值）
        │
        ▼
③ EngineResult { engine, success, executedExpr, value, valueType, elapsedMs, warnings, error }
        │
        ▼
④ 响应：mode=EXPR_EXEC、simulated=false、engine、execElapsedMs、
   exprPreview（@@ 已替换的表达式回显）、derivedValue / derivedValueType、outputs={refs, derived}
```

要点：

- **入参物化**：引擎收到的 `testValues` 是表单里填的字符串。`EngineValues.coerce` 把纯数字还原为
  `Long` / `BigDecimal`、`true|false` 还原为 `Boolean`，其余保持字符串，避免脚本里还要手写类型转换。
- **超时**：优先取特征自身 `timeout_ms`，未配则用 `risk.derived.timeout-ms`（默认 **300ms**）。
- **`REF` 加工方式**：只允许基础特征使用；衍生特征选了 `REF` 会得到明确报错提示改选四引擎。

## 2. 统一契约

### 2.1 EngineResult（四引擎统一返回）

| 字段 | 说明 |
|---|---|
| `engine` | 实际执行的引擎：`SQL` / `JAVA` / `PYTHON` / `OP` |
| `success` / `error` | 成败与失败原因（各引擎把底层异常转成一句可读中文） |
| `executedExpr` | **@@ / :param 已替换为字面量后的可执行表达式**（回填 `exprPreview`，前端可展开核对） |
| `value` / `valueType` | 实算输出值与推断出的平台数据类型（STRING / INTEGER / LONG / DECIMAL / BOOLEAN / DATETIME） |
| `elapsedMs` | 引擎执行耗时（不含引用基础特征取数耗时，后者单列在 `elapsedMs` 总耗时） |
| `warnings` | 软警告：列别名非 `result`、查询返回多行只取第一行、引用无测试值按 NULL 等 |

### 2.2 `@@` / `:param` → 语言字面量替换（ExprLiterals）

所有语言共用同一替换器，按目标语言的**字面量写法**生成（键同时支持 `param.x` 与裸名 `x`）：

| 值形态 | SQL | Java | Python |
|---|---|---|---|
| null / 空串 | `NULL` | `null` | `None` |
| 布尔 | `TRUE` / `FALSE` | `true` / `false` | `True` / `False` |
| 数字 | 原样 | 原样 | 原样 |
| 日期串（`yyyy-MM-dd[ HH:mm[:ss]]`） | `DATE '...'` / `TIMESTAMP '...'` | 字符串 `"..."` | 字符串 `'...'` |
| 其他字符串 | `'...'`（`''` 转义） | `"..."`（`\` `"` `\n` 转义） | `'...'`（`\` `'` `\n` 转义） |

- **前导零数字串**（如 `007`）按字符串处理，避免被当作八进制或丢失前导零。
- 替换是**文本级**的：引用对应的实测值来自第 ① 步真实取数，未解析到的引用替换为 NULL 并提示。

## 3. SQL 引擎（Calcite 内存执行）

**执行模型**：全部登记入参物化成**单行内存表 `t_input`**（列名 = 入参名，列类型跟随入参类型，值 = 本次测试值），
用户写 `SELECT ... AS result FROM t_input`；没有任何入参时保留一行占位列（`t_input_dummy`），`FROM t_input` 依然可用。

| 实现点 | 说明 |
|---|---|
| 平台类型 → Calcite | INTEGER / BIGINT / DECIMAL(20,6) / BOOLEAN / TIMESTAMP / VARCHAR(65536) |
| 输出契约 | 必须返回**单列**（多列直接报错），列别名建议 `AS result`（不强制，仅告警） |
| `AS result` 改写 | Calcite 中 `RESULT` 是保留字，执行前把 `AS result` 改写为 `AS "result"`（列名不变），用户按直觉写即可 |
| 语法预检 | `SqlParser.parseQuery()` 先行解析，语法错误给出可读位置信息，不进入执行 |
| 大小写与函数库 | `caseSensitive=false` + 不改变大小写存档；启用 **mysql 函数库**（`CONCAT` / `IFNULL` / `DATE_FORMAT` 等） |
| 中文常量 | 启动静态块预设 `calcite.default.charset=UTF-8`（Calcite 默认 ISO-8859-1，中文常量会报 Failed to encode），必须在 Calcite 类加载前设置 |
| 预热 | `@PostConstruct` 后台线程先跑一次简单查询：Calcite 首次准备/执行要生成加载大量类（约半秒），避免用户首次试跑撞超时 |
| 多行结果 | 只取第一行并告警；0 行 → NULL + 告警 |
| 超时 | 整个 `prepare + execute` 包在 ExecGuard 线程池里，超时中断并按超时错误返回 |
| 报错定位 | Calcite 顶层 SQLException 只说「Error while preparing statement」，沿 cause 链取最贴近根因的一行（如未知列），并附 t_input 可用列名提示 |

> **与设计稿的差异**：§4.3 设计的「LIST 视图物化成 `t_view_*` 表 + JOIN/聚合」本期**未实现**——
> 当前所有入参都是单值（单行 t_input），聚合类多行场景留待视图多行取数落地后再接。

## 4. Java 引擎（Janino 编译执行）

**两种脚本写法**（按内容自动识别，脚本里出现 `apply(` 视为完整方法写法）：

```java
// 写法一：只写方法体（推荐，帮助卡示例）——平台自动包进 apply 方法
int cnt = ((Number) in.get("user_overdue_cnt")).intValue();
if (cnt >= 3) return "H";
return "L";
```

```java
// 写法二：写完整方法
public static Object apply(java.util.Map<String, Object> in, Ctx ctx) {
    ...
}
```

| 实现点 | 说明 |
|---|---|
| 包装源码 | `去 PRELUDE（import java.util.* / java.math.* / java.time.* / Ctx；class DrvTrialFeature）+ 包装/拼接 + }` |
| 方法体缩进 | 用户代码统一缩进 8 空格再包进方法；编译错误行号按 `BODY_LINE_OFFSET=7` 换算回**用户脚本行号**（"脚本第 N 行附近"） |
| 编译隔离 | 每次执行 new 一个 `SimpleCompiler`（parent ClassLoader = 应用类加载器），类名固定 `DrvTrialFeature`——天然一编译一隔离，无热替换残留 |
| 危险调用拦截 | 编译前黑名单正则拒绝：`Runtime.` / `ProcessBuilder` / `System.exit|getenv|getProperties|setProperty|load` / `Class.forName` / `getClassLoader` / `java.net|io|nio.` / `Files.` / `new File` / `Socket` / `Unsafe` / `java.lang.reflect` / `ScriptEngine` / `javax.naming` / `Thread`，命中即报「只做数据加工」拒绝执行 |
| 执行上下文 | `Map<String,Object> in`（全部入参，值已经 coerce）+ `Ctx`（`get/has/param/inputs/params/elapsedMs`）；`ctx.param("uid")` 读 `:param.uid` |
| 超时 | `apply` 调用包在 ExecGuard 里按 timeoutMs 中断（`Future.cancel(true)` 尽力中断）；**编译阶段不在超时内**（Janino 编译 10ms 级，编译错误直接返回） |
| 方法签名校验 | 包装/完整写法最终都必须提供 `public static Object apply(Map, Ctx)`，否则返回签名提示 |

> **与设计稿的差异**：§4.4 设计的是「Javaparser AST 白名单扫描」；本期用**正则黑名单**实现，
> 属于 P0 取舍——覆盖已知逃逸面（进程/文件/网络/反射/线程），后续可平滑升级为 AST 级校验。

## 5. Python 引擎（独立 python-runner 服务）

**形态与设计稿一致**：后端不做进程内嵌入，而是 HTTP 调 `python-runner/runner.py`（FastAPI + 独立工作进程池）。

### 5.1 后端侧（PythonRunnerClient）

| 实现点 | 说明 |
|---|---|
| 服务地址 | `risk.python-runner.url`，默认 `http://127.0.0.1:8090` |
| 危险调用拦截 | 正则黑名单：`import/from os|sys|subprocess|socket|shutil|ctypes|pathlib|importlib|multiprocessing|signal|pickle|marshal|builtins`、`__import__`、`eval/exec/compile/open/globals/locals`、`getattr/setattr/delattr`、`__builtins__/__class__/__subclasses__/__globals__/__mro__` |
| **HTTP/1.1 强制**（坑） | JDK HttpClient 默认 HTTP/2，对 uvicorn 会先发 h2c 升级请求而被拒（表现为 **422 body missing**）；必须 `.version(HTTP_1_1)`。已在 `PythonRunnerClient` 构造函数固定 |
| 请求 / 响应 | `POST /run {script, inputs, timeoutMs}` → `{success, value, valueType, elapsedMs, stdout, error}`；HTTP 请求超时 = timeoutMs + 5000ms；脚本 stdout 非空时转成一条 warning 回显 |
| 服务未启动 | `ConnectException` → 明确提示「Python 执行服务未启动（…:8090）。请先启动：python python-runner/runner.py」 |
| 输出类型 | runner 侧 `_py_type` 推断 → 平台类型；value 经 ObjectMapper 转换回 Java 值 |

### 5.2 Runner 侧（runner.py）

| 实现点 | 说明 |
|---|---|
| 进程池 | 默认 **2 个常驻工作子进程**（`--workers`），JSON Lines 走 stdin/stdout 协议；绕过 GIL，进程级隔离 |
| 脚本约定 | `def apply(inp: dict, ctx) -> object`（2 参）或 `def apply(inp)`（1 参，按签名自动适配）；未定义 apply 给出清晰报错 |
| 预置全局 | `json / math / re / datetime / decimal` + 可选 `pd/pandas / np/numpy`（依赖缺失时服务照常启动，/health 标注版本） |
| 脚本缓存 | 按源码 sha1 缓存编译产物（每工作进程 64 条上限），首次编译后续直接 exec |
| 超时熔断 | 等待预算 = timeoutMs + 2s；超时直接 **kill 工作进程**并自动重启（不留脏状态），返回「执行超时，工作进程已被终止」；写管道失败自动换新进程**重试一次** |
| 错误可读性 | SyntaxError 报「第 N 行」；运行异常返回 `traceback.format_exc(limit=6)` 截断 |
| 版本可见 | `GET /health` 返回 pandas / numpy 版本，便于对齐在线离线依赖 |
| 监听范围 | 仅 127.0.0.1、无鉴权（本机调试服务）；**需手动启动**，未随后端进程托管 |

> **启动方式**：`python python-runner/runner.py --port 8090`（依赖 `pip install -r python-runner/requirements.txt`）。

## 6. OP 引擎（算子树 JSON 求值）

**执行模型**：前端 OP 可视化编辑器把「规则表」生成为 CASE 算子树 JSON（`{type:'CASE', branches:[{when,then}], else}`），
引擎对 JSON 求值；高级模式可手写全部算子。

| 算子 | 结构 | 说明 |
|---|---|---|
| `CONST` | `{type,value}` | 任意 JSON 值（含数组/对象） |
| `REF` | `{type,ref}` | `@@编码` 查 refValues；`:param.x` 查测试值；裸名先查 refs 再查 values；缺值告警并按 NULL |
| `CMP` | `{type,op,left,right?}` | `EQ/NE/GT/GTE/LT/LTE/IN/NOT_IN/IS_NULL/NOT_NULL`（ISNULL/ISNOTNULL 别名同义）；数值比较走 BigDecimal，空值比较告警 |
| `LOGIC` | `{type,op,args[]}` | `AND/OR/NOT`，短路求值 |
| `ARITH` | `{type,op,args[]}` | `ADD/SUB/MUL/DIV/MOD`；DIV 保留 6 位 HALF_UP，除零报错 |
| `CASE` | `{type,branches[],else?}` | 顺序命中第一个 `when` 为真的分支；无命中取 else（缺省 NULL） |
| `DICT` | `{type,source,mapping,default?}` | 查表映射（先精确后忽略大小写），未命中取 default |
| `FILLNULL` | `{type,source,fallback}` | null 或空白字符串时取 fallback（COALESCE 语义） |

| 实现点 | 说明 |
|---|---|
| 结构校验 | 保存时 `DerivedExecutor.validateExpression` 只对 OP 做结构预校验；执行前再 `validateNode` 一遍（未知 type、缺 ref/op、空 branches 等均给人话报错） |
| 规模上限 | 嵌套 ≤ 50 层、节点数 ≤ 500（超限直接报错）——求值是内存内纯计算，天然有界，因此**未再套 ExecGuard 超时** |
| 值归一 | BigDecimal 去尾零，整数还原为 Long/Integer，避免 `2.0` 之类的展示噪音 |
| 表达式回显 | `render()` 把算子树渲染成 **SQL 风格文本**（`CASE WHEN (x > 3) THEN 'H' ... END`，@@ 已替换为实测值），即 `executedExpr` |

## 7. 前端联动（FeatureCreate.jsx）

| 交互 | 说明 |
|---|---|
| 加工方式切换 | `SQL / Java / Python / OP` 四个 tab；**切换语言不丢草稿**（每个语言各自保留已写表达式，互不覆盖） |
| 帮助卡 | 每个语言悬浮帮助卡展示对应示例（含 `@@` 写法与 `apply` 签名样例） |
| OP 可视化 | 规则表（每行：特征/入参 比较 值 → 输出）+ 兜底值；随配置自动生成算子树 JSON 预览；「高级 JSON」可双向切换（简单条件映射可转回可视化） |
| 验证结果 | 「表达式真实执行」标识 + 引擎标签（`SQL · Calcite 内存执行` / `Java · Janino 编译执行` / `Python · 独立 Runner` / `OP · 算子求值`）+ 衍生输出值（超长值折叠、可展开）+ **引擎耗时**（与引用取数总耗时分开显示）+ 表达式回显（@@ 已替换实测值） |

## 8. 验证结果（浏览器四引擎联调）

每个引擎用一条冒烟衍生特征在配置台完整走「新建 → 登记入参 → 解析引用并执行」，全部 ✓ 验证通过：

| 引擎 | 表达式（冒烟） | 展示引擎 | 输出值 / 类型 | 引擎耗时 |
|---|---|---|---|---|
| SQL | `SELECT 'sql_ok' AS result FROM t_input` | SQL · Calcite 内存执行 | `sql_ok` / STRING | 29 ms |
| Java | `return "java_ok";` | Java · Janino 编译执行 | `java_ok` / STRING | 7 ms |
| Python | `return len(s) if s else -1`（`@@url_json_dashboard`） | Python · 独立 Runner | `3392` / INTEGER | 10 ms |
| OP | 规则：`@@url_json_dashboard 非空 → HAS_DATA`，兜底 `EMPTY` | OP · 算子求值 | `HAS_DATA` / STRING | ms 级 |

- SQL 结果回显中可见 `AS "result"` 改写生效；Python 结果回显中 `@@url_json_dashboard` 已替换为 3392 字符的实测值（前端折叠展示）。
- 4 条冒烟特征在验证完成后已逻辑下线，不留在特征列表。
- 另：四引擎此前已通过 API 冒烟（直接调 trial 接口），本次为**配置台浏览器全链路**复验。

## 9. 已知边界 / 后续

- **黑名单是正则级而非 AST 级**（Java / Python）：覆盖已知逃逸面，但不如语法树白名单严密；后续升级路径：Java 换 Javaparser AST 扫描、Python 换 `ast` 模块白名单遍历。
- **SQL 多行输入未支持**：`t_view_*` 物化 / JOIN / 聚合算子未实现（依赖视图多行取数），当前所有入参单值。
- **Java 编译阶段不在超时守卫内**：仅 `apply` 调用受 ExecGuard 保护；异常缓慢的编译（理论上无）会占用请求线程。
- **OP 未接 ExecGuard**：由深度/节点数上限兜底，属刻意选择。
- **超时中断是「尽力而为」**：`Future.cancel(true)` 靠线程中断，恶意死循环中的代码不一定立刻停下（Janino 侧）；Python 侧是进程 kill，更彻底。
- **python-runner 需手动启动、单机直连**：未做服务发现/鉴权/多实例路由；后端每次 trial 直接调本机 8090。
- **运行时链路未接入**：四引擎目前服务于**验证（trial）**；决策运行时的特征计算层（05-engine.md 的执行计划/缓存）尚未接这些引擎，留作下一步。
