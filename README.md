# Bleep Scripts 收藏

一组可直接复用的 **bleep BleepScript** 脚本。每个脚本按功能独立成目录,内含
源码 + 设计文档,可复制到任意 bleep 项目的 `scripts/src/scala/scripts/` 下使用。

## 脚本一览

| 脚本 | 功能 | bleep 版本 | 目录 |
| --- | --- | --- | --- |
| `mcp-setup` | 把 [ScalaSemantic](https://github.com/MercurieVV/ScalaSemantic) MCP 服务器接入项目:装 launcher、prefetch jar、合并写 `.mcp.json` | 0.0.14 | [mcp-setup/](mcp-setup/) |
| `link-vite` | Scala.js + Vite 统一开发流水线:动态生成 vite 配置 + 双路监听(`bleep link --watch` + `vite`),抛弃静态 `vite.config.js` | 1.0.0-M10 | [link-vite/](link-vite/) |
| `custom-dist` | 封装 bleep 内置 `Dist` 命令打包后端服务,把产物搬到约定目录 `out/` | 0.0.14 | [custom-dist/](custom-dist/) |

## 共性模式

两者都是同一种 bleep script 姿势:

1. **独立 JVM 子项目**:`bleep.yaml` 里一个 `scripts` 项目,`platform: jvm`,依赖 `bleep-core`(暴露 `BleepScript / Started / Commands`)。不继承业务项目的(可能是 Scala.js 的)模板。
2. **`bleep.yaml` 注册**:`scripts:` 段下 `{ main: <全限定对象名>, project: scripts }`,bleep 把脚本名提升为顶层命令(`bleep <script-name>`)。
3. **`object X extends BleepScript(name)`**:实现 `run(started, commands, args)`。

> ⚠️ **版本差异**:bleep 0.0.14 的 script 用 `java <mainClass>` 直接调用,需要 **static main**,所以必须用 `object`(Scala 为 object 生成 static forwarder);1.0.0-M10 起放宽,`class`/`object` 均可。两个脚本因目标项目版本不同而写法略有差异——移植时按你的 bleep 版本调整。

## 接入任意 bleep 项目(以 `mcp-setup` 为例)

1. 把脚本源码复制到 `<项目>/scripts/src/scala/scripts/McpSetup.scala`。
2. 在 `bleep.yaml` 加:

   ```yaml
   projects:
     scripts:
       dependencies:
         - build.bleep::bleep-core:<你的bleep版本>
       extends: <某个JVM模板>      # 不能继承 Scala.js 模板
   scripts:
     mcp-setup:
       main: scripts.McpSetup
       project: scripts
   ```

3. `bleep mcp-setup` 即可运行。

## 目录结构

```
bleep_scripts/
├── README.md                              # 本文件
├── mcp-setup/
│   ├── McpSetup.scala                     # 脚本源码
│   └── scalasemantic-integration-design.md # 设计文档
├── link-vite/
│   ├── LinkVite.scala                     # 脚本源码
│   └── link-vite-design.md                # 设计文档
└── custom-dist/
    ├── CustomDist.scala                   # 脚本源码
    └── custom-dist-design.md              # 设计文档
```

> 设计文档中的相对链接(如 `../scripts/...`)指向原始项目的源码位置,复制到本目录后会失效;以本目录下同名的 `.scala` 文件为准。
