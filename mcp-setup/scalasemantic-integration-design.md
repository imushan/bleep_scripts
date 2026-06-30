# ScalaSemantic × Bleep 接入设计总结

## 1. 目标

让一个 **bleep 构建**的 Scala 3 项目能用上 [ScalaSemantic](https://github.com/MercurieVV/ScalaSemantic) MCP 服务器,使 AI 编程助手(Claude Code)能做**精确的语义查询**(`find_usages` / `class_hierarchy` / `call_path` 等),而非依赖 grep。

衡量标准:bleep 项目里一条 `bleep mcp-setup` 命令完成服务器接入,且不破坏已有配置。

## 2. 核心认知(决定一切设计的两个事实)

| 事实 | 含义 |
| --- | --- |
| ScalaSemantic 服务器**构建工具无关** | 它只读磁盘上的 `.semanticdb`,谁编译的都行。sbt 插件不是特殊通道,只是"便利包" |
| bleep 0.0.14 **没有插件机制** | 只有 BleepScript(编译后跑的程序),没有 autoplugin/variant。所以"一行 enablePlugins"在 bleep 不存在,必须用 script 编排 |

这两点直接推导出:**接入 = (让 bleep 吐 .semanticdb) + (用 script 复刻 sbt 插件的编排逻辑)**。

## 3. 架构:三层职责切分

```
┌─ bleep 构建 ────────────────────────────────────┐
│  bleep.yaml: -Xsemanticdb -sourceroot           │  ← 编译时产生数据
│  bleep compile → META-INF/semanticdb/*.semanticdb│     (索引读)
│                → *.bloop/<proj>.json + classesDir│     (PC 读)
└───────────────────────┬─────────────────────────┘
                        │ 磁盘文件(唯一耦合点)
                        ▼
┌─ McpSetup script (bleep mcp-setup) ────────────┐
│  ① 装 launcher  ② prefetch jar                  │  ← 配置编排(一次性)
│  ③ 写 compile classpath 文件(启用 PC 实时后端)  │
│  ④ 合并 .mcp.json(argv 含 classpath 作为 arg2)  │
└───────────────────────┬─────────────────────────┘
                        │ 写出 .mcp.json 指向 launcher
                        ▼
┌─ 运行时(每次 AI 会话)─────────────────────────┐
│  Claude Code → fork launcher → cs launch 服务器  │  ← 读取数据
│  索引 = SemanticDB(.semanticdb)  ← 永远在,全项目 │
│  PC   = 编译器实例 + classpath(.tasty/.class)   │
│        ↑ 可选层,按 source 现场重生单文件 → 18 工具│
└─────────────────────────────────────────────────┘
```

**关键边界**:下载/缓存/断点续传/版本解析那一整套复杂逻辑**全在官方 launcher 里**,script 一行不碰。script 只做编排——这正是路线 A(复用 launcher)相对路线 B(重写下载)的核心收益。

## 4. 关键设计决策(含理由)

| # | 决策 | 选择 | 理由 |
| --- | --- | --- | --- |
| 1 | 下载逻辑归属 | **复用官方 launcher** | 重写版本解析/双通道/续传/冷启动性价比为零 |
| 2 | 服务器版本 | **默认 latest** | launcher 自带后台更新机制 |
| 3 | launcher 装哪 | **`~/.local/bin`** | 用户级、多项目共享、不被 `bleep clean` 清 |
| 4 | PC 实时后端 | **已做**(读 bloop json 抽 classpath) | 解锁未编译/编辑中 buffer 的 `type_at_position`/`method_signature` 等;失败自动降级 index-only |
| 5 | 客户端范围 | **只 claude** | 当前用 Claude Code;多客户端以后再说 |
| 6 | 规则文件 | **不做** | 验证链路后再加 CLAUDE.md 等 |
| 7 | SemanticDB 触发 | **一直开**(非按需) | bleep 0.0.14 无 variant,无法按需;开销极小,sbt 默认也一直开 |
| 8 | `.semanticdb` 输出位置 | **`META-INF/semanticdb/`** | 加 `-sourceroot` 后自动干净,不污染源码 |

## 5. 实现组成

### ① `bleep.yaml` 改动

- `template-common` 的 `scala.options` 加 `-Xsemanticdb -sourceroot:<根>`(→ 干净输出 + 服务器路径可解析)
- 新增 `scripts` 子项目,依赖 `build.bleep::bleep-core:0.0.14`
- `scripts:` 段注册 `mcp-setup: { main: scripts.McpSetup, project: scripts }`

### ② `scripts/src/scala/scripts/McpSetup.scala`

`object McpSetup extends BleepScript("mcp-setup")`,四个步骤:

1. **装 launcher** —— 从 GitHub 下载 `.sh` 到 `~/.local/bin`,chmod +x
2. **`launcher --prefetch`** —— 预热 jar(下载全交给 launcher)
3. **写 compile classpath**(新增) —— 从 `.bleep/builds/normal/.bloop/<proj>.json` 解析 `classpath`(依赖 jar)+ `classesDir`(本项目编译产物),用 `:` 拼接写到 `.bleep/scala-semantic-classpath.txt`;bloop 配置或 classesDir 缺失时自动触发一次 `bleep compile <proj>`;拿不到则降级(不传 classpath,服务器仍可 index-only 运行)
4. **circe 合并写 `.mcp.json`** —— 只替换/插入 `scala-semantic`,保留所有其它条目(幂等);argv = `[root, classpathFile, --log, --log-output]`(classpath 占 arg2);并注入 `env.JAVA_HOME`(自愈损坏的全局环境变量)

> 默认对主代码项目 `app` 启用 PC;`bleep mcp-setup scripts` 可切目标项目。

## 6. 踩坑与修复记录(最有价值的部分)

| 坑 | 现象 | 根因 | 修复 |
| --- | --- | --- | --- |
| BleepScript 实例 main | `Main method is not static` | 0.0.14 的 `rawRun` 用 `java <mainClass>` 直接调,需 static main | `class` → `object`(Scala 为 object 生成 static forwarder) |
| 全局 JAVA_HOME 损坏 | `cs launch` 启动失败:`Cannot run program .../bin/java` | `JAVA_HOME` 指向 coursier 的 `%252B` 双重编码缓存路径,从未解压 | script 用 `readlink -f $(which java)` 反推真实 JAVA_HOME,写入 `.mcp.json` 的 `env` |
| `flatMap(_.getParentFile)` | 类型错误 | `getParentFile` 返回 `File` 非 `Option` | `flatMap(bin => Option(bin.getParentFile))` |

> **教训**:这两个坑都不是"设计错",而是 bleep 老版本机制差异 + 宿主环境污染。设计上 script 主动检测/推导(JAVA_HOME)而非依赖环境,正是为吸收这类宿主差异。

## 7. 当前能力边界

**已实现**

- ✅ 一键 `bleep mcp-setup` 完成接入
- ✅ SemanticDB 干净输出到 META-INF
- ✅ `.mcp.json` 合并(保留已有服务器)
- ✅ JAVA_HOME 自愈
- ✅ 所有 index-only 工具可用
- ✅ PC 实时后端(读 bloop json 抽 classpath,失败降级 index-only;已 A/B 实测通过)

**未实现(按决策有意延后)**

- ⬜ 规则文件 / CLAUDE.md
- ⬜ codex/gemini 等多客户端

## 8. PC 实时后端原理与验证

### 8.1 classpath 里到底是什么 —— 不是源码,是编译产物的类型元数据

PC 模式传的 jar / classesDir,看的不是 `.scala` 源码,而是里面已编译的类型元数据:

| 文件 | 是什么 | 谁来读 |
| --- | --- | --- |
| `.tasty` | **TASTy**(Typed AST),Scala 3 编译器吐出的完整带类型程序树:类型/签名/隐式/泛型/继承全在里面 | **PC 优先读它** |
| `.class` | JVM 字节码;Scala 2/Java 类型存在 `ScalaSignature`/`ScalaLongSignature` 注解或方法签名里 | 没有 tasty 时 PC **降级读它** |
| `.semanticdb` | protobuf:符号表 + 符号在源码里的出现位置 | **SemanticIndex 读它**(PC 不读) |

实测 `scala3-library_3-3.3.3.jar` = 562 个 `.class` + 98 个 `.tasty`;本项目 `app/classes` = 18 `.class` + 7 `.tasty` + 2 `.semanticdb`。**同一份编译产物同时含两类东西,PC 和索引各取所需**。
> 旁注:jar 里 `.tasty` 少于 `.class` 是正常的 —— 多数 class 是编译器合成的桥接方法/内部类,类型信息附在主类的 tasty 里,PC 按符号定位 tasty 而非逐 class 找。

### 8.2 PC 怎么用 classpath 找到信息

`PresentationCompilerBackend` 启动一个**真正的 Scala 3 编译器实例**并把 classpath 交给它([src:34-36](https://github.com/MercurieVV/ScalaSemantic/blob/master/pc/src/main/scala/com/github/mercurievv/scalasemantic/pc/PresentationCompilerBackend.scala))。传入 buffer 的 `source` 时:

1. 编译器把这段 source **部分编译**成 typed AST(容错:有语法错也能出部分结果);
2. 引用到的符号(如 `List`、`Option`、项目内的 `GreetTranslator`),按 classpath 查找:Scala 库符号去 jar 里读 `*.tasty`;本项目符号去 classesDir 读 `*.tasty`;纯 Java 依赖降级读 `.class` 签名;
3. 解析后,PC 把**这一个 buffer** 的结果序列化成 `.semanticdb` 格式的 `TextDocument`,splice 进磁盘索引(`SemanticIndex.withDocument`)。

jar 在这里扮演的角色与 `scalac`/`javac -cp` 编译时完全一样 —— **已编译好的依赖类型库**。

### 8.3 index-only 与 PC:不是包含关系,是「基座 + 可选叠加层」

启动时(`Mcp.serve`):索引**永远建**,PC 是 `Option` —— classpath 解析成功才有,失败就是 `None`,两者塞进同一个 `Analyzer`:

```
index-only 模式 = 磁盘索引
classpath  模式 = 磁盘索引 + (按需) PC 重生的单文件文档叠加
```

无 classpath 时 `source` 参数被**忽略**(服务器退 index-only);有 classpath 时 `source` 才生效。两者输出同一种 wire 格式(`TextDocument`),所以「叠加」是干净拼接,不重写查询逻辑。

> **关键:开了 classpath ≠ 全用 PC。** 即使开了,`find_usages`/`class_hierarchy`/`structure` 这类全项目工具**仍只读磁盘索引**,PC 只帮 per-file/位置/签名查询,且要主动传 `source`。

每个工具按三类分发([McpTools.scala:15-24](https://github.com/MercurieVV/ScalaSemantic/blob/master/mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala)):

| 模式 | 触发 | 用什么 | 代表工具 |
| --- | --- | --- | --- |
| **index-only** | 无 source,全项目扫描 | 只读磁盘索引 | `find_symbol`/`find_usages`/`class_hierarchy`/`find_overloads`/`structure` |
| **overlay** | source+uri,需全项目索引但单文件更新鲜 | PC 重生该文件叠加到索引 | `method_signature` |
| **PC-only** | source,单文件/位置局部查询 | 只查 PC 重生的那份,不读磁盘 | `type_at_position`/带 source 的 `method_signature` |

### 8.4 日常怎么用:一条习惯

> **「全项目查询前 compile,单文件查询时按需传 source」**

- 全项目级(`find_usages`/继承/依赖图)→ 不传 source,**先 `bleep compile`**(PC 救不了这类,索引永远只有上次编译的快照);
- 单文件级(签名/类型/位置/抽取方法)→ 文件编译过且没改就不传;改了没 compile 或新建文件,就传 `uri`+`source`,不用等编译,还能容忍语法错。

### 8.5 怎么验证 PC 正常(3 种,由轻到重)

1. **看启动日志**:`--log` 已开,日志在 `<root>/scala-semantic.log`。开 classpath → `PC backend enabled (N classpath entries)`;没开 → 末尾带 `(index-only; pass a classpath to enable live buffers)`。
2. **MCP 工具自测**:对未 compile 的文件传 `source` 问 `type_at_position`;返回类型 = PC 在工作,`found:false` = 没生效。
3. **A/B 对照探针**(决定性):用一段磁盘上不存在的代码对比「开/关 classpath」两次启动的返回。实测结果:

| 模式 | 返回 | 含义 |
| --- | --- | --- |
| 开 classpath | `{"symbol":"_empty_/PcProbeUnique.schnozzle().","type":"List[Int]"}` | ✅ 解析了只存在于 source 的发明符号并推断类型 |
| 无 classpath | `{"found":false}` | ✅ 该符号不在磁盘索引,反证 source 被忽略 |

### 8.6 验证中暴露的关键坑:手动跑 launcher 必须带 JAVA_HOME

`cs launch` 会挑一个损坏的 coursier 缓存 JDK(`%252B` 双重编码、从未解压的目录)而崩溃:`Cannot run program ...%252B.../bin/java: No such file`。经 `.mcp.json` 启动时 `env.JAVA_HOME` 会注入,正常;**手动用 `scalasemantic-mcp.sh` 排障时必须 `export JAVA_HOME` 指向真实 JDK**,否则复现此错。

## 9. 后续演进点

1. **多客户端**:`writeMcpJson` 按 `--client` 参数写不同格式。
2. **根治 JAVA_HOME**:找到 profile 里的残留 export 清掉(目前靠 env 局部覆盖)。
3. **sourceroot 可移植化**:当前写死绝对路径;若 bleep 升级支持变量或 variant,改成相对。
4. **classpath 多项目**:当前只对 `app`;若需对 `scripts` 等多项目同时启用,考虑为每个项目写独立 classpath 文件 + 多个 server 条目。
