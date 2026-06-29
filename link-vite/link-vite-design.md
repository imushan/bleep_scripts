# LinkVite 设计文档

> Bleep 脚本驱动的 Scala.js + Vite 统一开发流水线

| 项 | 值 |
|----|----|
| 文档版本 | v1.0 |
| 适用项目 | baidu（Scala 3 + Scala.js + Laminar + cats-effect） |
| 构建工具 | bleep 1.0.0-M10 |
| 调度器 | `scripts.LinkVite`（纯 Scala Bleep 脚本） |
| 打包/开发服务器 | Vite 8.x |
| 核心源文件 | [scripts/src/scala/scripts/LinkVite.scala](../scripts/src/scala/scripts/LinkVite.scala) |

---

## 1. 背景与目标

### 1.1 现状问题

Scala.js 项目的传统前端工程化通常依赖两条路径之一：

1. **静态 `vite.config.js` + `@scala-js/vite-plugin-scalajs` 插件**：插件与 sbt 强绑定，且硬编码路径，跨项目复用性差。
2. **手动双终端**：一个终端跑 `bleep link --watch`，另一个跑 `npx vite`，改代码后需人工确认两者状态。

两者都把「构建产物路径发现」「配置生成」「进程编排」这三件事割裂在不同的地方（配置文件、插件、人的操作）。

### 1.2 设计目标

用一个**纯 Scala 的 Bleep 脚本**作为全局调度器，统一承担：

- **抛弃静态 `vite.config.js`**：不再在仓库里维护任何 Vite 配置文件。
- **不依赖 sbt 绑定的 `@scala-js/vite-plugin-scalajs`**。
- **自动发现产物路径**：直接读取 bleep 内部项目模型，精准拿到 Scala.js 链接产物的物理路径。
- **动态生成临时配置**：在系统临时目录生成 Vite 配置，用 `resolve.alias` 注入产物路径。
- **双路监听（Dual-Watch）一键启动**：后台 bleep 链接监听 + 前台 Vite 开发服务器，单命令、单终端。

### 1.3 非目标

- 不替代 bleep 本身的编译/链接能力，只做编排。
- 不引入新的 Scala.js 打包器（继续用 Vite 原生能力）。
- 不处理 CSS 预处理器 / 框架集成（保持前端入口纯净，业务样式仍由 Scala 端 `Styles.scala` 注入）。

---

## 2. 总体架构

```
                    bleep link-vite [baidu]
                              │
        ┌─────────────────────┴──────────────────────────┐
        │  LinkVite.run(started, commands, args)        │
        │                                                  │
        │  ① started.projectPaths(crossName).targetDir    │
        │     → 定位 link-output/debug/js/main.js          │
        │  ② writeTempConfig(): 临时目录生成 vite 配置     │
        │     resolve.alias { 'scalajs:main.js' → main.js }│
        └────────┬───────────────────────────┬────────────┘
                 ▼                           ▼
      ┌───────────────────┐         ┌───────────────────┐
      │  线程 A（后台）    │         │  线程 B（前台）    │
      │  bleep link baidu  │         │  npx vite         │
      │       --watch      │         │    --config <临时> │
      └────────┬──────────┘         └────────┬──────────┘
               │                              │
   改 .scala → 增量重链接               文件监控(Chokidar)
   刷新磁盘 main.js                  捕获 main.js 变动
               │                              │
               └──────────► HMR ◄─────────────┘
                          整页刷新
```

### 2.1 组件职责

| 组件 | 文件 | 职责 |
|------|------|------|
| **调度器** | `scripts/src/scala/scripts/LinkVite.scala` | 读取项目模型、生成临时配置、编排双进程、资源清理 |
| **脚本注册** | `bleep.yaml`（`scripts:` + `projects.scripts`） | 声明脚本入口与 JVM 编译项目 |
| **前端入口** | `src/main.js` | 纯净导入 `scalajs:main.js`（虚拟路径） |
| **页面入口** | `index.html` | `<script type="module" src="/src/main.js">` |

---

## 3. 工作流程

### 3.1 启动时序

