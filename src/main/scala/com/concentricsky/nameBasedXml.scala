package com.concentricsky

import com.thoughtworks.Extractor._
import com.thoughtworks.binding.XmlExtractor
import scala.reflect.macros.whitebox
import scala.annotation._
import scala.language.experimental.macros
import com.thoughtworks.binding.XmlExtractor.PrefixedName
import com.thoughtworks.binding.XmlExtractor.UnprefixedName
import com.thoughtworks.binding.XmlExtractor.QName
import scala.xml.NodeBuffer

object nameBasedXml {

  class Macros(val c: whitebox.Context) extends XmlExtractor {
    import c.universe._

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

    protected class NameBasedXmlTransformer(defaultPrefix: Tree) extends Transformer {

      protected def transformAttribute(parentPrefix: Tree, attributeName: QName, attributeValue: Tree) =
        atPos(attributeValue.pos) {
          val attributeFunction =
            q"${prefixTree(parentPrefix, attributeName)}.attributes.${TermName(localName(attributeName))}"
          attributeValue match {
            case TextAttributes(textValues) =>
              val transformedTexts = textValues.map(transformText(parentPrefix))
              q"$attributeFunction(..$transformedTexts)"
            case expression =>
              q"$attributeFunction(${transformInterpolation(parentPrefix, expression)})"
          }
        }

      private def localName(qName: QName) = qName match {
        case PrefixedName(prefix, localPart) =>
          localPart
        case UnprefixedName(localPart) =>
          localPart
      }

      private def prefixTree(defaultPrefix: Tree, qName: QName) = qName match {
        case PrefixedName(prefix, localPart) =>
          Ident(TermName(prefix))
        case UnprefixedName(localPart) =>
          defaultPrefix
      }
      protected def transformText(parentPrefix: Tree): PartialFunction[Tree, Tree] = {
        case tree @ Text(value) =>
          atPos(tree.pos)(q"$parentPrefix.text($value)")
        case tree @ EntityRef(entityName) =>
          atPos(tree.pos)(q"$parentPrefix.entities.${TermName(entityName)}")
      }

      protected def transformNode(parentPrefix: Tree): PartialFunction[Tree, Tree] = {
        transformText(parentPrefix).orElse {
          case tree @ Elem(tagName, attributes, minimizeEmpty, children) =>
            val prefix = prefixTree(defaultPrefix, tagName)
            val factory = q"$prefix.elements.${TermName(localName(tagName))}"

            val transformedAttributes = attributes.view.reverse.map {
              case (attributeName, attributeValue) =>
                transformAttribute(parentPrefix, attributeName, attributeValue)
            }

            def transformInterpolationWithCurrentParent(tree: Tree) = transformInterpolation(parentPrefix, tree)

            val transformedChildren = children.map {
              transformNode(parentPrefix).applyOrElse(_, transformInterpolationWithCurrentParent)
            }

            atPos(tree.pos)(q"$factory(..${transformedAttributes.toList}, ..$transformedChildren)")
          case tree @ PCData(data) =>
            atPos(tree.pos)(q"$parentPrefix.cdata($data)")
          case tree @ Comment(data) =>
            atPos(tree.pos)(q"$parentPrefix.comment($data)")
          case tree @ ProcInstr(target, data) =>
            atPos(tree.pos)(q"$parentPrefix.processInstructions.${TermName(target)}($data)")
        }
      }

      protected def transformRootNode = transformNode(defaultPrefix)

      protected def transformLiteral: PartialFunction[Tree, Tree] = {
        case tree @ NodeBuffer(transformRootNode.extract.forall(transformedNodes)) =>
          atPos(tree.pos)(q"$defaultPrefix.literal(..$transformedNodes)")
        case tree @ transformRootNode.extract(transformedNode) =>
          atPos(tree.pos)(q"$defaultPrefix.literal($transformedNode)")
      }

      protected def transformInterpolation(parentPrefix: Tree, expression: Tree): Tree = {
        atPos(expression.pos)(q"$parentPrefix.interpolation(${super.transform(expression)})")
      }

      override def transform(tree: Tree): Tree = {
        transformLiteral.applyOrElse(tree, super.transform)
      }

      protected def withDefaultPrefix(newPrefix: Tree) = new NameBasedXmlTransformer(newPrefix)

    }

    protected def transformBody(tree: Tree): Tree = {
      new NameBasedXmlTransformer(q"xml").transform(tree)
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
  * object prefix1 {
  *   object withAttribute {
  *     case class attribute1(attributeValue: Any)
  *   }
  *   object elements {
  *     case class tagName2(attributesAndChildren: Any*)
  *   }
  * }
  * object xml {
  *   case class literal[A](a: A*)
  *   object elements {
  *     case class tagName1(attributesAndChildren: Any*)
  *   }
  *   object attributes {
  *     case class attribute1(attributeValue: Any)
  *     case class attribute2(attributeValue: Any)
  *   }
  *   case class text(value: String)
  *   case class interpolation(expression: Any)
  * }
  *
  * object prefix2 {
  *   object attributes {
  *     case class attribute3(attributeValue: Any)
  *   }
  * }
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
  * def myXml = <prefix1:tagName2/>
  * myXml should be(xml.literal(prefix1.elements.tagName2()))
  * }}}
  *
  * @example Node list
  * {{{
  * @nameBasedXml
  * def myXml = <tagName1/><prefix1:tagName2/>
  * myXml should be(xml.literal(xml.elements.tagName1(), prefix1.elements.tagName2()))
  * }}}
  * @example Attributes
  * {{{
  * case class f()
  *
  * @nameBasedXml
  * def myXml = <tagName1 attribute1="value"
  *   attribute2={ f() }
  *   prefix2:attribute3={"value"}
  * />
  * myXml should be(xml.literal(
  *   xml.elements.tagName1(
  *     xml.attributes.attribute1(xml.text("value")),
  *     xml.attributes.attribute2(xml.interpolation(f())),
  *     prefix2.attributes.attribute3(xml.interpolation("value"))
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
