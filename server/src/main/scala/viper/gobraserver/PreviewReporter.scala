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

  private def appendPosition(members: Seq[Node], astFormatted: String): Unit = members.foreach(m => {
    val (position, _, _) = m.getPrettyMetadata

    position match {
      case pos: AbstractSourcePosition if isHighlighted(pos) =>
        val startIndex = astFormatted.indexOfSlice(m.toString())
        highlightingPositions += HighlightingPosition(startIndex, m.toString().length)
      
      case _ => // ignore
    }
  })

  private def getPositionHighlighting(ast: Program, astFormatted: String): Array[HighlightingPosition] = {
    
    appendPosition(ast.methods, astFormatted)
    appendPosition(ast.functions, astFormatted)
    appendPosition(ast.predicates, astFormatted)
    
    //TODO: look that these cases work correctly
    //appendPosition(ast.fields, astFormatted)
    //appendPosition(ast.domains, astFormatted)
    //appendPosition(ast.extensions, astFormatted)

    // TODO: make highlighting of submethods, subfunctions, ... possible

    highlightingPositions.toArray
  }

  override def report(msg: GobraMessage): Unit = msg match {
    case m@GeneratedViperMessage(file, ast) if viperPreview =>
      val highlightingPositions = getPositionHighlighting(ast(), m.vprAstFormatted)

      VerifierState.client match {
        case Some(c) => c.finishedViperCodePreview(m.vprAstFormatted, gson.toJson(highlightingPositions))
      }
    

    case _ => // ignore
  }
}