```
1. 用户执行 `bleep link-vite baidu`
2. bleep 编译 `scripts` 项目 → 反射加载 `LinkVite`
3. 调用 run(started, commands, args=["baidu"])
4. 解析参数：buildMode = false, projectName = "baidu"
5. targetDir = started.projectPaths(CrossProjectName(ProjectName("baidu"), None)).targetDir
6. mainJs    = targetDir / "link-output" / "debug" / "js" / "main.js"
7. tempDir   = Files.createTempDirectory("link-vite-")
8. tempCfg   = writeTempConfig(tempDir, mainJs, projectRoot)
        └─ 写入 CJS 配置：resolve.alias + server.fs.allow
9. 【dev】启动后台 bleep link --watch
10. waitForFile(mainJs, 90s)        # 等首次链接产物出现
11. 启动前台 npx vite --config <tempCfg>
12. 注册 shutdown hook
13. viteProc.exitValue()            # 阻塞主线程，直到 Ctrl+C
```

### 3.2 运行期（改 Scala 代码）

```
开发者保存 Main.scala
   → bleep link --watch 的 zinc 增量编译
   → 日志：Changed: Set(baidu)
   → 重新链接，覆盖磁盘 main.js
   → Vite 内置文件监控(Chokidar) 捕获 main.js mtime 变化
   → 日志：(client) page reload .../main.js
   → 浏览器整页刷新，加载新 bundle
```

> 全程无需开发者干预——保存即生效，单终端闭环。

### 3.3 退出时序

```
用户 Ctrl+C (SIGINT)
   → JVM 收到信号，执行 shutdown hook
      ├─ linkProc.destroy()      # 终止后台链接
      ├─ viteProc.destroy()      # 终止 Vite
      └─ cleanup(tempCfg, tempDir)  # 删除临时配置与目录
   → 进程退出，端口 5173 释放
```

---

## 4. 组件详细设计

### 4.1 组件 A：LinkVite.scala

#### 4.1.1 类型与签名

```scala
object LinkVite extends BleepScript("LinkVite"):
  override def run(started: Started, commands: Commands, args: List[String]): Unit
```

`BleepScript` 是 bleep-core 提供的脚本基类，构造参数为脚本名；`run` 由 bleep 反射调用，注入：

- `started: Started` —— 构建上下文，含完整项目模型、路径、解析器。
- `commands: Commands` —— 命令门面（compile/run/test/script/publish*）。
- `args: List[String]` —— CLI 透传参数。

#### 4.1.2 参数解析

```
bleep link-vite [dev|build] [project]
```

- 位置参数中含 `build` → 生产构建模式；否则 dev 模式（默认）。
- 其余位置参数取第一个为项目名，缺省 `baidu`。
- `--` 前缀的参数忽略（bleep 自身标志）。

#### 4.1.3 产物路径发现

```scala
val crossName  = CrossProjectName(ProjectName(projectNameStr), None)
val targetDir  = started.projectPaths(crossName).targetDir
val mainJs     = targetDir.resolve("link-output").resolve("debug").resolve("js").resolve("main.js")
```

`targetDir` 即 `.bleep/builds/normal/.bloop/<project>`。链接产物固定在
`link-output/<debug|release>/js/main.js`（本项目默认 debug / fast-opt）。

#### 4.1.4 临时配置生成

`writeTempConfig(tempDir, mainJs, projectRoot)` 用 `Files.createTempFile(tempDir, "vite-config-", ".js")`
创建文件，写入 **CJS 纯对象**（无需 `import vite`，规避 Vite 8 ESM-only 的 `require` 问题）：

```js
module.exports = {
  resolve: { alias: { 'scalajs:main.js': "<ABSPATH>/main.js" } },
  server: {
    port: 5173,
    fs: {
      allow: [
        "<PROJECT_ROOT>",          // 放行 index.html / src
        "<LINK_OUTPUT_DIR>"         // 放行 .bleep 下的产物
      ]
    }
  }
};
```

> **关键点**：`server.fs.allow` 会**覆盖** Vite 默认白名单（默认含项目根），因此必须同时
> 显式放行项目根，否则 `index.html` / `src/` 会被 403。这是本设计踩过并修复的坑。

#### 4.1.5 进程编排

