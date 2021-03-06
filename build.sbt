organization := "org.scalanlp"

name := "gust"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.5" % "test",
  "org.scalanlp" %% "breeze-macros" % "0.3-SNAPSHOT" % "compile",
  "org.scalanlp" %% "breeze" % "0.7-SNAPSHOT",
  "org.scalatest" %% "scalatest" % "2.0.M5b",
  "com.nativelibs4java" % "javacl" % "1.0-SNAPSHOT",
  "org.scalanlp" % "jcublas2" % "0.5.5",
  "org.scalanlp" % "jcurand" % "0.5.5"
)

fork := true

javaOptions ++= Seq("-Xmx12g")

resolvers ++= Seq(
  "Scala Tools Snapshots" at "http://scala-tools.org/repo-snapshots/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
)


testOptions in Test += Tests.Argument("-oDF")

addCompilerPlugin("org.scala-lang.plugins" % "macro-paradise" % "2.0.0-SNAPSHOT" cross CrossVersion.full)
