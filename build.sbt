name := "nameBasedXml"

ThisBuild / organization := "org.lrng.binding"

libraryDependencies += "com.thoughtworks.extractor" %% "extractor" % "2.1.2"

libraryDependencies += "com.thoughtworks.binding" %% "xmlextractor" % "11.8.1+25-746fc092"

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.1.0"

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)

enablePlugins(Example)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % Test