# Name based XML literals

## Background

Name-based `for` comprehension has been proven success in Scala language design. A `for` / `yield` expression will be converted to higher-order function calls to `flatMap` , `map` and `withFilter` methods, no matter which type signatures they are. The `for` comprehension can be used for either `Option` or `List` , even when `List` has an additional implicit `CanBuildFrom` parameter. Third-party libraries like Scalaz and Cats also provides `Ops` to allow monadic data types in `for` comprehension.

[Name-based pattern matching ](http://dotty.epfl.ch/docs/reference/changed/pattern-matching.html) is introduced by Dotty. It is greatly simplified the implementation compared to Scala 2. In addition, specific symbols in Scala library ( `Option` , `Seq` ) are decoupled from the Scala compiler.

Considering the success of the above name-based syntactic sugars, in order to decouple `scala-xml` library from Scala compiler, name-based XML literal is an obvious approach.

## Goals

* Keeping source-level backward compatibility to existing symbol-based XML literals in most use cases of `scala-xml`
* Allowing schema-aware XML literals, i.e. static type varying according to tag names, similar to the current TypeScript and Binding.scala behavior.
* Schema-aware XML literals should be understandable by both the compiler and IDE (e.g. no white box macros involved)
* Existing libraries like ScalaTag should be able to support XML literals by adding a few simple wrapper classes. No macro or metaprogramming knowledge is required for library authors.
* The compiler should expose as less as possible number of special names, in case of being *intolerably ugly* .
* Able to implement an API to build a DOM tree with no more cost than manually written Scala code.

## Non-goals

* Embedding fully-featured standard XML in Scala.
* Allowing arbitrary tag names and attribute names (or avoiding reserved word).
* Distinguishing lexical differences, e.g. `<a b = "&#99;"></a>` vs `<a b="c"/>` .

## The proposal

### Lexical Syntax

Kept unchanged from Scala 2.12

### XML literal translation

Scala compiler will translate XML literal to Scala AST before type checking.
The translation rules are:

#### Self-closing tags without prefixes

```
<tag-name />
```

will be translated to

```
xml.elements.`tag-name`.withoutNodeList.build()
```

#### Self-closing tags with some prefixes

```
<prefix-1:tag-name />
```

will be translated to

```
`prefix-1`.elements.`tag-name`.withoutNodeList.build()
```

#### Attributes

```
<tag-name attribute-1="value"
          attribute-2={ f() }
          prefix-2:attribute-3={"value"} />
```

will be translated to

```
val builder = xml.elements.`tag-name`
  .withAttribute.`attribute-1`("value")
  .withAttribute.`attribute-2`(xml.interpolation(f()))
    
`prefix-2`.withAttribute.`attribute-3`(builder, xml.interpolation("value"))
  .withoutNodeList
  .build()
```

Note that attributes with a prefix becomes function calls on the prefix, and attributes without a prefix becomes method calls on the builder.

#### CDATA

`<![CDATA[ raw ]]>` will be translated to `xml.text(" raw ")` if `-Xxml:coalescing` flag is on, or `xml.cdata(" raw ")` if the flag is turned off as `-Xxml:-coalescing` .

#### Process instructions

```
<?xml-stylesheet type="text/xsl" href="sty.xsl"?>
```

will be translated to

```
xml.processInstructions.`xml-stylesheet`("type=\"text/xsl\" href=\"sty.xsl\"")
```

#### Child nodes

```
<tag-name attribute-1="value">
  text &amp; &#x68;exadecimal reference &AMP; &#100;ecimal reference
  <child-1/>
  <!-- my comment -->
  { math.random }
  <![CDATA[ raw ]]>
</tag-name>
```

will be translated to

```
xml.elements.`tag-name`
  .withAttribute.`attribute-1`(xml.text("value"))
  .withNodeList
    .withChild(xml.text("\n  text "))
    .withChild(xml.entities.amp)
    .withChild(xml.text(" hexadecimal reference "))
    .withChild(xml.entities.AMP)
    .withChild(xml.text(" decimal reference\n  "))
    .withChild(xml.elements.`child-1`.withoutNodeList
    .withChild(xml.text("\n  "))
    .withChild(xml.comment(" my comment "))
    .withChild(xml.text("\n  "))
    .withChild(xml.interpolation(math.random))
    .withChild(xml.text("\n  "))
    .withChild(xml.cdata(" raw ")) // .withChild(xml.text(" raw "))  if `-Xxml:coalescing` flag is set
    .withChild(xml.text("\n  "))
  .build()
```

Note that hexadecimal references and decimal references will be unescaped and translated to `xml.text()` automatically, while entity references are translated to fields in `xml.entities` .

### XML library vendors

An XML library vendor should provide a package or object named `xml` , which contains the following methods or values:

* elements
* entities
* processInstructions
* text
* comment
* cdata
* interpolation

Each of those methods should return a builder, which contains a `build()` method to create an XML object. In addition, builders for elements should contains the following methods or values:

* withAttributes
* withNodeList
* withChild
* withoutNodeList

An XML library user can switch different implementations by importing different `xml` packages or objects. `scala.xml` is used by default when no explicit import is present.

In a schema-aware XML library like Binding.scala, its `elements` , `attributes` , `processInstructions` and `entities` methods should return factory objects that contain all the definitions of available tag names and attribute names. An XML library user can provide additional tag names and attribute names in user-defined implicit classes for `tags` and `attributes` .

In a schema-less XML library like `scala-xml` , its `elements` , `attributes` , `processInstructions` and `entities` should return builders that extend [scala.Dynamic](https://www.scala-lang.org/api/current/scala/Dynamic.html) in order to handle tag names and attribute names in `selectDynamic` or `applyDynamic` .

Those builders can be either mutable or immutable. 
* If a builder object is an immutable case class, each `withXxx` method should return a new builder, by invoking `copy` method of the case class.
* If a builder object is a mutable class, each `withXxx` method should change its internal states and return `this`.
* If a builder object is a value class backed by a mutable DOM node, each `withXxx` method should change the internal mutable node and return `this`. Especially, when all its methods are inlined, it should as efficient as manually written Scala code to create DOM nodes.

### Known issues

#### Name clash

`<toString/>` or `<foo equals="bar"/>` will not compile due to name clash to `Any.toString` and `Any.equals` .

* Compilation error is the desired behavior in a schema-aware XML library as long as `toString` is not a valid name in the schema. Fortunately, unlike JSX, `<div class="foo"></div>` should compile because `class` is a valid method name.
* A schema-less XML library user should instead explicit construct `new Elem("toString")` .

### White space only text

![|20x20](//contributors.scala-lang.org/user_avatar/contributors.scala-lang.org/adowrath/40/618_1.png) Adowrath:

> Should whitespace-only text be preserved, though? I’m asking this because, if it is preserved, this won’t work:
>
>
>
>
>
> ```
> val a = <a>
>   <b/>
> </a>
> a match { case <a><b/></a> => () }
> ```

### Pattern matching

This approach does not support XML pattern matching.

## Alternative approach

XML initialization can be implemented in a special string interpolation as `xml"<x/>"` . The pros and cons of these approaches are list in the following table:

||symbol-based XML literals in Scala 2.12|name-based XML literals in this proposal|`xml` string interpolation|
| --- | --- | --- | --- |
|XML is parsed by ...|compiler|compiler|library, IDE, and other code browsers including Github, Jekyll (if syntax highlighting is wanted)|
|Is third-party schema-less XML library supported?|No, unless using white box macros|Yes|Yes|
|Is third-party schema-aware XML library supported?|No, unless using white box macros|Yes|No, unless using white box macros|
|How to highlight XML syntax?|By regular highlighter grammars|By regular highlighter grammars|By special parsing rule for string content|
|Can presentation compiler perform code completion for schema-aware XML literals?|No|Yes|No|

