name := "nameBasedXml"

ThisBuild / organization := "org.lrng.binding"

libraryDependencies += "com.thoughtworks.extractor" %% "extractor" % "2.1.2"

libraryDependencies += "com.thoughtworks.binding" %% "xmlextractor" % {
  import Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers >= Seq(2L, 13L)) {
    "12.0.0"
  } else {
    "11.9.0"
  }
}

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.3.0"

// Enable macro annotation by scalac flags for Scala 2.13
scalacOptions ++= {
  import Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers >= Seq(2L, 13L)) {
    Seq("-Ymacro-annotations")
  } else {
    Nil
  }
}

// Enable macro annotation by compiler plugins for Scala 2.12
libraryDependencies ++= {
  import Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers >= Seq(2L, 13L)) {
    Nil
  } else {
    Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full))
  }
}

enablePlugins(Example)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.2" % Test
