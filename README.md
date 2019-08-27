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

``` scala
<tag-name />
```

will be translated to

``` scala
xml.literal(
  xml.elements.`tag-name`()
)
```

#### Node list

``` scala
<tag-name />
<prefix-1:tag-name />
```

will be translated to

``` scala
xml.literal(
  xml.elements.`tag-name`(),
  `prefix-1`.elements.`tag-name`()
)
```
#### Attributes

``` scala
<tag-name attribute-1="value"
          attribute-2={ f() }/>
```

will be translated to

``` scala
xml.literal(
  xml.elements.`tag-name`(
    xml.attributes.`attribute-1`(xml.values.value),
    xml.attributes.`attribute-2`(xml.interpolation(f()))
  )
)
```

#### CDATA

`<![CDATA[raw]]>` will be translated to `xml.literal(xml.texts.raw)` if `-Xxml:coalescing` flag is on, or `xml.literal(xml.cdata("raw"))` if the flag is turned off as `-Xxml:-coalescing` .

#### Process instructions

``` scala
<?xml-stylesheet type="text/xsl" href="style.xsl"?>
```

will be translated to

``` scala
xml.literal(
  xml.processInstructions.`xml-stylesheet`("type=\"text/xsl\" href=\"style.xsl\"")
)
```

#### Child nodes

``` scala
<tag-name attribute-1="value">
  text &amp; &#x68;exadecimal reference &AMP; &#100;ecimal reference
  <child-1/>
  <!-- my comment -->
  { math.random }
  <![CDATA[ raw ]]>
</tag-name>
```

will be translated to

``` scala
xml.literal(
  xml.elements.`tag-name`(
    xml.attributes.`attribute-1`(xml.values.value),
    xml.texts.`$u000A  text `,
    xml.entities.amp,
    xml.texts.` hexadecimal reference `,
    xml.entities.AMP,
    xml.texts.` decimal reference$u000A  `,
    xml.elements.`child-1`(),
    xml.texts.`$u000A  `,
    xml.comment(" my comment "),
    xml.texts.`$u000A  `,
    xml.interpolation(math.random),
    xml.texts.`$u000A  `,
    xml.cdata(" raw "), //  or (xml.texts.` raw `), if `-Xxml:coalescing` flag is set
    xml.texts.`$u000A  `
  )
)
```

Note that hexadecimal references and decimal references will be unescaped and translated to `xml.texts` automatically, while entity references are translated to fields in `xml.entities` .

#### Prefixes without `xmlns` bindings.

``` scala
<prefix-1:tag-name-1 attribute-1="value-1" prefix-2:attribute-2="value-2">
  <tag-name-2>content</tag-name-2>
  <!-- my comment -->
</prefix-1:tag-name-1>
```

will be translated to

``` scala
xml.literal(
  `prefix-1`.elements.`tag-name-1`(
    `prefix-1`.attributes.`attribute-1`(`prefix-1`.values.`value-1`),
    `prefix-2`.attributes.`attribute-2`(`prefix-2`.values.`value-2`),
    `prefix-1`.texts.`$u000A  `,
    xml.elements.`tag-name-2`(
      xml.texts.content
    ),
    `prefix-1`.texts.`$u000A  `,
    `prefix-1`.comment(" my comment "),
    `prefix-1`.texts.`$u000A`
  )
)
```

Note that unprefixed attribute will be treated as if it has the same prefix as its enclosing element.

#### `xmlns` bindings.

``` scala
<prefix-1:tag-name-1 xmlns="http://example.com/0" xmlns:prefix-1="http://example.com/1" xmlns:prefix-2="http://example.com/2" attribute-1="value-1" prefix-2:attribute-2="value-2">
  <tag-name-2>content</tag-name-2>
  <!-- my comment -->
</prefix-1:tag-name-1>
```

will be translated to

``` scala
xml.literal(
  xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).elements.`tag-name-1`(
    xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).attributes.`attribute-1`(xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).values.`value-1`),
    xml.prefixes.`prefix-2`(xml.uris.`http://example.com/2`).attributes.`attribute-2`(xml.prefixes.`prefix-2`(xml.uris.`http://example.com/2`).values.`value-2`),
    xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).texts.`$u000A  `,
    xml.noPrefix(xml.uris.`http://example.com/0`).elements.`tag-name-2`(
      xml.noPrefix(xml.uris.`http://example.com/0`).texts.content
    ),
    xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).texts.`$u000A  `,
    xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).comment(" my comment "),
    xml.prefixes.`prefix-1`(xml.uris.`http://example.com/1`).texts.`$u000A`
  )
)
```

### XML library vendors

An XML library vendor should provide a package or object named `xml` , which contains the following methods or values:

* elements
* attributes
* values
* entities
* processInstructions
* texts
* comment
* cdata
* interpolation
* noPrefix
* prefixes
* uris
* literal

All above methods except `literal` should return a builder, and `literal` will turn one or more builders into an XML object / or an XML node list.

An XML library user can switch different implementations by importing different `xml` packages or objects. `scala.xml` is used by default when no explicit import is present.

In a schema-aware XML library like Binding.scala, its `elements` , `attributes` , `processInstructions` and `entities` methods should return factory objects that contain all the definitions of available tag names and attribute names. An XML library user can provide additional tag names and attribute names in user-defined implicit classes for `tags` and `attributes` .

In a schema-less XML library like `scala-xml` , its `elements` , `attributes` , `processInstructions` and `entities` should return builders that extend [scala.Dynamic](https://www.scala-lang.org/api/current/scala/Dynamic.html) in order to handle tag names and attribute names in `selectDynamic` or `applyDynamic` .

Those XML libraries can be extended with the help of standard XML namespace bindings. A plug-in author can create `implicit class` for `xml.uris` to introduce foreign elements embedded in existing XML literals.

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

## Alternative approach

XML initialization can be implemented in a special string interpolation as `xml"<x/>"`, which can be implemented in a macro library. The pros and cons of these approaches are list in the following table:

||symbol-based XML literals in Scala 2.12|name-based XML literals in this proposal|`xml` string interpolation|
| --- | --- | --- | --- |
|XML is parsed by ...|compiler|compiler|library, IDE, and other code browsers including Github, Jekyll (if syntax highlighting is wanted)|
|Is third-party schema-less XML library supported?|No, unless using white box macros|Yes|Yes|
|Is third-party schema-aware XML library supported?|No, unless using white box macros|Yes|No, unless using white box macros|
|How to highlight XML syntax?|By regular highlighter grammars|By regular highlighter grammars|By special parsing rule for string content|
|Can presentation compiler perform code completion for schema-aware XML literals?|No|Yes|No|

