package viper.gobraserver

import viper.gobra.reporting._
import viper.silver.ast.{ AbstractSourcePosition, Program, Positioned, Node }

import com.google.gson.Gson
import org.eclipse.lsp4j.{ Range, Position }

import scala.collection.mutable.ListBuffer

case class PreviewReporter(name: String = "preview_reporter",
                           viperPreview: Boolean = false,
                           selections: List[Range]) extends GobraReporter {

  private val gson = new Gson()
  private val highlightingPositions = ListBuffer[HighlightingPosition]()

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

  private def memberHighlighting(members: Seq[Node]): Unit = members.foreach(m => {
    val (position, _, _) = m.getPrettyMetadata

    position match {
      case pos: AbstractSourcePosition if isHighlighted(pos) =>
        val startIndex = vprAstFormatted.indexOfSlice(m.toString())
        highlightingPositions += HighlightingPosition(startIndex, m.toString().length)
      
      case _ => // ignore
    }
  })


  override def report(msg: GobraMessage): Unit = msg match {
    case m@GeneratedViperMessage(file, ast) if viperPreview =>
      val vprAst = ast()
      vprAstFormatted = m.vprAstFormatted

      memberHighlighting(vprAst.methods)
      memberHighlighting(vprAst.functions)
      memberHighlighting(vprAst.predicates)
      memberHighlighting(vprAst.fields)
      memberHighlighting(vprAst.domains)
      memberHighlighting(vprAst.extensions)

      VerifierState.client match {
        case Some(c) => c.finishedViperCodePreview(m.vprAstFormatted, gson.toJson(highlightingPositions.toArray))
        case None =>
      }
    

    case _ => // ignore
  }
}