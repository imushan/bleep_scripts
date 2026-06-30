package scripts

import bleep.{BleepScript, Commands, Started}
import io.circe.{Json, JsonObject, Printer}
import io.circe.parser
import os.*

/** `bleep mcp-setup` —— 把 ScalaSemantic 接入本项目（bleep 版，等价于 sbt 插件的 `mcpClientConfig`）。
  *
  * 文件/路径/进程一律走 os-lib（com.lihaoyi::os-lib），等价逻辑比 java.nio.file + ProcessBuilder 更紧凑：
  *   - 路径用 `os.Path` / `os.home` / `os.up`，文件读写用 `os.read` / `os.write.over`；
  *   - 下载 launcher 字节用 JDK URL、`os.write.over` 落盘（os-lib 0.11.x 核心已无 `os.download`），赋可执行位用 `os.perms.set`；
  *   - 跑子进程用 `os.proc(...).call(check = false, stdout = os.Inherit, ...)`。
  *
  * 做四件事：
  *   1. 安装官方自动下载 launcher 到 `~/.local/bin`（若不存在）；真正下载逻辑在 launcher，不在此处；
  *   2. 预热 server fat jar 一次，让首次 MCP 连接命中热缓存；
  *   3. 写出目标项目的 compile classpath 文件 —— 这是启用 **presentation-compiler（实时）后端** 的关键：
  *      server 拿到它才能对「尚未编译 / 正在编辑」的 buffer 做 live 类型叠加。失败时自动降级为
  *      index-only（仅静态 SemanticDB 索引），服务器仍可用，只是没有实时能力（与 sbt 插件行为一致）；
  *   4. 把一个 `scala-semantic` 条目合并进 `.mcp.json`，其它 server 原样保留。
  *
  * server 读取的 SemanticDB 数据由 `bleep compile` 产出（`bleep.yaml` 已开启 SemanticDB）；
  * 本脚本只负责配置 server，本身不编译（但会在缺 classpath 时触发一次 compile，见 ③）。
  */
