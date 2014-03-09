import sbt._
import Keys._

import sbtassembly.{Plugin=>SbtAssembly}
import org.sbtidea.SbtIdeaPlugin
import com.typesafe.sbt.SbtProguard
import com.typesafe.sbt.SbtNativePackager

object ProjectBuild extends Build {
    override lazy val settings = super.settings ++ Seq(
        organization := "org.refptr",
        version := "0.2-SNAPSHOT",
        description := "Scala-language backend for IPython",
        scalaVersion := "2.10.3",
        scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:_"),
        shellPrompt := { state =>
            "refptr (%s)> ".format(Project.extract(state).currentProject.id)
        },
        cancelable := true,
        resolvers ++= Seq(
            Resolver.sonatypeRepo("releases"),
            Resolver.sonatypeRepo("snapshots"),
            Resolver.typesafeIvyRepo("releases"),
            "mandubian-releases" at "https://github.com/mandubian/mandubian-mvn/raw/master/releases/",
            "mandubian-snapshots" at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/")
    )

    object Dependencies {
        val scalaio = {
            val namespace = "com.github.scala-incubator.io"
            val version = "0.4.2"
            Seq(namespace %% "scala-io-core" % version,
                namespace %% "scala-io-file" % version)
        }

        val ivy = "org.scala-sbt" % "ivy" % "0.13.0"

        val jopt = "net.sf.jopt-simple" % "jopt-simple" % "4.6"

        val jeromq = "org.jeromq" % "jeromq" % "0.3.0-SNAPSHOT"

        val play_json = "play" %% "play-json" % "2.2-SNAPSHOT"

        val slick = "com.typesafe.slick" %% "slick" % "1.0.1"

        val h2 = "com.h2database" % "h2" % "1.3.175"

        val sqlite = "org.xerial" % "sqlite-jdbc" % "3.7.2"

        val slf4j = "org.slf4j" % "slf4j-nop" % "1.7.6"

        val specs2 = "org.specs2" %% "specs2" % "2.3.10" % "test"

        val reflect = Def.setting { "org.scala-lang" % "scala-reflect" % scalaVersion.value }

        val compiler = Def.setting { "org.scala-lang" % "scala-compiler" % scalaVersion.value }
    }

    val release = taskKey[File]("Create a set of archives and installers for new release")

    val ipyCommands = settingKey[Seq[(String, String)]]("IPython commands (e.g. console) and their command line options")

    val jrebelJar = settingKey[Option[File]]("Location of jrebel.jar")
    val jrebelOptions = settingKey[Seq[String]]("http://manuals.zeroturnaround.com/jrebel/misc/index.html#agent-settings")
    val jrebelCommand = taskKey[Seq[String]]("JVM command line options enabling JRebel")

    val debugPort = settingKey[Int]("Port for remote debugging")
    val debugCommand = taskKey[Seq[String]]("JVM command line options enabling remote debugging")

    val develScripts = taskKey[Seq[File]]("Development scripts generated in bin/")
    val userScripts = taskKey[Seq[File]]("User scripts generated in target/bin/")

    lazy val ideaSettings = SbtIdeaPlugin.settings

    lazy val assemblySettings = SbtAssembly.assemblySettings ++ {
        import SbtAssembly.AssemblyKeys._
        Seq(test in assembly := {},
            jarName in assembly := "IScala.jar",
            target in assembly := target.value / "lib",
            assemblyDirectory in assembly := IO.createTemporaryDirectory,
            assembly <<= (assembly, assemblyDirectory in assembly, streams) map { (outputFile, tmpDir, streams) =>
                streams.log.info(s"Cleaning up $tmpDir")
                IO.delete(tmpDir)
                outputFile
            })
    }

    lazy val proguardSettings = SbtProguard.proguardSettings ++ {
        import SbtProguard.{Proguard,ProguardOptions}
        import SbtProguard.ProguardKeys._
        Seq(javaOptions in (Proguard, proguard) := Seq("-Xmx2G"),
            options in Proguard += "@" + (baseDirectory.value / "project" / "IScala.pro"),
            options in Proguard += ProguardOptions.keepMain(organization.value + ".iscala.IScala"))
    }

    lazy val packagerSettings = SbtNativePackager.packagerSettings ++ {
        import SbtNativePackager.NativePackagerKeys._
        import SbtNativePackager.Universal
        import SbtAssembly.AssemblyKeys.assembly
        Seq(mappings in Universal <++= (assembly, target) map { (jar, target) =>
                jar x relativeTo(target)
            },
            mappings in Universal <++= (userScripts, target) map { (scripts, target) =>
                scripts x relativeTo(target)
            },
            mappings in Universal ++= {
                val paths = Seq("README.md", "LICENSE")
                paths.map(path => (file(path), path))
            },
            name in Universal := s"IScala-${version.value}",
            release <<= packageZipTarball in Universal)
    }

    lazy val pluginSettings = ideaSettings ++ assemblySettings ++ proguardSettings ++ packagerSettings

    lazy val projectSettings = Project.defaultSettings ++ pluginSettings ++ {
        import SbtAssembly.AssemblyKeys.{assembly,jarName}
        Seq(publishMavenStyle := true,
            publishTo := {
                def repo(name: String) = Resolver.file(s"refptr-$name", file("/srv/repo/share") / name)
                Some(if (version.value.endsWith("SNAPSHOT")) repo("snapshots") else repo("releases"))
            },
            libraryDependencies ++= {
                import Dependencies._
                scalaio ++ Seq(ivy, jopt, jeromq, play_json, slick, h2, sqlite, slf4j, specs2, compiler.value)
            },
            fork in run := true,
            initialCommands in Compile := """
                import scala.reflect.runtime.{universe=>u}
                import scala.tools.nsc.interpreter._
                import scalax.io.JavaConverters._
                import scalax.file.Path
                import scala.slick.driver.SQLiteDriver.simple._
                import Database.threadLocalSession
                """,
            ipyCommands := List(
                "console"   -> "--no-banner",
                "qtconsole" -> "",
                "notebook"  -> ""),
            jrebelJar := {
                val jar = Path.userHome / ".jrebel" / "jrebel" / "jrebel.jar"
                if (jar.exists) Some(jar) else None
            },
            jrebelOptions := Seq("-Drebel.load_embedded_plugins=false", "-Drebel.stats=false", "-Drebel.usage_reporting=false"),
            jrebelCommand <<= (jrebelJar, jrebelOptions) map { (jar, options) =>
                jar.map(jar => Seq("-XX:+CMSClassUnloadingEnabled", "-noverify", s"-javaagent:$jar") ++ options) getOrElse Nil
            },
            debugPort := 5005,
            debugCommand <<= (debugPort) map { (port) =>
                Seq("-Xdebug", s"-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$port")
            },
            develScripts <<= (fullClasspath in Compile, mainClass in Compile, baseDirectory, ipyCommands, jrebelCommand, debugCommand, streams) map {
                    (fullClasspath, mainClass, base, commands, jrebel, debug, streams) =>
                val classpath = fullClasspath.files.mkString(java.io.File.pathSeparator)
                val main = mainClass getOrElse sys.error("unknown main class")

                val cmd = Seq("java") ++ jrebel ++ debug ++ Seq("-cp", classpath, main, "--profile", "{connection_file}", "--parent", "$@")
                val kernel_cmd = cmd.map(arg => s"""\\"$arg\\"""").mkString(", ")

                val bin = base / "bin"
                IO.createDirectory(bin)

                commands map { case (command, options) =>
                    val output =
                        s"""
                        |#!/bin/bash
                        |ipython $command --profile scala --KernelManager.kernel_cmd="[$kernel_cmd]" $options
                        """.stripMargin.trim + "\n"
                    val file = bin / command
                    streams.log.info(s"Writing ${file.getPath}")
                    IO.write(file, output)
                    file.setExecutable(true)
                    file
                }
            },
            userScripts <<= (jarName in assembly, target, ipyCommands, streams) map { (jarName, target, commands, streams) =>
                val bin = target / "bin"
                IO.createDirectory(bin)

                commands map { case (command, options) =>
                    val output = s"""
                        |#!/bin/bash
                        |JAR_PATH="$$(dirname $$(dirname $$(readlink -f $$0)))/lib/$jarName"
                        |KERNEL_CMD="[\\"java\\", \\"-jar\\", \\"$$JAR_PATH\\", \\"--profile\\", \\"{connection_file}\\", \\"--parent\\", \\"$$@\\"]"
                        |ipython $command --profile scala --KernelManager.kernel_cmd="$$KERNEL_CMD" $options
                        """.stripMargin.trim + "\n"
                    val file = bin / command
                    streams.log.info(s"Writing ${file.getPath}")
                    IO.write(file, output)
                    file.setExecutable(true)
                    file
                }
            })
    }

    lazy val macrosSettings = Project.defaultSettings ++ Seq(
        addCompilerPlugin("org.scala-lang.plugins" % "macro-paradise" % "2.0.0-SNAPSHOT" cross CrossVersion.full),
        libraryDependencies ++= {
            import Dependencies._
            Seq(play_json, specs2, reflect.value)
        }
    )

    lazy val IScala = Project(id="IScala", base=file("."), settings=projectSettings) dependsOn(Macros)

    lazy val Macros = Project(id="IScala-Macros", base=file("macros"), settings=macrosSettings)

    override def projects = Seq(IScala, Macros)
}
