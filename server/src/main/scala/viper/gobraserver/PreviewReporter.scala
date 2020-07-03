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

  private def highlightBlock(body: Option[Seqn], methodIndex: Int, method: String): Unit = body match {
    case Some(block) => block.deepCollect {case s: Stmt => s}.foreach(b => {
      b.pos match {
        case pos: AbstractSourcePosition if isHighlighted(pos) =>

          val indentedBlock = Helper.indentBlock(method, b.toString())
          val startIndex = method.indexOfSlice(indentedBlock)

          highlightedRanges += HighlightingPosition(methodIndex + startIndex, indentedBlock.length)

        case _ => // ignore
      }
    })
    case None => // ignore
  }

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

          highlightBlock(body, methodIndex, method)

        case Function(_, formalArgs, _, pres, posts, body) => // formalArgs: Seq[LocalVarDecl], pres: Seq[Exp], posts: Seq[Exp], body: Option[Exp]

        case Predicate(_, formalArgs, body) => // formalArgs: Seq[LocalVarDecl], body: Option[Exp]

        case _ => // ignore
      }

    }
  })


  override def report(msg: GobraMessage): Unit = msg match {
    case m@GeneratedViperMessage(file, ast) if viperPreview =>
      val vprAst = ast()
      vprAstFormatted = m.vprAstFormatted

      highlightMember(vprAst.methods)
      highlightMember(vprAst.functions)
      highlightMember(vprAst.predicates)
      highlightMember(vprAst.fields)
      //highlightMember(vprAst.domains)
      //highlightMember(vprAst.extensions)
      

      VerifierState.client match {
        case Some(c) => c.finishedViperCodePreview(m.vprAstFormatted, gson.toJson(highlightedRanges.toArray))
        case None =>
      }
    

    case _ => // ignore
  }
}