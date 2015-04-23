/**
 * This file is part of objc2swift. 
 * https://github.com/yahoojapan/objc2swift
 * 
 * Copyright (c) 2015 Yahoo Japan Corporation
 * 
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

import ObjCParser._
import org.antlr.v4.runtime.{CommonToken, ParserRuleContext}
import org.antlr.v4.runtime.tree.{TerminalNode, ParseTree, ParseTreeProperty}
import collection.JavaConversions._

class ObjC2SwiftConverter(_root: Translation_unitContext) extends ObjCBaseVisitor[String] with MethodVisitor {

  val root = _root
  val visited = new ParseTreeProperty[Boolean]()
  val indentString = " " * 4

  def getResult: String = visit(root)

  def concatChildResults(node: ParseTree, glue: String): String = {
    val children = for(i <- 0 until node.getChildCount) yield node.getChild(i)
    concatResults(children.toList, glue)
  }

  def concatResults(nodes: List[ParseTree], glue: String): String =
    nodes.map(visit).filter(_ != null).mkString(glue)

  def indentLevel(node: ParserRuleContext): Int = {
    node.depth() match {
      case n if n < 4 => 0 // class
      case n if n < 8 => 1 // method
      case _ => 2 // TODO count number of compound_statement's
    }
  }

  def indent(node: ParserRuleContext): String = {
    indentString * indentLevel(node)
  }

  def findCorrespondingClassImplementation(classCtx: Class_interfaceContext): Class_implementationContext = {
    val list = root.external_declaration
    for (extDclCtx <- list) {
      for (ctx <- extDclCtx.children if ctx.isInstanceOf[Class_implementationContext]) {
        val implCtx = ctx.asInstanceOf[Class_implementationContext]
        if(implCtx.class_name.getText == classCtx.class_name.getText)
          return implCtx
      }
    }

    null
  }

  def findCorrespondingMethodDefinition(declCtx: Instance_method_declarationContext): Option[Instance_method_definitionContext] = {
    val classCtx = declCtx.parent.parent.asInstanceOf[Class_interfaceContext]
    Option(findCorrespondingClassImplementation(classCtx)) match {
      case None =>
      case Some(implCtx) =>
        for (ctx <- implCtx.implementation_definition_list.children if ctx.isInstanceOf[Instance_method_definitionContext]) {
          val defCtx = ctx.asInstanceOf[Instance_method_definitionContext]
          if (defCtx.method_definition.method_selector.getText == declCtx.method_declaration.method_selector.getText) {
            return Some(defCtx)
          }
        }
    }
    None
  }

  def visitUnaryOperator(ctx: ParserRuleContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => sb.append(symbol.getSymbol.getText)
        case _ => sb.append(visit(element))
      }
    }

    sb.toString()
  }

  def visitBinaryOperator(ctx: ParserRuleContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => sb.append(" " + symbol.getSymbol.getText + " ")
        case _ => sb.append(visit(element))
      }
    }

    sb.toString()
  }

  override def visitTranslation_unit(ctx: Translation_unitContext): String = {
    concatChildResults(ctx, "")
  }

  override def visitExternal_declaration(ctx: External_declarationContext): String = {
    concatChildResults(ctx, "\n\n")
  }

  override def visitDeclaration(ctx: DeclarationContext): String = {
    //TODO convert Swift grammer (dump objective-c grammer now)

    val sb = new StringBuilder()
    sb.append(indent(ctx))

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case ";" => sb.append("\n")
            case _ => null
          }
        }
        case _ => sb.append(visit(element) + " ")
      }
    }

    sb.toString()
  }

  override def visitDeclarator(ctx: DeclaratorContext): String = {
    concatChildResults(ctx, "")
  }

  override def visitDirect_declarator(ctx: Direct_declaratorContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case "(" | ")" => sb.append(symbol.getSymbol.getText)
            case _ => null
          }
        }
        case _ => sb.append(visit(element))
      }
    }

    sb.toString()
  }

  override def visitClass_interface(ctx: Class_interfaceContext): String = {
    val sb = new StringBuilder()
    sb.append("class " + ctx.class_name.getText)

    if(ctx.superclass_name() != null) {
      sb.append(" : ")
      sb.append(ctx.superclass_name().getText)
    }
    if(ctx.protocol_reference_list() != null) {
      val protocols = ctx.protocol_reference_list()
        .protocol_list()
        .children
        .filter(_.isInstanceOf[Protocol_nameContext])
        .map(_.getText)
      sb.append(", " + protocols.mkString(", "))
    }

    sb.append(" {\n")
    if(ctx.interface_declaration_list() != null) {
      val result = visit(ctx.interface_declaration_list())
      if(result != null) {
        sb.append(result)
      }
    }

    val implCtx = findCorrespondingClassImplementation(ctx)
    if(implCtx != null) {
      val result = visit(implCtx.implementation_definition_list)
      if(result != null) {
        sb.append(result)
      }
    }

    sb.append("}")

    sb.toString()
  }

  override def visitCategory_interface(ctx: Category_interfaceContext): String = {
    val sb = new StringBuilder()
    sb.append("extension " + ctx.class_name.getText)

    if(ctx.protocol_reference_list() != null) {
      val protocols = ctx.protocol_reference_list()
        .protocol_list()
        .children
        .filter(_.isInstanceOf[Protocol_nameContext])
        .map(_.getText)
      sb.append(" : " + protocols.mkString(", "))
    }

    sb.append(" {\n")
    if(ctx.interface_declaration_list() != null) {
      val result = visit(ctx.interface_declaration_list())
      if(result != null) {
        sb.append(result)
      }
    }
    sb.append("}")

    sb.toString()
  }

  override def visitInterface_declaration_list(ctx: Interface_declaration_listContext): String = {
    concatChildResults(ctx, "\n")
  }

  /**
   * Convert Objective-C type to Swift type.
   *
   * @param ctx the parse tree
   * @return Swift type strings
   *
   * TODO: Implement other types
   *
   */
  override def visitType_specifier(ctx: Type_specifierContext): String =
    ctx.getText match {
      case "void"   => "void"
      case "id"     => "AnyObject"
      case "short"  => "Int8"
      case "int"    => "Int32"
      case "long"   => "Int32"
      case "float"  => "Float"
      case "double" => "Double"
      case s if s != "" => s
      case _ => "AnyObject"
    }

  override def visitClass_implementation(ctx: Class_implementationContext): String = {
    concatChildResults(ctx, "")
  }

  override def visitImplementation_definition_list(ctx: Implementation_definition_listContext): String = {
    concatChildResults(ctx, "")
  }


  override def visitCompound_statement(ctx: Compound_statementContext): String = {
    concatChildResults(ctx, "")
  }

  override def visitStatement_list(ctx: Statement_listContext): String = {
    concatChildResults(ctx, "\n")
  }

  override def visitStatement(ctx: StatementContext): String = {
    indent(ctx) + concatChildResults(ctx, " ") // TODO
  }


  override def visitJump_statement(ctx: Jump_statementContext): String = {
    ctx.getChild(0).getText match {
      case "return" => "return " + visit(ctx.expression)
      case "break" => "" // TODO not implemented
      case _ => "" // TODO
    }
  }

  /*
     * Expressions
     */
  override def visitMessage_expression(ctx: Message_expressionContext): String = {
    val sel = ctx.message_selector()

    val sb = new StringBuilder()
    sb.append(visit(ctx.receiver))
    sb.append(".")

    if(sel.keyword_argument.length == 0) { // no argument
      sb.append(sel.selector.getText + "()")
    } else {
      for (i <- 0 until sel.keyword_argument.length) {
        val arg = sel.keyword_argument(i)
        if(i > 0)
          sb.append(", ")

        if(i == 0) {
          sb.append(arg.selector().getText)
          sb.append("(")
          sb.append(visit(arg.expression))
        } else {
          sb.append(arg.selector().getText + ": ")
          sb.append(visit(arg.expression))
        }
      }
      sb.append(")")
    }
    sb.toString()
  }

  override def visitPrimary_expression(ctx: Primary_expressionContext): String = {
    // TODO need to support more

    if(ctx.message_expression != null)
      return visit(ctx.message_expression)

    if(ctx.STRING_LITERAL != null)
      return ctx.STRING_LITERAL().getText.substring(1)

    ctx.getText
  }

  override def visitExpression(ctx: ExpressionContext): String = {
    concatChildResults(ctx, "")
  }

  override def visitSelection_statement(ctx: Selection_statementContext): String = {
    val sb = new StringBuilder()
    var statement_kind = ""

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case s if s == "if" || s == "switch" => {
              sb.append(s)
              statement_kind = s
            }
            case "(" | ")" => sb.append(" ")
            case _ => null
          }
        }
        case expression: ExpressionContext => {
          sb.append(visit(expression))
        }
        case statement: StatementContext => {
          sb.append("{\n")

          if (statement_kind == "if") {
            sb.append(indentString)
          }
          sb.append(visitChildren(statement) + "\n")
          sb.append(indent(statement) +  "}\n")
        }
        case _ => null
      }
    }

    sb.toString()
  }

  override def visitFor_in_statement(ctx: For_in_statementContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case "for" => sb.append("for")
            case "in" => sb.append(" in ")
            case "(" | ")" => sb.append(" ")
            case _ => null
          }
        }
        case expression: ExpressionContext => {
          sb.append(visit(expression))
        }
        case statement: StatementContext => {
          sb.append("{\n")
          sb.append(indentString + visitChildren(statement) + "\n")
          sb.append(indent(statement) +  "}\n")
        }
        case typeVariable: Type_variable_declaratorContext => {
          sb.append(visit(typeVariable))
        }
        case _ => null
      }
    }
    sb.toString()
  }

  override def visitFor_statement(ctx: For_statementContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case "for" => sb.append("for")
            case "(" | ")" => sb.append(" ")
            case ";" => sb.append("; ")
            case _ => null
          }
        }
        case expression: ExpressionContext => {
          sb.append(visit(expression))
        }
        case statement: StatementContext => {
          sb.append("{\n")
          sb.append(indentString + visitChildren(statement) + "\n")
          sb.append(indent(statement) +  "}\n")
        }
        case _ => null
      }
    }
    sb.toString()
  }

  override def visitWhile_statement(ctx: While_statementContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case "while" => sb.append("while")
            case "(" | ")" => sb.append(" ")
            case _ => null
          }
        }
        case expression: ExpressionContext => {
          sb.append(visit(expression))
        }
        case statement: StatementContext => {
          sb.append("{\n")
          sb.append(indentString + visitChildren(statement) + "\n")
          sb.append(indent(statement) +  "}\n")
        }
        case _ => null
      }
    }
    sb.toString()
  }

  override def visitDo_statement(ctx: Do_statementContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case "do" => sb.append("do ")
            case "while" => sb.append("while")
            case "(" | ")" => sb.append(" ")
            case _ => null
          }
        }
        case expression: ExpressionContext => {
          sb.append(visit(expression))
        }
        case statement: StatementContext => {
          sb.append("{\n")
          sb.append(indentString + visitChildren(statement) + "\n")
          sb.append(indent(statement) +  "} ")
        }
        case _ => null
      }
    }
    sb.toString()
  }

  override def visitLabeled_statement(ctx: Labeled_statementContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      element match {
        case symbol: TerminalNode => {
          symbol.getSymbol.getText match {
            case "case" => sb.append("case ")
            case "default" => sb.append("default")
            case ":" => sb.append(":\n" + indentString)
            case _ => null
          }
        }
        case _ => sb.append(visit(element))
        //TODO fix indent bug
      }
    }
    sb.toString()
  }

  override def visitType_variable_declarator(ctx: Type_variable_declaratorContext): String = {
    concatChildResults(ctx, " ")
  }

  override def visitInit_declarator(ctx: Init_declaratorContext): String = {
    visitBinaryOperator(ctx)
  }

  override def visitEquality_expression(ctx: Equality_expressionContext): String = {
    visitBinaryOperator(ctx)
  }

  override def visitRelational_expression(ctx: Relational_expressionContext): String = {
    visitBinaryOperator(ctx)

  }

  override def visitLogical_or_expression(ctx: Logical_or_expressionContext): String = {
    visitBinaryOperator(ctx)
  }

  override def visitLogical_and_expression(ctx: Logical_and_expressionContext): String = {
    visitBinaryOperator(ctx)

  }

  override def visitAdditive_expression(ctx: Additive_expressionContext): String = {
    visitBinaryOperator(ctx)
  }

  override def visitMultiplicative_expression(ctx: Multiplicative_expressionContext): String = {
    visitBinaryOperator(ctx)
  }

  override def visitUnary_expression(ctx: Unary_expressionContext): String = {
    visitUnaryOperator(ctx)
  }

  override def visitPostfix_expression(ctx: Postfix_expressionContext): String = {
    visitUnaryOperator(ctx)
  }

  override def visitAssignment_expression(ctx: Assignment_expressionContext): String = {
    concatChildResults(ctx, " ")
  }

  override def visitAssignment_operator(ctx: Assignment_operatorContext): String = {
    ctx.getText
  }

  override def visitPointer(ctx: PointerContext): String = {
    val sb = new StringBuilder()

    for (element <- ctx.children) {
      if (! element.isInstanceOf[TerminalNode]) sb.append(visit(element))
    }
    sb.toString()
  }

  override def visitIdentifier(ctx: IdentifierContext): String = {
    ctx.getText
  }

  override def visitConstant(ctx: ConstantContext): String = {
    ctx.getText
  }
  
}
