package viper.gobraserver

import viper.gobra.reporting._
import viper.silver.ast.{ AbstractSourcePosition, Program }

import com.google.gson.Gson
import org.eclipse.lsp4j.{ Range, Position }

import scala.collection.mutable.ListBuffer

case class PreviewReporter(name: String = "preview_reporter",
                           viperPreview: Boolean = false,
                           selections: List[Range]) extends GobraReporter {

  private val gson = new Gson()

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

  private def getPositionHighlighting(ast: Program, astFormatted: String): Array[HighlightingPosition] = {
    val highlightingPositions = ListBuffer[HighlightingPosition]()

    ast.methods.foreach(method => {
      if (isHighlighted(method.pos.asInstanceOf[AbstractSourcePosition])) {
        val startIndex = astFormatted.indexOfSlice(method.toString())
        highlightingPositions += HighlightingPosition(startIndex, method.toString().length)
      }
    })

    highlightingPositions.toArray
  }

//domains: Seq[Domain], fields: Seq[Field], functions: Seq[Function], predicates: Seq[Predicate], methods: Seq[Method], extensions: Seq[ExtensionMember]

  override def report(msg: GobraMessage): Unit = msg match {
    case m@GeneratedViperMessage(file, ast) if viperPreview =>
      val highlightingPositions = getPositionHighlighting(ast(), m.vprAstFormatted)

      VerifierState.client match {
        case Some(c) => c.finishedViperCodePreview(m.vprAstFormatted, gson.toJson(highlightingPositions))
      }
    

    case _ => // ignore
  }
}