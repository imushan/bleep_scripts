package scripts

import bleep._
import bleep.model.{CrossProjectName, ProjectName}

import java.io.{InputStream, PrintStream}
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessIO}

/** 全局调度器：为 Scala.js 项目驱动一条 bleep link + Vite 的统一开发流水线。
  *
  * 完全不依赖静态的 vite.config.js，也不用 sbt 绑定的 @scala-js/vite-plugin-scalajs。
  *
  * 用法（作为 bleep 脚本注册名 `link-vite`）：
  *   bleep link-vite [dev|build] [project]
  *     - dev   （默认）开发模式：双路监听（后台 bleep link --watch + 前台 vite）
  *     - build 生产构建：链接一次后 `vite build`，产物输出到 dist/
  *     - project 默认 baidu
  *
  * 开发模式工作流：
  *   1. 直接读取 bleep 内部项目模型，定位 Scala.js 链接产物的物理路径；
  *   2. 在临时目录动态生成一个 vite 配置，通过 resolve.alias 把虚拟路径
  *      `scalajs:main.js` 映射到该产物（前端 `src/main.js` 只需 `import 'scalajs:main.js'`）；
  *   3. 双路监听：
  *        - 后台：`bleep link <project> --watch`，改 .scala 后毫秒级增量重链接刷新 main.js；
  *        - 前台：`npx vite --config <临时配置>`，其内置文件监控捕获 main.js 变动并 HMR 推送到浏览器。
  *
  * 说明：bleep 的脚本 Commands API 只暴露 compile/run/test/script/publish*，
  * 并没有 link 方法（链接由 CLI / BSP 内部实现），因此后台链接以子进程方式运行
  * `bleep link --watch`，效果与 `commands.link(watch = true)` 完全一致。
  */
