package com.concentricsky

import com.thoughtworks.Extractor._
import com.thoughtworks.binding.XmlExtractor
import scala.reflect.macros.whitebox
import scala.reflect.NameTransformer.encode
import scala.annotation._
import scala.language.experimental.macros
import com.thoughtworks.binding.XmlExtractor.PrefixedName
import com.thoughtworks.binding.XmlExtractor.UnprefixedName
import com.thoughtworks.binding.XmlExtractor.QName
import scala.xml.NodeBuffer

object nameBasedXml {

  class Macros(val c: whitebox.Context) extends XmlExtractor {
    import c.universe._

    private def encodedTermName(s: String) = TermName(encode(s.replace("$", "$u0024")))

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def nodeBufferStar(child: List[Tree]): List[Tree] = {
      child match {
        case Nil =>
          Nil
        case List(q"""${NodeBuffer(children)}: _*""") =>
          children
      }
    }

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def prefix: PartialFunction[Tree, Option[String]] = {
      case q"null"                      => None
      case Literal(Constant(p: String)) => Some(p)
    }

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private val Prefix = prefix.extract

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def elemWithMetaData: PartialFunction[List[Tree], (QName, List[(QName, Tree)], Boolean, List[Tree])] = {
      case q"var $$md: _root_.scala.xml.MetaData = _root_.scala.xml.Null" +:
            (attributes :+
            q"""
              new _root_.scala.xml.Elem(
                ${Prefix(prefixOption)},
                ${Literal(Constant(localPart: String))},
                $$md, $$scope,
                ${Literal(Constant(minimizeEmpty: Boolean))},
                ..$child
              )
            """) =>
        (QName(prefixOption, localPart), attributes.map {
          case q"""$$md = new _root_.scala.xml.UnprefixedAttribute(${Literal(Constant(key: String))}, $value, $$md)""" =>
            UnprefixedName(key) -> value
          case q"""$$md = new _root_.scala.xml.PrefixedAttribute(${Literal(Constant(pre: String))}, ${Literal(
                Constant(key: String))}, $value, $$md)""" =>
            PrefixedName(pre, key) -> value
        }, minimizeEmpty, nodeBufferStar(child))
      case Seq(
          q"""
            new _root_.scala.xml.Elem(
              ${Prefix(prefixOption)},
              ${Literal(Constant(localPart: String))},
              _root_.scala.xml.Null,
              $$scope,
              ${Literal(Constant(minimizeEmpty: Boolean))},
              ..$child
            )
          """
          ) =>
        (QName(prefixOption, localPart), Nil, minimizeEmpty, nodeBufferStar(child))
    }

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    protected val ElemWithMetaData = elemWithMetaData.extract

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def elemWithNamespaceBindings
      : PartialFunction[Tree,
                        (QName, List[(Option[String] /*prefix*/, Tree)], List[(QName, Tree)], Boolean, List[Tree])] = {
      case q"""{
        var $$tmpscope: _root_.scala.xml.NamespaceBinding = $outerScope;
        ..$xmlnses;
        {
          val $$scope: _root_.scala.xml.NamespaceBinding = $$tmpscope;
          ..${ElemWithMetaData(tagName, attributes, minimizeEmpty, children)}
        }
      }""" =>
        val namespaceBindings = xmlnses.map {
          case q"$tmpscope = new _root_.scala.xml.NamespaceBinding($prefixOrNull, $uri, $$tmpscope);" =>
            val prefixOption = prefixOrNull match {
              case q"null" =>
                None
              case Literal(Constant(prefix: String)) =>
                Some(prefix)
            }
            prefixOption -> uri
        }
        (tagName, namespaceBindings, attributes, minimizeEmpty, children)
      case Block(Nil, q"{..${ElemWithMetaData(tagName, attributes, minimizeEmpty, children)}}") =>
        (tagName, Nil, attributes, minimizeEmpty, children)
    }
    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    protected val ElemWithNamespaceBindings = elemWithNamespaceBindings.extract

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def textUris: PartialFunction[Tree, Seq[Tree]] = {
      case text @ (Text(_) | EntityRef(_))                     => Seq(text)
      case q"null"                                             => Nil
      case NodeBuffer(texts @ ((Text(_) | EntityRef(_)) +: _)) => texts
      case Literal(Constant(data: String))                     => Seq(q"new _root_.scala.xml.Text($data)")
    }

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    protected final val TextUris = textUris.extract

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def textAttributes: PartialFunction[Tree, Seq[Tree]] = {
      case text @ (Text(_) | EntityRef(_))                     => Seq(text)
      case EmptyAttribute()                                    => Nil
      case NodeBuffer(texts @ ((Text(_) | EntityRef(_)) +: _)) => texts
    }

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    protected final val TextAttributes = textAttributes.extract

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private def pcData: PartialFunction[Tree, String] = {
      case q"""
        new _root_.scala.xml.PCData(
          ${Literal(Constant(data: String))}
        )
      """ =>
        data
    }

    // TODO: Move to [[com.thoughtworks.binding.XmlExtractor]]
    private final val PCData = pcData.extract

