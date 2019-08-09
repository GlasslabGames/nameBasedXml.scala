package com.concentricsky

import com.thoughtworks.binding.XmlExtractor
import scala.reflect.macros.whitebox
import scala.annotation._
import scala.language.experimental.macros
import com.thoughtworks.binding.XmlExtractor.PrefixedName
import com.thoughtworks.binding.XmlExtractor.UnprefixedName
import com.thoughtworks.binding.XmlExtractor.QName

object nameBasedXml {

  class Macros(val c: whitebox.Context) extends XmlExtractor {
    import c.universe._

    protected class NameBasedXmlTransformer(defaultPrefix: Tree) extends Transformer {

      protected def tagBuilder(tagName: QName) = {
        tagName match {
          case PrefixedName(prefix, localPart) =>
            q"${Ident(TermName(prefix))}.elements.${TermName(localPart)}"
          case UnprefixedName(localPart) =>
            q"$defaultPrefix.elements.${TermName(localPart)}"
        }
      }

      protected def withAttribute(builder: Tree, attributeName: QName, attributeValue: Tree) = {
        val builderWithAttributeName = attributeName match {
          case PrefixedName(prefix, localPart) =>
            q"${Ident(TermName(prefix))}.withAttribute.${TermName(localPart)}($builder)"
          case UnprefixedName(localPart) =>
            q"$builder.withAttribute.${TermName(localPart)}"
        }
        attributeValue match {
          case Text(textValue) =>
            q"$builderWithAttributeName($defaultPrefix.text($textValue))"
          case expression =>
            q"$builderWithAttributeName(${transformInterpolation(expression)})"
        }
      }

      protected def transformChild: PartialFunction[Tree, Tree] = {
        case Text(value) =>
          q"$defaultPrefix.text($value)"
        case Elem(tagName, attributes, minimizeEmpty, children) =>
          val builder = tagBuilder(tagName)

          val builderWithAttributes = attributes.foldLeft[Tree](builder) {
            case (accumulator, (attributeName, attributeValue)) =>
              withAttribute(accumulator, attributeName, attributeValue)
          }

          if (minimizeEmpty && children.isEmpty) {
            q"$builderWithAttributes.withoutNodeList"
          } else {
            children.foldLeft[Tree](q"$builderWithAttributes.withNodeList")(withChild)
          }
        case NodeBuffer(nodes) =>
          nodes.foldLeft[Tree](q"$defaultPrefix.withNodeList")(withChild)
        case Comment(data) =>
          q"$defaultPrefix.comment($data)"
        case EntityRef(entityName) =>
          q"$defaultPrefix.entities.${TermName(entityName)}"
      }

      protected def transformXml = transformChild.andThen { builderTree =>
        q"$builderTree.build()"
      }

      protected def transformInterpolation(expression: Tree): Tree = {
        q"$defaultPrefix.interpolation(${super.transform(expression)})"
      }

      protected def withChild(builder: Tree, child: Tree): Tree = {
        q"$builder.withChild(${transformChild.applyOrElse(child, transformInterpolation)})"
      }

      override def transform(tree: Tree): Tree = {
        transformXml.applyOrElse(tree, super.transform)
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
  *     case object tagName2 {
  *       def build() = this
  *       def withNodeList = this
  *       def withoutNodeList = this
  *       def apply(child: Any) = this
  *     }
  *   }
  * }
  * object xml {
  *   object elements {
  *     def tagName1 = TagName1()
  *     case class TagName1(
  *       attribute1Option: Option[Any] = None,
  *       attribute2Option: Option[Any] = None,
  *       prefix2Attribute3Option: Option[Any] = None
  *     ) { self =>
  *       def build() = this
  *       def withNodeList = this
  *       def withoutNodeList = this
  *       object withAttribute {
  *         def attribute1(attributeValue: Any) = self.copy(attribute1Option = Some(attributeValue))
  *         def attribute2(attributeValue: Any) = self.copy(attribute2Option = Some(attributeValue))
  *       }
  *     }
  *   }
  *   case class text(value: String)
  *   case class interpolation(expression: Any)
  * }
  *
  * object prefix2 {
  *   object withAttribute {
  *     case class attribute3[A](element: xml.elements.TagName1) {
  *       def apply(attributeValue: Any) = element.copy(prefix2Attribute3Option = Some(attributeValue))
  *     }
  *   }
  * }
  * }}}
  *
  * @example Self-closing tags without prefixes
  * {{{
  * @nameBasedXml
  * def myXml = <tagName1/>
  * myXml should be(xml.elements.tagName1)
  * }}}
  *
  * @example Self-closing tags with some prefixes
  * {{{
  * @nameBasedXml
  * def myXml = <prefix1:tagName2/>
  * myXml should be(prefix1.elements.tagName2)
  * }}}
  *
  * @example Attributes
  * {{{
  * case class f()
  *
  * @nameBasedXml
  * def myXml: xml.elements.TagName1 = <tagName1 attribute1="value"
  *   attribute2={ f() }
  *   prefix2:attribute3={"value"}
  * />
  * myXml should be(
  *   xml.elements.TagName1(
  *     attribute1Option=Some(xml.text("value")),
  *     attribute2Option=Some(xml.interpolation(f())),
  *     prefix2Attribute3Option=Some(xml.interpolation("value")),
  *   )
  * )
  * }}}
  *
  * @see [[https://contributors.scala-lang.org/t/pre-sip-name-based-xml-literals/2175 Pre SIP: name based XML literals]]
  */
@compileTimeOnly("enable macro paradise to expand macro annotations")
class nameBasedXml extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro nameBasedXml.Macros.macroTransform
}