object McpSetup extends BleepScript("mcp-setup"):
  override def run(started: Started, commands: Commands, args: List[String]): Unit =
    // bleep 的 buildDir 是 java.nio.file.Path，转成 os.Path 统一后续操作。
    val root: os.Path = os.Path(started.buildPaths.buildDir.toAbsolutePath.toString)
    val launcher: os.Path = os.home / ".local" / "bin" / "scalasemantic-mcp"
    val launcherUrl =
      "https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh"
    // 默认对主代码项目 `app` 启用实时（PC）后端；可传参覆盖，如 `bleep mcp-setup scripts`。
    val targetProject = args.headOption.getOrElse("app")

    started.logger.info(s"Project root: $root")

    // ① 安装 launcher（若不存在）：下载 .sh，赋予可执行位。
    if !os.exists(launcher) then
      started.logger.info(s"Installing launcher to $launcher")
      os.makeDir.all(launcher / os.up)
      // os-lib 核心无 os.download（0.11.x 起移除），故用 JDK 拉字节、os.write 落盘的混合方式。
      val bytes = new java.net.URI(launcherUrl).toURL.openStream().readAllBytes()
      os.write.over(launcher, bytes)
      os.perms.set(launcher, "rwxr-xr-x")
    else
      started.logger.info(s"Launcher already present: $launcher")

    // ② 预热 server jar（一次性，~88MB）。下载逻辑归 launcher 管。
    started.logger.info("Prefetching server jar (one-time, ~88MB)…")
    val rc = os.proc(launcher.toString, "--prefetch", root.toString)
      .call(check = false, stdout = os.Inherit, stderr = os.Inherit)
      .exitCode
    if rc != 0 then
      started.logger.warn(s"Prefetch returned $rc; server will download on first connect instead")

    // ③ 生成 compile classpath 文件（启用实时 PC 后端）。详见 writeClasspathFile 的降级策略。
    val classpathFile = writeClasspathFile(root, targetProject, started.logger)

    // ④ 合并 `scala-semantic` 条目到 .mcp.json，保留其它顶层 key 与 server。
    writeMcpJson(root, launcher, classpathFile)
    started.logger.info("Done. Restart Claude Code, then try find_symbol.")

  /** 写出目标项目的 compile classpath 文件，用于启用 presentation-compiler（实时）后端。
    *
    * compile classpath = 依赖 jar（bloop 配置的 `classpath` 字段）+ 本项目编译输出（`classesDir`）。
    * PC 后端靠它解析未编译/编辑中 buffer 里引用到的类型，缺了它就没有 live 叠加能力。
    *
    * 必须先 `bleep compile` 才会生成 bloop json 和编译产物：这里在缺失时自动触发一次 compile；
    * 若最终仍拿不到有效 classpath（例如编译失败），返回 None —— 退化为 index-only，服务器仍可用。
    */
  private def writeClasspathFile(root: os.Path, project: String, logger: ryddig.Logger): Option[os.Path] =
    val bloopFile = root / ".bleep" / "builds" / "normal" / ".bloop" / s"$project.json"

    // bloop 配置或编译产物缺失时，触发一次 compile 把它们补齐。
    if !classpathReady(bloopFile) then
      logger.info(s"Compiling `$project` 以生成 compile classpath…")
      val code = os.proc("bleep", "compile", project)
        .call(check = false, stdout = os.Inherit, stderr = os.Inherit)
        .exitCode
      if code != 0 then
        logger.warn(s"`bleep compile $project` 返回非零 → 以 index-only 模式运行（无实时叠加）")
        return None

    readCompileClasspath(bloopFile) match
      case Some(cp) =>
        val file = root / ".bleep" / "scala-semantic-classpath.txt"
        os.write.over(file, cp)
        logger.info(s"compile classpath 已写出（${cp.count(_ == ':') + 1} 项）：$file")
        Some(file)
      case None =>
        logger.warn(s"无法从 $bloopFile 解析 classpath → 以 index-only 模式运行（无实时叠加）")
        None

  /** bloop 配置存在且 classesDir 已有编译产物，才算 classpath 就绪。 */
  private def classpathReady(bloopFile: os.Path): Boolean =
    readCompileClasspath(bloopFile).isDefined

  /** 从 bloop 配置解析出 compile classpath（依赖 jar 列表 + classesDir），用 `:` 拼接。解析失败返回 None。 */
  private def readCompileClasspath(bloopFile: os.Path): Option[String] =
    if !os.exists(bloopFile) then None
    else
      for
        json <- parser.parse(os.read(bloopFile)).toOption
        project = json.hcursor.downField("project")
        deps <- project.downField("classpath").as[Vector[String]].toOption
        classesDir <- project.downField("classesDir").as[String].toOption
        // classesDir 必须存在且非空，否则说明尚未编译
        if classesDir.nonEmpty && os.exists(os.Path(classesDir)) && os.list(os.Path(classesDir)).nonEmpty
      yield (deps :+ classesDir).mkString(":")

  /** 合并 `scala-semantic` 条目到 .mcp.json，只替换该 server，保留其它顶层 key 与 server。
    *
    * classpathFile 为 Some 时，argv 为 `[root, classpathFile, --log, --log-output]` —— classpath 占 arg2，
    * 让 server 启用实时 PC 后端；为 None 时省略 arg2，退化为 index-only。
    */
  private def writeMcpJson(root: os.Path, launcher: os.Path, classpathFile: Option[os.Path]): Unit =
    val file = root / ".mcp.json"
    // arg1 = SemanticDB root；arg2（可选）= classpath 文件，启用 PC 实时后端；其后为日志开关。
    val argv: Vector[String] =
      Vector(root.toString) ++
        classpathFile.map(_.toString) ++
        Vector("--log", "--log-output")
    val entry = Json.obj(
      "command" -> Json.fromString(launcher.toString),
      "args" -> Json.fromValues(argv.map(Json.fromString)),
      // 覆盖 JAVA_HOME：继承的环境变量可能指向过期/损坏的 coursier 缓存路径（例如一个从未解压的
      // 双重编码 %252B 目录），导致 `cs launch` 无法 fork 出 server JVM，报 "Cannot run program .../bin/java:
      // No such file"。这里改从 PATH 上找到的 `java` 反推一个真实可用的 JAVA_HOME。
      "env" -> Json.obj(
        "JAVA_HOME" -> Json.fromString(detectJavaHome())
      )
    )
    val existing: Json =
      if os.exists(file) then parser.parse(os.read(file)).getOrElse(Json.obj())
      else Json.obj()

    // 只替换 mcpServers.scala-semantic；保留其它顶层 key 与 server（例如 `bleep`）。
    val updated = existing.mapObject { obj =>
      val servers = obj("mcpServers").flatMap(_.asObject).getOrElse(JsonObject.empty)
      obj.add("mcpServers", Json.fromJsonObject(servers.add("scala-semantic", entry)))
    }

    val _ = os.write.over(file, Printer.spaces2.print(updated))

  /** 从 PATH 上的 `java` 反推可用 JAVA_HOME。继承的 JAVA_HOME 环境变量可能是损坏的 coursier 缓存路径，
    * 这里解析出用户实际运行的那个真实 JVM。`java` 位于 `<JAVA_HOME>/bin/java`，上溯两级即 JAVA_HOME。
    */
  private def detectJavaHome(): String =
    val javaPath = os.proc("sh", "-c", """readlink -f "$(which java)"""").call().out.text().trim
    val java = os.Path(javaPath)
    val jh = java / os.up / os.up
    if os.isDir(jh) then jh.toString
    else sys.error(s"Could not derive JAVA_HOME from java at: $javaPath")
