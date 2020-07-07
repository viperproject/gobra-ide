package viper.gobraserver

import viper.gobra.reporting._
import viper.silver.ast.{ AbstractSourcePosition, Program, Positioned, Node, Stmt, _ }

import com.google.gson.Gson
import org.eclipse.lsp4j.{ Range, Position }

import scala.collection.mutable.ListBuffer

case class PreviewReporter(name: String = "preview_reporter",
                           viperPreview: Boolean = false,
                           selections: List[Range]) extends GobraReporter {

  private val gson = new Gson()
  private val highlightedRanges = ListBuffer[HighlightingPosition]()

  private var vprAstFormatted: String = ""

  private def isHighlighted(position: AbstractSourcePosition): Boolean = {
    val startLine = position.start.line
    val endLine = position.end.getOrElse(position.start).line

    selections.foreach(selection => {
      val selectionStartLine = Helper.startLine(selection) + 1
      val selectionEndLine = Helper.endLine(selection) + 1

      if (startLine >= selectionStartLine && endLine <= selectionEndLine) return true
    })

    false
  }

/*
  private def highlightSeqn(body: Option[Seqn], methodIndex: Int, method: String): Unit = body match {
    case Some(block) => block.deepCollect {case s: Stmt => s}.foreach(b => {
      b.pos match {
        case pos: AbstractSourcePosition if isHighlighted(pos) =>

          val indentedBlock = Helper.indent(method, b.toString())
          val startIndex = method.indexOfSlice(indentedBlock)

          highlightedRanges += HighlightingPosition(methodIndex + startIndex, indentedBlock.length)

        case _ => // ignore
      }
    })
    case None => // ignore
  }

  private def highlightExp(exp: Seq[Exp], methodIndex: Int, method: String): Unit = exp.foreach(e => {
    e.pos match {
      case pos: AbstractSourcePosition if isHighlighted(pos) =>
        val indentedExp = Helper.indent(method, e.toString())
        val startIndex = method.indexOfSlice(indentedExp)

        highlightedRanges += HighlightingPosition(methodIndex + startIndex, indentedExp.length)

      case _ => // ignore
    }
  })
  

  // implement LocalVarDecl, Exp -> these are used for most parts in method, function and predicates. This is then used in the parts below.
  

  private def highlightMember(members: Seq[Node]): Unit = members.foreach(mem => {
    val (position, _, _) = mem.getPrettyMetadata

    position match {
      case pos: AbstractSourcePosition if isHighlighted(pos) =>
        val startIndex = vprAstFormatted.indexOfSlice(mem.toString())
        highlightedRanges += HighlightingPosition(startIndex, mem.toString().length)
      
      case _ => mem match {
        case Method(_, formalArgs, formalReturns, pres, posts, body) =>
          val method = mem.toString()
          val methodIndex = vprAstFormatted.indexOfSlice(method)

          highlightSeqn(body, methodIndex, method)
          highlightExp(pres, methodIndex, method)
          highlightExp(posts, methodIndex, method)

        case Function(_, formalArgs, _, pres, posts, body) => // formalArgs: Seq[LocalVarDecl], pres: Seq[Exp], posts: Seq[Exp], body: Option[Exp]
          val function = mem.toString()
          val functionIndex = vprAstFormatted.indexOfSlice(function)

          highlightExp(pres, functionIndex, function)
          highlightExp(posts, functionIndex, function)
          highlightExp(Helper.optToSeq(body), functionIndex, function)

        case Predicate(_, formalArgs, body) => // formalArgs: Seq[LocalVarDecl], body: Option[Exp]
          val predicate = mem.toString()
          val predicateIndex = vprAstFormatted.indexOfSlice(predicate)

          highlightExp(Helper.optToSeq(body), predicateIndex, predicate)

        case _ => // ignore
      }

    }
  })
*/

  override def report(msg: GobraMessage): Unit = msg match {
    case m@GeneratedViperMessage(file, ast) if viperPreview =>
      val vprAst = ast()

      vprAstFormatted = HighlightingPrettyPrinter.pretty(vprAst)

      val positionStore = HighlightingPrettyPrinter.positionStore

      positionStore.keySet.foreach(key => {
        positionStore.get(key) match {
          case Some(viperPos) =>
            key match {
              case pos: AbstractSourcePosition if isHighlighted(pos) => viperPos.toList.map({case (startPos, length) => highlightedRanges += HighlightingPosition(startPos, length)})
              case _ => // ignore
            }
          case None => // ignore
        }
      })

      

/*
      vprAstFormatted = m.vprAstFormatted

      highlightMember(vprAst.methods)
      highlightMember(vprAst.functions)
      highlightMember(vprAst.predicates)
      highlightMember(vprAst.fields)
      //highlightMember(vprAst.domains)
      //highlightMember(vprAst.extensions)
*/
      VerifierState.client match {
        case Some(c) => c.finishedViperCodePreview(vprAstFormatted, gson.toJson(highlightedRanges.toArray))
        case None =>
      }
    

    case _ => // ignore
  }
}