    protected class NameBasedXmlTransformer(vendors: Map[Option[String], Tree] = Map.empty) extends Transformer {
      protected def transformAttribute(parentVendor: Tree, attributeName: QName, attributeValue: Tree) =
        atPos(attributeValue.pos) {
          val vendor = resolveVendor(parentVendor, attributeName)
          val attributeFunction = q"$vendor.attributes.${encodedTermName(localName(attributeName))}"
          attributeValue match {
            case TextAttributes(textValues) =>
              val transformedTexts = textValues.map(transformAttributeText(vendor))
              q"$attributeFunction(..$transformedTexts)"
            case expression =>
              q"$attributeFunction(${transformInterpolation(vendor, expression)})"
          }
        }

      private def localName(qName: QName) = qName match {
        case PrefixedName(prefix, localPart) =>
          localPart
        case UnprefixedName(localPart) =>
          localPart
      }

      private def getVendor(prefixOption: Option[String]): Tree = {
        vendors.getOrElse(prefixOption, Ident(encodedTermName(prefixOption.getOrElse("xml"))))
      }

      private def resolveVendor(defaultVendor: Tree, qName: QName) = qName match {
        case PrefixedName(prefix, localPart) =>
          getVendor(Some(prefix))
        case UnprefixedName(localPart) =>
          defaultVendor
      }

      protected def transformUriText(factory: Tree): PartialFunction[Tree, Tree] = {
        case tree @ Text(value) =>
          atPos(tree.pos)(q"$factory.uris.${encodedTermName(value)}")
        case tree @ EntityRef(entityName) =>
          atPos(tree.pos)(q"$factory.entities.${encodedTermName(entityName)}")
      }

      protected def transformAttributeText(factory: Tree): PartialFunction[Tree, Tree] = {
        case tree @ Text(value) =>
          atPos(tree.pos)(q"$factory.values.${encodedTermName(value)}")
        case tree @ EntityRef(entityName) =>
          atPos(tree.pos)(q"$factory.entities.${encodedTermName(entityName)}")
      }

      protected def transformChildText(factory: Tree): PartialFunction[Tree, Tree] = {
        case tree @ Text(value) =>
          atPos(tree.pos)(q"$factory.texts.${encodedTermName(value)}")
        case tree @ EntityRef(entityName) =>
          atPos(tree.pos)(q"$factory.entities.${encodedTermName(entityName)}")
      }

      protected def transformNamespaceBinding(parentVendor: Tree, prefixOption: Option[String], tree: Tree) = {
        val prefixFactory = prefixOption match {
          case None =>
            q"$parentVendor.noPrefix"
          case Some(prefix) =>
            q"$parentVendor.prefixes.${encodedTermName(prefix)}"
        }
        tree match {
          case TextUris(textValues) =>
            val transformedTexts = textValues.map(transformUriText(parentVendor))
            q"$prefixFactory(..$transformedTexts)"
          case expression =>
            q"$prefixFactory(${transformInterpolation(parentVendor, expression)})"
        }
      }

      protected def withNamespaces(vendors: Map[Option[String], Tree]) = {
        new NameBasedXmlTransformer(vendors)
      }

      protected def transformElem(tagName: QName, attributes: List[(QName, Tree)], children: List[Tree]): Tree = {
        val vendor = resolveVendor(getVendor(None), tagName)
        val transformedAttributes = attributes.view.reverse.map {
          case (attributeName, attributeValue) =>
            transformAttribute(vendor, attributeName, attributeValue)
        }
        def transformInterpolationWithCurrentParent(tree: Tree) = transformInterpolation(vendor, tree)
        val transformedChildren = children.map {
          transformNode(vendor).applyOrElse(_, transformInterpolationWithCurrentParent)
        }
        q"$vendor.elements.${encodedTermName(localName(tagName))}(..${transformedAttributes.toList}, ..$transformedChildren)"
      }

      protected def transformNode(parentVendor: Tree): PartialFunction[Tree, Tree] = {
        transformChildText(q"$parentVendor").orElse {
          case tree @ ElemWithNamespaceBindings(tagName, namespaceBindings, attributes, minimizeEmpty, children) =>
            val transformer = if (namespaceBindings.isEmpty) {
              this
            } else {
              withNamespaces(vendors ++ namespaceBindings.view.map {
                case (prefixOption, uri) =>
                  prefixOption -> transformNamespaceBinding(parentVendor, prefixOption, uri)
              })
            }
            atPos(tree.pos)(transformer.transformElem(tagName, attributes, children))
          case tree @ PCData(data) =>
            atPos(tree.pos)(q"$parentVendor.cdata($data)")
          case tree @ Comment(data) =>
            atPos(tree.pos)(q"$parentVendor.comment($data)")
          case tree @ ProcInstr(target, data) =>
            atPos(tree.pos)(q"$parentVendor.processInstructions.${encodedTermName(target)}($data)")
        }
      }

      protected def transformRootNode = transformNode(getVendor(None))