```scala
// 线程 A：后台
val linkProc = Process(List("bleep", "link", projectNameStr, "--watch", "--no-tui")).run(rawIO)

// 线程 B：前台（阻塞主线程）
val viteProc = Process(List("npx", "vite", "--config", tempCfg.toString)).run(rawIO)
viteProc.exitValue()   // 阻塞
```

`rawIO` 是自定义 `ProcessIO`，把子进程 stdout/stderr **原样转发**到当前进程，
保留 ANSI 颜色（`transfer()` 用 4KB 缓冲做字节级 copy，不按行截断）。

#### 4.1.6 资源清理

```scala
Runtime.getRuntime.addShutdownHook(new Thread(() => {
  linkProc.destroy(); viteProc.destroy(); cleanup(tempCfg, tempDir)
}))
```

#### 4.1.7 build 模式

```scala
val linkExit = Process(List("bleep", "link", projectNameStr, "--no-tui")).run(rawIO).exitValue()
// linkExit != 0 → sys.error
val buildExit = Process(List("npx", "vite", "build", "--config", tempCfg.toString)).run(rawIO).exitValue()
cleanup(tempCfg, tempDir)
```

非 watch、阻塞、失败即抛异常、结束即清理。

### 4.2 组件 B：前端入口 main.js

```js
import './style.css';
import 'scalajs:main.js';   // 虚拟路径，由 LinkVite 生成的配置拦截
```

- `scalajs:main.js` 不是真实文件，是**约定**的虚拟标识符。
- Vite 加载 `main.js` 时，`resolve.alias` 在 `resolveId` 阶段把 `scalajs:main.js`
  替换为 bleep 产物的绝对路径，再按普通 JS 模块解析、加载。
- bleep 的 `jsKind: none` 产物是 IIFE，文件末尾自动调用 `Main.main`，故 side-effect
  导入即触发 Laminar 应用挂载。

### 4.3 组件 C：bleep.yaml 脚本注册

```yaml
projects:
  scripts:                          # 脚本编译项目（必须 JVM，不能继承 JS 模板）
    platform: { name: jvm }
    scala: { version: 3.8.3, strict: true, options: "-encoding utf8 -feature -unchecked" }
    dependencies:
      - build.bleep::bleep-core:1.0.0-M10   # 暴露 BleepScript / Started / Commands / ProjectPaths

scripts:
  link-vite:
    main: scripts.LinkVite        # 全限定对象名
    project: scripts                # 由哪个项目编译
```

> 调用形式：`bleep link-vite [args]`（bleep 把注册的脚本名提升为顶层命令）。

---

## 5. 关键设计决策

### 5.1 为什么不用 `commands.link(watch = true)`？（与原始规格的偏差）

**原始设计假设** `Commands` 提供 `link` 方法。**实测结论**：bleep M10 的脚本
`Commands` API 只暴露 `compile / run / test / script / publishLocal / publish`，
**没有 `link`**（链接由 CLI / BSP 内部的 `bleep.bsp.LinkExecutor` 实现）。

| 方案 | 可行性 | 取舍 |
|------|--------|------|
| `commands.link(watch=true)` | ❌ API 不存在 | — |
| 子进程 `bleep link --watch` | ✅ 采用 | 多一次进程启动（毫秒级），换来与 CLI 完全一致的行为 |
| 直接调用 `bleep.bsp.LinkExecutor` | ⚠️ 内部 API | 不稳定，跨版本可能失效 |

**结论**：以子进程方式实现后台链接，效果与原规格一致，仅 API 形式不同。此偏差已在
`LinkVite` 的 Scaladoc 与 README 中明确记录。

### 5.2 为什么临时配置用 CJS 而非 ESM？

- Vite 8 的 `vite` 包是 ESM-only，CJS 中 `require('vite')` 会抛 `ERR_REQUIRE_ESM`。
- 但 `defineConfig` 仅为 TypeScript 类型辅助，**纯配置对象不需要它**。
- 因此用 `module.exports = {...}`（CJS）+ 不引入 `vite`，规避了模块系统冲突，
  同时满足规格要求的 `.js` 后缀（无需 `.mjs`，无需 sibling `package.json`）。

### 5.3 为什么 `scalajs:main.js` 用别名（alias）而非 Vite 插件？

