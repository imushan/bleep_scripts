package scripts

import bleep.{BleepScript, Commands, Started}
import io.circe.{Json, JsonObject, Printer}
import io.circe.parser

import java.nio.file.{Files, Path, Paths}

/** `bleep mcp-setup` — wires ScalaSemantic into this project.
  *
  * It does three things (the sbt plugin's `mcpClientConfig` equivalent):
  *   1. installs the official auto-download launcher to `~/.local/bin` (if absent);
  *   2. prefetches the server fat jar once, so the first MCP connect hits a warm cache — the
  *      actual download logic lives in the launcher, not here;
  *   3. merges a `scala-semantic` entry into `.mcp.json`, leaving any other servers untouched.
  *
  * The SemanticDB data the server reads is produced by `bleep compile` (SemanticDB is enabled in
  * `bleep.yaml`); this script only configures the server, it does not compile.
  */
object McpSetup extends BleepScript("mcp-setup"):
  override def run(started: Started, commands: Commands, args: List[String]): Unit =
    val root = started.buildPaths.buildDir.toAbsolutePath
    val launcher = Paths.get(sys.props("user.home"), ".local", "bin", "scalasemantic-mcp")
    val launcherUrl =
      "https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh"

    started.logger.info(s"Project root: $root")

    // ① install launcher (if absent): download the .sh, chmod +x.
    if !Files.exists(launcher) then
      started.logger.info(s"Installing launcher to $launcher")
      Files.createDirectories(launcher.getParent)
      val bytes = new java.net.URI(launcherUrl).toURL.openStream.readAllBytes()
      Files.write(launcher, bytes)
      if !launcher.toFile.setExecutable(true) then
        started.logger.warn("Could not mark launcher executable")
    else
      started.logger.info(s"Launcher already present: $launcher")

    // ② prefetch the server jar (one-time, ~88MB). The launcher owns the download logic.
    started.logger.info("Prefetching server jar (one-time, ~88MB)…")
    val pb = new ProcessBuilder(launcher.toString, "--prefetch", root.toString)
    pb.inheritIO()
    val rc = pb.start().waitFor()
    if rc != 0 then
      started.logger.warn(s"Prefetch returned $rc; server will download on first connect instead")

    // ③ merge a `scala-semantic` entry into .mcp.json, keeping everything else (e.g. `bleep`).
    writeMcpJson(root, launcher)
    started.logger.info("Done. Restart Claude Code, then try find_symbol.")

  private def writeMcpJson(root: Path, launcher: Path): Unit =
    val file = root.resolve(".mcp.json")
    val entry = Json.obj(
      "command" -> Json.fromString(launcher.toString),
      "args" -> Json.arr(
        Json.fromString(root.toString),
        Json.fromString("--log"),
        Json.fromString("--log-output")
      ),
      // Override JAVA_HOME: the inherited env var may point at a stale/broken coursier cache
      // path (e.g. a double-encoded %252B dir that was never extracted), which makes `cs launch`
      // fail to fork the server JVM with "Cannot run program .../bin/java: No such file". We
      // derive a real JAVA_HOME from the `java` found on PATH instead.
      "env" -> Json.obj(
        "JAVA_HOME" -> Json.fromString(detectJavaHome())
      )
    )
    val existing: Json =
      if Files.exists(file) then parser.parse(Files.readString(file)).getOrElse(Json.obj())
      else Json.obj()

    // Replace only mcpServers.scala-semantic; preserve all other top-level keys & servers.
    val updated = existing.mapObject { obj =>
      val servers = obj("mcpServers").flatMap(_.asObject).getOrElse(JsonObject.empty)
      obj.add("mcpServers", Json.fromJsonObject(servers.add("scala-semantic", entry)))
    }

    Files.writeString(file, Printer.spaces2.print(updated))

  /** Derive a usable JAVA_HOME from the `java` on PATH. The inherited JAVA_HOME env var can be a
    * broken coursier cache path; this resolves the real JVM the user actually runs.
    */
  private def detectJavaHome(): String =
    val pb = new ProcessBuilder("sh", "-c", """readlink -f "$(which java)"""")
    pb.redirectErrorStream(true)
    val p = pb.start()
    val javaPath = new String(p.getInputStream.readAllBytes()).trim
    p.waitFor()
    val javaFile = new java.io.File(javaPath)
    Option(javaFile.getParentFile).flatMap(bin => Option(bin.getParentFile)) match
      case Some(jh) => jh.getAbsolutePath
      case None     => sys.error(s"Could not derive JAVA_HOME from java at: $javaPath")
