// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobraserver

import com.google.gson.Gson
import org.eclipse.lsp4j.Range
import viper.gobra.ast.internal.Node
import viper.gobra.reporting._
import viper.silver.ast.{AbstractSourcePosition, Position => GobraPosition}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

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


  private def getHighlightedRanges(positionStore: mutable.Map[GobraPosition, ListBuffer[ViperPosition]]): ListBuffer[HighlightingPosition] = {
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
    case DesugaredMessage(_, internal) if internalPreview =>
      val internalFormatted = internal().formatted
      val positionStore = Node.defaultPrettyPrinter.positionStore

      val highlightedRanges = getHighlightedRanges(positionStore)

      VerifierState.client match {
        case Some(c) => c.finishedInternalCodePreview(internalFormatted, gson.toJson(highlightedRanges.toArray))
        case None =>
      }


    case GeneratedViperMessage(_, ast, _) if viperPreview =>
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

