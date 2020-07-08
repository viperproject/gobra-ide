package viper.gobraserver

import viper.gobra.reporting._
import viper.silver.ast.{ AbstractSourcePosition, Node, Position => GobraPosition, _ }
import viper.gobra.ast.internal.Node

import com.google.gson.Gson
import org.eclipse.lsp4j.Range

import scala.collection.mutable.{ ListBuffer, Map }

case class PreviewReporter(name: String = "preview_reporter",
                           internalPreview: Boolean = false,
                           viperPreview: Boolean = false,
                           selections: List[Range]) extends GobraReporter {

  type ViperPosition = (Int, Int)
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


  private def getHighlightedRanges(positionStore: Map[GobraPosition, ListBuffer[ViperPosition]]): ListBuffer[HighlightingPosition] = {
    val highlighted = ListBuffer[HighlightingPosition]()

    positionStore.toList.map({case (gobraPos, viperPos) =>
      gobraPos match {
        case pos: AbstractSourcePosition if isHighlighted(pos) =>
          viperPos.toList.map({case (startPos, length) => highlighted += HighlightingPosition(startPos, length)})
        case _ => // ignore
      }
    })

    highlighted
  }

  override def report(msg: GobraMessage): Unit = msg match {
    case DesugaredMessage(file, internal) if internalPreview =>
      val internalFormatted = internal().formatted
      val positionStore = Node.defaultPrettyPrinter.positionStore
      println(positionStore)

      val highlightedRanges = getHighlightedRanges(positionStore)

      VerifierState.client match {
        case Some(c) => c.finishedInternalCodePreview(internalFormatted, gson.toJson(highlightedRanges.toArray))
        case None =>
      }


    case GeneratedViperMessage(file, ast) if viperPreview =>
      val vprAstFormatted = HighlightingPrettyPrinter.pretty(ast())
      val positionStore = HighlightingPrettyPrinter.positionStore

      val highlightedRanges = getHighlightedRanges(positionStore)

      VerifierState.client match {
        case Some(c) => c.finishedViperCodePreview(vprAstFormatted, gson.toJson(highlightedRanges.toArray))
        case None =>
      }

    
    

    case _ => // ignore
  }
}

