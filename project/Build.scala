/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
import sbt._
import Keys._
import sbtassembly.Plugin.AssemblyKeys._
import sbt.NameFilter._

object PlatformBuild extends Build {
  val archiveDir    = SettingKey[String]("archive-dir", "The temporary directory to which deleted projections will be moved")
  val dataDir       = SettingKey[String]("data-dir", "The temporary directory into which to extract the test data")
  val profileTask   = InputKey[Unit]("profile", "Runs the given project under JProfiler")
  val extractData   = TaskKey[String]("extract-data", "Extracts the data files used by the tests and the REPL")

  val blueeyesVersion = "1.0.0-M9.5"
  val scalazVersion   = "7.0.0"
  def optimizeOpts    = if (sys.props contains "com.precog.build.optimize") Seq("-optimize") else Seq()

  val commonSettings = Seq(
                               organization :=  "com.precog",
                                    version :=  "2.6.1-SNAPSHOT",
                              scalacOptions +=  "-g:none",
                              scalacOptions ++= optimizeOpts,
                               javacOptions ++= Seq("-source", "1.6", "-target", "1.6"),
                               scalaVersion :=  "2.9.3",
                        jarName in assembly <<= (name) map { name => name + "-assembly-" + ("git describe".!!.trim) + ".jar" },
                         target in assembly <<= target,
    (unmanagedSourceDirectories in Compile) <<= (scalaSource in Compile, javaSource in Compile)(Seq(_) ++ Set(_)),
       (unmanagedSourceDirectories in Test) <<= (scalaSource in Test)(Seq(_)),
                  parallelExecution in test :=  false,
                        logBuffered in Test :=  false,

    libraryDependencies ++= Seq(
      "com.weiglewilczek.slf4s"   % "slf4s_2.9.1"    %     "1.0.7",
      "com.google.guava"          % "guava"          %    "13.0.1",
      "com.google.code.findbugs"  % "jsr305"         %     "1.3.9",
      "org.scalaz"               %% "scalaz-core"    %  scalazVersion,
      "com.reportgrid"           %% "blueeyes-json"  % blueeyesVersion,
      "com.reportgrid"           %% "blueeyes-core"  % blueeyesVersion,
      "com.reportgrid"           %% "blueeyes-mongo" % blueeyesVersion,
      "com.reportgrid"           %% "akka_testing"   % blueeyesVersion,
      "org.scalacheck"           %% "scalacheck"     %     "1.10.1"     % "test",
      "com.rubiconproject.oss"    % "jchronic"       %     "0.2.6"
    )
  )

  def jprofilerArg(base: File) = "-agentpath:%s=offline,config=%s/%s,id=%s".format(
    "/Applications/jprofiler7/bin/macos/libjprofilerti.jnilib",
    base,
    "src/main/resources/jprofile.xml",
    "116"
  )

  def jprofilerSettings = Seq(
                   fork in run :=  true,
           fork in profileTask :=  true,
    javaOptions in profileTask <<= (javaOptions, baseDirectory) map ((opts, b) => opts :+ jprofilerArg(b))
  )

  // https://github.com/sbt/sbt-assembly
  //
  // By the way, the first case pattern in the above using PathList(...) is how
  // you can pick javax/servlet/* from the first jar. If the default
  // MergeStrategy.deduplicate is not working for you, that likely means you have
  // multiple versions of some library pulled by your dependency graph. The real
  // solution is to fix that dependency graph. You can work around it by
  // MergeStrategy.first but don't be surprised when you see
  // ClassNotFoundException.
  import sbtassembly.Plugin.{ MergeStrategy, PathList }
  def assemblyMerger(path: String): MergeStrategy = path match {
    case s if s endsWith ".txt"                    => MergeStrategy.discard
    case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
    case PathList("org", "slf4j", "impl", xs @ _*) => MergeStrategy.first
    case _                                         => MergeStrategy.deduplicate
  }

  def commonAssemblySettings = sbtassembly.Plugin.assemblySettings ++ commonSettings ++ Seq(
             test in assembly := (),
    mergeStrategy in assembly := assemblyMerger
  )

  // Logging is simply a common project for the test log configuration files
  lazy val logging = Project(id = "logging", base = file("logging")).settings(commonSettings: _*)

  lazy val platform = Project(id = "platform", base = file(".")).
    aggregate(yggdrasil, bytecode, util, common)

  lazy val util = Project(id = "util", base = file("util")).
    settings(commonSettings: _*) dependsOn(logging % "test->test")

  lazy val common = Project(id = "common", base = file("common")).
    settings(commonSettings: _*) dependsOn (util, logging % "test->test")

  lazy val bytecode = Project(id = "bytecode", base = file("bytecode")).
    settings(commonSettings: _*) dependsOn(logging % "test->test")

  lazy val niflheim = Project(id = "niflheim", base = file("niflheim")).
    settings(commonAssemblySettings: _*).dependsOn(common % "compile->compile;test->test", util, logging % "test->test")

  lazy val yggdrasil = Project(id = "yggdrasil", base = file("yggdrasil")).
    settings(commonAssemblySettings: _*).dependsOn(common % "compile->compile;test->test", bytecode, util, niflheim, logging % "test->test")
}
