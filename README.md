# Name based XML literals
[![Scaladoc](https://javadoc.io/badge2/org.lrng.binding/namebasedxml_2.12/Scaladoc.svg)](https://javadoc.io/page/org.lrng.binding/namebasedxml_2.12/latest/org/lrng/binding/nameBasedXml.html)

This repository contains the implementation for [Name Based XML Literals](https://docs.scala-lang.org/sips/name-based-xml.html).

## Installation

``` sbt
// Enable macro annotations by setting scalac flags for Scala 2.13
scalacOptions ++= {
  import Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers >= Seq(2L, 13L)) {
    Seq("-Ymacro-annotations")
  } else {
    Nil
  }
}

// Enable macro annotations by adding compiler plugins for Scala 2.11 and 2.12
libraryDependencies ++= {
  import Ordering.Implicits._
  if (VersionNumber(scalaVersion.value).numbers >= Seq(2L, 13L)) {
    Nil
  } else {
    Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full))
  }
}

libraryDependencies += "org.lrng.binding" %% "namebasedxml" % "latest.release"
```

## Usage

See examples in [Scaladoc](https://javadoc.io/page/org.lrng.binding/namebasedxml_2.12/latest/org/lrng/binding/nameBasedXml.html).

