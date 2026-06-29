package bleep.scripts

import bleep._
import bleep.commands.Dist
import os._

object CustomDist extends BleepScript("CustomDist"):

  override def run(
    started: Started,
    commands: Commands,
    args: List[String]
  ): Unit = {
    val projectName = model.ProjectName("api-server")
    println(s"Starting distribution for $projectName...")
    val crossName = model.CrossProjectName(projectName, None)
    val distOptions = bleep.commands.Dist.Options(
      project = crossName,
      overrideMain = None,
      overridePath = None
    )
    val distCommand = bleep.commands.Dist(
      started = started,
      watch = false,
      options = distOptions
    )
    distCommand.run(started = started)

    val sourcedistDir =
      started.projectPaths(crossName).targetDir.resolve("dist")

    val targetDir = started.buildPaths.buildDir.resolve("out")

    if (os.exists(os.Path(sourcedistDir))) {
      println(s"Moving artifacts from $sourcedistDir to $targetDir")
      os.remove.all(os.Path(targetDir))

      os.move(os.Path(sourcedistDir), os.Path(targetDir))

      println(s"Success! Your distribution is ready at: $targetDir")

    } else {
      println(s"Error: Could not find distribution at $sourcedistDir")
    }

  }
