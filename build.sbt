name := "nameBasedXml"

ThisBuild / organization := "org.lrng.binding"

libraryDependencies += "com.thoughtworks.extractor" %% "extractor" % "2.1.2"

libraryDependencies += "com.thoughtworks.binding" %% "xmlextractor" % "11.9.0"

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.2.0"

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)

enablePlugins(Example)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % Test
