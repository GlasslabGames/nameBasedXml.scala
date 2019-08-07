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
        val tagFunction = tagName match {
          case PrefixedName(prefix, localPart) =>
            q"${Ident(TermName(prefix))}.${TermName(localPart)}"
          case UnprefixedName(localPart) => 
            q"$defaultPrefix.${TermName(localPart)}"
        }
        q"$tagFunction()"
      }

      protected def addAttribute(builder: Tree, attributeName: QName, attributeValue: Tree) = {
        val builderWithAttributeName = attributeName match {
          case PrefixedName(prefix, localPart) =>
            q"${Ident(TermName(prefix))}.${TermName(localPart)}($builder)"
          case UnprefixedName(localPart) =>
            q"$builder.${TermName(localPart)}"
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
              addAttribute(accumulator, attributeName, attributeValue)
          }

          if (minimizeEmpty && children.isEmpty) {
            q"$builderWithAttributes.selfClose()"
          } else {
            children.foldLeft[Tree](q"$builderWithAttributes.nodeList")(addChild)
          }
        case NodeBuffer(nodes) =>
          nodes.foldLeft[Tree](q"$defaultPrefix.nodeList")(addChild)
      }

      protected def transformXml = transformChild.andThen{ builderTree =>
        q"$builderTree.build()"
      }

      protected def transformInterpolation(expression: Tree): Tree = {
        q"$defaultPrefix.interpolation(${super.transform(expression)})"
      }

      protected def addChild(builder: Tree, child: Tree): Tree = {
        q"$builder(${transformChild.applyOrElse(child, transformInterpolation)})"
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
        case Seq(annottee@DefDef(mods, name, tparams, vparamss, tpt, rhs)) =>
          atPos(annottee.pos) {
            DefDef(mods, name, tparams, vparamss, tpt, transformBody(rhs))
          }
        case Seq(annottee@ValDef(mods, name, tpt, rhs)) =>
          atPos(annottee.pos) {
            ValDef(mods, name, tpt, transformBody(rhs))
          }
        case _ =>
          c.error(c.enclosingPosition, "Expect def or val")
          annottees.head
      }
         c.info(c.enclosingPosition, show(result), true)
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
  *   case class attribute1(attributeValue: Any)
  *   case class tagName2() {
  *     def render() = this
  *     def nodeList = this
  *     def selfClose() = this
  *     def apply(child: Any) = this
  *   }
  * }
  * object xml {
  *   case class tagName1(
  *     attribute1Option: Option[Any] = None,
  *     attribute2Option: Option[Any] = None,
  *     prefix2Attribute3Option: Option[Any] = None
  * ) {
  *     def render() = this
  *     def nodeList = this
  *     def selfClose() = this
  *     def attribute1(attributeValue: Any) = copy(attribute1Option = Some(attributeValue))
  *     def attribute2(attributeValue: Any) = copy(attribute2Option = Some(attributeValue))
  *   }
  *   case class text(value: String)
  *   case class interpolation(expression: Any)
  * }
  * 
  * object prefix2 {
  *   case class attribute3[A](element: xml.tagName1) {
  *     def apply(attributeValue: Any) = element.copy(prefix2Attribute3Option = Some(attributeValue))
  *   }
  * }
  * }}}
  * 
  * @example Self-closing tags without prefixes 
  * {{{
  * @nameBasedXml
  * def myXml = <tagName1/>
  * myXml should be(xml.tagName1())
  * }}}
  * 
  * @example Self-closing tags with some prefixes
  * {{{
  * @nameBasedXml
  * def myXml = <prefix1:tagName2/>
  * myXml should be(prefix1.tagName2())
  * }}}
  * 
  * @example Attributes
  * {{{
  * case class f()
  * 
  * @nameBasedXml
  * def myXml: xml.tagName1 = <tagName1 attribute1="value"
  *   attribute2={ f() }
  *   prefix2:attribute3={"value"}
  * />
  * myXml should be(
  *   xml.tagName1(
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
