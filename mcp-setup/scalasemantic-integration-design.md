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
│  bleep compile → META-INF/semanticdb/*.semanticdb│
└───────────────────────┬─────────────────────────┘
                        │ 磁盘文件(唯一耦合点)
                        ▼
┌─ McpSetup script (bleep mcp-setup) ────────────┐
│  ① 装 launcher  ② prefetch jar  ③ 合并 .mcp.json│  ← 配置编排(一次性)
└───────────────────────┬─────────────────────────┘
                        │ 写出 .mcp.json 指向 launcher
                        ▼
┌─ 运行时(每次 AI 会话)─────────────────────────┐
│  Claude Code → fork launcher → cs launch 服务器  │  ← 读取数据
│  服务器读 .semanticdb → 18 个工具 → 返回 JSON     │
└─────────────────────────────────────────────────┘
```

**关键边界**:下载/缓存/断点续传/版本解析那一整套复杂逻辑**全在官方 launcher 里**,script 一行不碰。script 只做编排——这正是路线 A(复用 launcher)相对路线 B(重写下载)的核心收益。

## 4. 关键设计决策(含理由)

| # | 决策 | 选择 | 理由 |
| --- | --- | --- | --- |
| 1 | 下载逻辑归属 | **复用官方 launcher** | 重写版本解析/双通道/续传/冷启动性价比为零 |
| 2 | 服务器版本 | **默认 latest** | launcher 自带后台更新机制 |
| 3 | launcher 装哪 | **`~/.local/bin`** | 用户级、多项目共享、不被 `bleep clean` 清 |
| 4 | PC 实时后端 | **先不做**(index-only) | 先跑通主链路;磁盘 semanticdb 已覆盖多数查询 |
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

`object McpSetup extends BleepScript("mcp-setup")`,三个步骤:

1. **装 launcher** —— 从 GitHub 下载 `.sh` 到 `~/.local/bin`,chmod +x
2. **`launcher --prefetch`** —— 预热 jar(下载全交给 launcher)
3. **circe 合并写 `.mcp.json`** —— 只替换/插入 `scala-semantic`,保留所有其它条目(幂等);并注入 `env.JAVA_HOME`(自愈损坏的全局环境变量)

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

**未实现(按决策有意延后)**

- ⬜ PC 实时后端(需抽 app 的 classpath)
- ⬜ 规则文件 / CLAUDE.md
- ⬜ codex/gemini 等多客户端

## 8. 后续演进点

1. **PC 后端**:script 增加从 `started.build` 抽 `app` fullClasspath 写文件,塞进 argv——解锁 `type_at_position` 查未编译脏 buffer。
2. **多客户端**:`writeMcpJson` 按 `--client` 参数写不同格式。
3. **根治 JAVA_HOME**:找到 profile 里的残留 export 清掉(目前靠 env 局部覆盖)。
4. **sourceroot 可移植化**:当前写死绝对路径;若 bleep 升级支持变量或 variant,改成相对。