object LinkVite extends BleepScript("LinkVite"):

  /** vite 默认会搜索的静态配置文件名。若项目根存在其中任意一个，会被本脚本忽略，
    * 因此需要提醒开发者：静态配置在这里不会生效。 */
  private val staticConfigNames: List[String] =
    List("vite.config.js", "vite.config.mjs", "vite.config.cjs",
         "vite.config.ts", "vite.config.mts", "vite.config.cts")

  override def run(started: Started, commands: Commands, args: List[String]): Unit =
    val positional = args.filterNot(_.startsWith("--"))
    val buildMode = positional.contains("build")
    val projectNameStr = positional.find(_ != "build").getOrElse("baidu")
    val crossName = CrossProjectName(ProjectName(projectNameStr), None)

    // 1. 从 bleep 项目模型定位 Scala.js 链接产物（debug / fast-opt）。
    //    targetDir = .bleep/builds/normal/.bloop/<project>
    val targetDir: Path = started.projectPaths(crossName).targetDir
    val mainJs: Path =
      targetDir.resolve("link-output").resolve("debug").resolve("js").resolve("main.js")
    val mainJsAbs: String = mainJs.toAbsolutePath.toString
    val projectRoot: Path = started.buildPaths.buildDir
    println(s"[link-vite] 模式=${if buildMode then "build" else "dev"} 项目=$projectNameStr 产物=$mainJsAbs")

    // 提醒：项目根若已存在静态 vite 配置，它会被本脚本的 --config 旁路（不生效、不报错）。
    warnIfStaticConfig(projectRoot)

    // 2. 在临时目录下动态生成 Vite 配置（CJS，纯对象，无需 import vite）。
    val tempDir: Path = Files.createTempDirectory("link-vite-")
    val tempConfig: Path = writeTempConfig(tempDir, mainJs, projectRoot)

    if buildMode then runBuild(started, projectNameStr, mainJs, tempConfig, tempDir)
    else runDev(started, projectNameStr, mainJs, tempConfig, tempDir)

  /** 若项目根存在静态 vite 配置文件，打印警告（不阻止运行）。 */
  private def warnIfStaticConfig(projectRoot: Path): Unit =
    val found = staticConfigNames.filter(name => Files.exists(projectRoot.resolve(name)))
    if found.nonEmpty then
      println(s"[link-vite] ⚠️  检测到项目根存在静态配置 ${found.mkString(", ")}。")
      println("[link-vite]    本脚本通过 --config 指定临时配置，静态配置会被忽略（不生效、不报错）。")
      println("[link-vite]    若需自定义 Vite 行为，请修改本脚本 LinkVite 的 writeTempConfig。")

  /** 开发模式：双路监听。 */
  private def runDev(
      started: Started,
      projectNameStr: String,
      mainJs: Path,
      tempConfig: Path,
      tempDir: Path
  ): Unit =
    // 线程 A（后台）：bleep link --watch，增量重链接刷新 main.js。
    val linkCmd: List[String] = List("bleep", "link", projectNameStr, "--watch", "--no-tui")
    println(s"[link-vite] 启动后台链接监听: ${linkCmd.mkString(" ")}")
    val linkProc = Process(linkCmd).run(rawIO)

    // 等 main.js 出现后再启动 Vite，避免首次请求 404。
    waitForFile(mainJs, timeoutMillis = 90000L)

    // 线程 B（前台/主线程阻塞）：npx vite，接管终端 IO。
    val viteCmd: List[String] =
      List("npx", "vite", "--config", tempConfig.toAbsolutePath.toString)
    println(s"[link-vite] 启动前台 Vite: ${viteCmd.mkString(" ")}")
    val viteProc = Process(viteCmd).run(rawIO)

    // 资源清理：退出（Ctrl+C）时删除临时配置、终止两个子进程。
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("[link-vite] 退出中，清理临时配置与子进程…")
      try {
        linkProc.destroy()
        viteProc.destroy()
        cleanup(tempConfig, tempDir)
      } catch case _: Exception => ()
    }))

    // 阻塞主线程直到 Vite 退出（Ctrl+C 触发上面的 shutdown hook）。
    val _: Int = viteProc.exitValue()
    ()

  /** 生产构建：链接一次 + vite build。 */
  private def runBuild(
      started: Started,
      projectNameStr: String,
      mainJs: Path,
      tempConfig: Path,
      tempDir: Path
  ): Unit =
    println(s"[link-vite] 链接一次: bleep link $projectNameStr")
    val linkExit = Process(List("bleep", "link", projectNameStr, "--no-tui")).run(rawIO).exitValue()
    if linkExit != 0 then
      sys.error(s"[link-vite] 链接失败 (exit=$linkExit)")

    val viteCmd: List[String] =
      List("npx", "vite", "build", "--config", tempConfig.toAbsolutePath.toString)
    println(s"[link-vite] 生产构建: ${viteCmd.mkString(" ")}")
    val buildExit = Process(viteCmd).run(rawIO).exitValue()
    cleanup(tempConfig, tempDir)
    if buildExit != 0 then sys.error(s"[link-vite] vite build 失败 (exit=$buildExit)")
    println("[link-vite] 构建完成，产物在 dist/")
    ()

  /** 在临时目录写入 Vite 配置：resolve.alias 把 scalajs:main.js 指向 bleep 产物。 */
  private def writeTempConfig(tempDir: Path, mainJs: Path, projectRoot: Path): Path =
    val tempConfig = Files.createTempFile(tempDir, "vite-config-", ".js")
    val mainJsAbs = mainJs.toAbsolutePath.toString
    // server.fs.allow 会覆盖默认值，因此必须同时放行项目根（index.html / src）
    // 与 .bleep 下的链接产物目录。
    val configContent: String =
      s"""module.exports = {
         |  // 前端 import 'scalajs:main.js' 在此被拦截并映射到 bleep 产物。
         |  resolve: { alias: { 'scalajs:main.js': ${jsString(mainJsAbs)} } },
         |  server: {
         |    port: 5173,
         |    fs: {
         |      allow: [
         |        ${jsString(projectRoot.toAbsolutePath.toString)},
         |        ${jsString(mainJs.getParent.toAbsolutePath.toString)}
         |      ]
         |    }
         |  }
         |};
         |""".stripMargin
    Files.writeString(tempConfig, configContent)
    println(s"[link-vite] 生成临时 Vite 配置: $tempConfig")
    println(s"[link-vite] 别名 scalajs:main.js -> $mainJsAbs")
    tempConfig

  private def cleanup(tempConfig: Path, tempDir: Path): Unit =
    try
      val _: Boolean = Files.deleteIfExists(tempConfig)
      val _: Boolean = Files.deleteIfExists(tempDir)
    catch case _: Exception => ()

  /** 把任意字符串转成合法的 JS 字符串字面量。 */
  private def jsString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** 轮询等待文件出现（用于首次链接产物生成）。 */
  private def waitForFile(path: Path, timeoutMillis: Long): Unit =
    val deadline = System.currentTimeMillis() + timeoutMillis
    while !Files.exists(path) && System.currentTimeMillis() < deadline do
      Thread.sleep(250)

  /** 子进程 IO：把 stdout/stderr 原样转发到当前进程，保留 ANSI 颜色。 */
  private def rawIO: ProcessIO = new ProcessIO(
    os => os.close(),
    is => transfer(is, System.out),
    es => transfer(es, System.err)
  )

  private def transfer(in: InputStream, out: PrintStream): Unit =
    val buf = new Array[Byte](4096)
    var n = 0
    try
      while { n = in.read(buf); n != -1 } do
        out.write(buf, 0, n)
        out.flush()
    catch case _: java.io.IOException => ()
    finally in.close()