- 原规格明确要求 `resolve.alias` 形式，且前端代码保持纯净（不感知构建细节）。
- `@rollup/plugin-alias`（Vite 内置）在 `resolveId` 早期阶段做精确字符串匹配替换，
  对 `import 'scalajs:main.js'` 可正确拦截，无需自定义插件代码。
- 代价：若别名键含特殊字符可能失效；实测 `scalajs:main.js` 工作正常（HTTP 200）。

### 5.4 为什么需要 `waitForFile`？

首次运行时 `main.js` 尚未生成。若 Vite 在产物出现前启动，首次浏览器请求会 404。
脚本在启动后台链接后、启动 Vite 前，轮询等待 `main.js` 出现（超时 90s），消除竞态。

---

## 6. 错误处理与边界

| 场景 | 处理 |
|------|------|
| 项目名不存在 | `started.projectPaths` 抛 bleep 异常，脚本终止 |
| 首次无产物 | 打印警告 + `waitForFile` 等待后台链接产出 |
| bleep link 非零退出（build 模式） | `sys.error` 终止 |
| vite build 非零退出 | `sys.error` 终止，仍清理临时配置 |
| `Ctrl+C` (SIGINT) | shutdown hook 清理子进程 + 删除临时文件 |
| `kill -9` (SIGKILL) | **绕过 hook**，临时文件残留，需手动删 `/tmp/link-vite-*` |
| 项目根存在 `vite.config.*` | `--config` 旁路静态配置（不生效、不报错）；脚本检测到会打印警告提醒，不阻止运行 |
| 端口 5173 占用 | vite 自行报错（脚本不抢端口管理） |

---

## 7. 使用方式

### 7.1 开发（dev，默认）

```bash
npm run dev                # = bleep link-vite baidu
# 或
bleep link-vite          # 默认 dev + 默认 baidu
bleep link-vite dev baidu
```

启动后浏览器打开 `http://localhost:5173`，改 `.scala` 保存即自动刷新。

### 7.2 生产构建（build）

```bash
npm run build              # = bleep link-vite build baidu
# 或
bleep link-vite build baidu
```

产出 `dist/`（Vite 压缩/tree-shake，实测 1.6MB → gzip 274KB）。

### 7.3 预览

```bash
npm run preview            # vite preview，服务 dist/
```

---

## 8. 验证记录

| 检查项 | 方法 | 结果 |
|--------|------|------|
| `scripts` 项目编译 | `bleep compile scripts` | ✅ 0 failed |
| 脚本注册与调用 | `bleep link-vite --help` | ✅ 可用 |
| dev 双路监听启动 | 后台 `bleep link --watch` + 前台 Vite | ✅ Vite ready on :5173 |
| 别名解析 | `curl /scalajs:main.js` | ✅ HTTP 200，展开为真实产物路径 |
| HMR 中继 | 改 Main.scala footer → 观察 | ✅ bleep `Changed: Set(baidu)` → Vite `page reload` → 新 bundle 含新文本 |
| build 模式 | `bleep link-vite build baidu` | ✅ dist/ 生成，临时配置已清理 |
| dist 渲染 | `node verify-render.mjs <dist asset>` | ✅ 完整百度 UI 挂载 |
| shutdown hook | `Ctrl+C` | ✅ 子进程终止、临时目录清理 |

---

## 9. 可扩展性

本设计天然支持多 Scala.js 项目：脚本参数化项目名即可（`bleep link-vite dev my-other-app`），
无需改动 `LinkVite`。后续可扩展方向：

- 支持 release 模式链接（`--release`，产物在 `link-output/release/js`）。
- 把端口、是否自动打开浏览器参数化。
- 在临时配置中注入自定义 Vite 插件（如 PostCSS、React Fast Refresh）。
- 用 `started.bleepExecutable` 替代 PATH 上的 `bleep`，提升子进程定位的健壮性。

---

## 10. 文件清单

```
scripts/src/scala/scripts/LinkVite.scala   # 调度器（本设计的核心）
bleep.yaml                                    # scripts 项目 + link-vite 注册
src/main.js                                   # 前端入口（import 'scalajs:main.js'）
src/style.css                                 # 占位样式
index.html                                    # 页面入口
docs/link-vite-design.md                    # 本文档
```
