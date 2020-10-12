import sbtcrossproject.CrossPlugin.autoImport.crossProject

lazy val scala211 = "2.11.12"
lazy val scala212 = "2.12.12"
lazy val scala213 = "2.13.3"

lazy val dotty = "0.27.0-RC1"

lazy val scalatestVersion = "3.2.2"

name := "biginteger"
organization in ThisBuild := "ky.korins"
version in ThisBuild := "1.0.0-SNAPSHOT"
scalaVersion in ThisBuild := dotty
crossScalaVersions in ThisBuild := Seq()

scalacOptions in ThisBuild ++= Seq(
  "-target:jvm-1.8",
  "-unchecked",
  "-deprecation"
)

// This code isn't ready to publishing yet
publishTo in ThisBuild := None // sonatypePublishToBundle.value

lazy val biginteger = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(disableDottyDocs)
  .settings(
    skip in publish := false,
    publishArtifact in Test := false,
    buildInfoKeys := Seq(
      BuildInfoKey.action("commit") {
        scala.sys.process.Process("git rev-parse HEAD").!!.trim
      }
    ),
    buildInfoPackage := "ky.korins.math",
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % scalatestVersion % Test,
    )
  )
  .jvmSettings(
    scalaVersion := dotty,
    crossScalaVersions := Seq(scala212, scala211, scala213, dotty)
  )
  .jsSettings(
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala211, scala212, scala213)
  )
  .nativeSettings(
    scalaVersion := scala211,
    crossScalaVersions := Seq(scala211),
    nativeLinkStubs := true
  )

lazy val bench = project.in(file("bench"))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(JmhPlugin)
  .dependsOn(biginteger.jvm)
  .settings(disableDottyDocs)
  .settings(
    libraryDependencies ++= Seq(
      "org.openjdk.jmh" % "jmh-core" % "1.25",
      "org.openjdk.jmh" % "jmh-generator-annprocess" % "1.25",
    ),
    skip in publish := true,
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala213),
    assemblyJarName in assembly := "bench.jar",
    mainClass in assembly := Some("org.openjdk.jmh.Main"),
    test in assembly := {},
    javacOptions ++= Seq("-source", "9"),
    scalacOptions ++= Seq(
      "-opt:_",
      "-Xlint:_,-nonlocal-return,-unit-special",
    ),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case _ => MergeStrategy.first
    },
    assembly in Jmh := (assembly in Jmh).dependsOn(Keys.compile in Jmh).value
  )

// Dotty has at least two bugs in docs generation:
//  - it copies whole project to _site
//  - it creates empty javadocs artifact.
// Details: https://github.com/lampepfl/dotty/issues/8769
// Let disable it
lazy val disableDottyDocs = Seq(
  sources in (Compile, doc) := {
    if (isDotty.value) Seq() else (sources in (Compile, doc)).value
  }
)