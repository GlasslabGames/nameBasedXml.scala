package org.lrng

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
          case tree @ Element(tagName, namespaceBindings, attributes, minimizeEmpty, children) =>
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