      protected def transformLiteral: PartialFunction[Tree, Tree] = {
        case tree @ NodeBuffer(transformRootNode.extract.forall(transformedNodes)) =>
          atPos(tree.pos)(q"${getVendor(None)}.literal(..$transformedNodes)")
        case tree @ transformRootNode.extract(transformedNode) =>
          atPos(tree.pos)(q"${getVendor(None)}.literal($transformedNode)")
      }

      protected def transformInterpolation(factory: Tree, expression: Tree): Tree = {
        atPos(expression.pos)(q"$factory.interpolation(${super.transform(expression)})")
      }

      override def transform(tree: Tree): Tree = {
        transformLiteral.applyOrElse(tree, super.transform)
      }

    }

    protected def transformBody(tree: Tree): Tree = {
      new NameBasedXmlTransformer().transform(tree)
    }

    def macroTransform(annottees: Tree*): Tree = {
      val result = annottees match {
        case Seq(annottee @ DefDef(mods, name, tparams, vparamss, tpt, rhs)) =>
          atPos(annottee.pos) {
            DefDef(mods, name, tparams, vparamss, tpt, transformBody(rhs))
          }
        case Seq(annottee @ ValDef(mods, name, tpt, rhs)) =>
          atPos(annottee.pos) {
            ValDef(mods, name, tpt, transformBody(rhs))
          }
        case _ =>
          c.error(c.enclosingPosition, "Expect def or val")
          annottees.head
      }
      // c.info(c.enclosingPosition, show(result), true)
      result
    }

  }

}

/** This annotation enables name based XML literal.
  *
  * All XML literals in methods that are annotated as [[nameBasedXml]]
  * will be transformed to calls to functions in `xml` object.
  *
  * {{{
  * import scala.language.dynamics
  * object xml {
  *   object texts extends Dynamic {
  *     def selectDynamic(value: String) = value
  *   }
  *   case class interpolation(expression: Any)
  *   object uris {
  *     object http$colon$div$divexample$u002Ecom$divmy$minusnamespace$minus1 {
  *       object elements {
  *         case class tagName2(attributesAndChildren: Any*)
  *       }
  *     }
  *     object http$colon$div$divexample$u002Ecom$divmy$minusnamespace$minus2 {
  *       object attributes {
  *         case class attribute3(attributeValue: Any)
  *       }
  *       case class interpolation(expression: Any)
  *     }
  *   }
  *   @inline def noPrefix[Uri](uri: Uri) = uri
  *   object prefixes extends Dynamic {
  *     @inline def applyDynamic[Uri](prefix: String)(uri: Uri) = uri
  *   }
  *   case class literal[A](a: A*)
  *   object elements {
  *     case class tagName1(attributesAndChildren: Any*)
  *   }
  *   object attributes {
  *     case class attribute1(attributeValue: Any)
  *     case class attribute2(attributeValue: Any)
  *   }
  *   object values extends Dynamic {
  *     def selectDynamic(value: String) = value
  *   }
  * }
  *
  * }}}
  *
  * @example Self-closing tags without prefixes
  * {{{
  * @nameBasedXml
  * def myXml = <tagName1/>
  * myXml should be(xml.literal(xml.elements.tagName1()))
  * }}}
  *
  * @example Self-closing tags with some prefixes
  * {{{
  * @nameBasedXml
  * def myXml = <prefix1:tagName2 xmlns:prefix1="http://example.com/my-namespace-1"/>
  * myXml should be(xml.literal(xml.prefixes.prefix1(xml.uris.http$colon$div$divexample$u002Ecom$divmy$minusnamespace$minus1).elements.tagName2()))
  * }}}
  *
  * @example Node list
  * {{{
  * @nameBasedXml
  * def myXml = <tagName1/><prefix1:tagName2 xmlns:prefix1="http://example.com/my-namespace-1"/>
  * myXml should be(xml.literal(xml.elements.tagName1(), xml.prefixes.prefix1(xml.uris.http$colon$div$divexample$u002Ecom$divmy$minusnamespace$minus1).elements.tagName2()))
  * }}}
  * @example Attributes
  * {{{
  * case class f()
  *
  * @nameBasedXml
  * def myXml = <tagName1 xmlns:prefix2="http://example.com/my-namespace-2" attribute1="special character: `"
  *   attribute2={ f() }
  *   prefix2:attribute3={"value"}
  * />
  * myXml should be(xml.literal(
  *   xml.elements.tagName1(
  *     xml.attributes.attribute1("special character: `"),
  *     xml.attributes.attribute2(xml.interpolation(f())),
  *     xml.uris.http$colon$div$divexample$u002Ecom$divmy$minusnamespace$minus2.attributes.attribute3(xml.uris.http$colon$div$divexample$u002Ecom$divmy$minusnamespace$minus2.interpolation("value"))
  *   )
  * ))
  * }}}
  *
  * @see [[https://contributors.scala-lang.org/t/pre-sip-name-based-xml-literals/2175 Pre SIP: name based XML literals]]
  */
@compileTimeOnly("enable macro paradise to expand macro annotations")
class nameBasedXml extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro nameBasedXml.Macros.macroTransform
}
