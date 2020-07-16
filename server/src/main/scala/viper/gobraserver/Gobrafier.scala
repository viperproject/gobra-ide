package viper.gobraserver

import scala.io.Source
import java.io._
import scala.util.matching.Regex

object GobrafierRunner {

  /**
    * Keywords used in Goified files.
    */
  private val ghost_parameters: String = "ghost-parameters:"
  private val ghost_results: String = "ghost-results:"
  private val addressable_variables: String = "addressable:"
  private val predicate_access: String = "predicate-access:"
  private val with_keyword: String = "with:"
  private val unfolding_keyword: String = "unfolding:"

  private val ghost_keyword: String = "ghost"

  /**
    * Regular expression patterns used in the regular expressions.
    */
  private val identifier_r = "(\\S+?)"
  private val functionInvocation_r = s"$identifier_r\\((.*?)\\)"
  private val functionDecl_r = "(func.*?)\\((.*?)\\)"
  private val spec_r = "\\n(.*?)"
  private val non_newline_r = "[\t\r\f]"
  
  private def multilineComment_r(str: String): String = s"/\\*@\\s*$str\\s*@\\*/"
  private def singlelineComment_r(str: String): String = s"//@\\s*$str\\s*"


  private val ghostParamsRegex =
    new Regex(s"(?s)${singlelineComment_r(s"$ghost_parameters(.*?)$spec_r$functionDecl_r")}",
    "ghostArgs", "spec", "funcName", "funcArgs")

  private val ghostResultsRegex =
    new Regex(s"(?s)${singlelineComment_r(s"$ghost_results\\s*(.*?)$spec_r$functionDecl_r\\s*\\((.*?)\\)")}",
    "ghostResults", "spec", "funcName", "funcArgs", "funcResults")

  private val pureKeywordRegex = new Regex(s"(?s)${singlelineComment_r(s"pure(.*?)func")}", "spec")

  private val addressableVariablesRegex = new Regex(s"(?m)(^.*?)\\s*${singlelineComment_r(addressable_variables)}(.*?)$$",
    "code", "addressableVars")

  private val ghostInvocationRegex = new Regex(s"(?m)(\\s*.*?)\\((.*?)\\)\\s*${multilineComment_r(s"$with_keyword\\s*(.*?)")}",
    "funcName", "funcParams", "ghostParams")

  private val unfoldingAccessRegex =
    new Regex(s"(?m)${multilineComment_r(s"$unfolding_keyword(.*?)\\s*")}", "predicate")



  /**
    * Remove the remaining goifying comments in the file.
    */
  def removeGoifyingComments(fileContent: String): String =
    //fileContent.replaceAll("//@\\s*", "").replaceAll("/\\*@\\s*", "").replaceAll("(?m)\\s*@\\*/", "")
    fileContent.replaceAll(s"//@\\s*", "").replaceAll(s"/\\*@$non_newline_r*", "").replaceAll(s"(?m)$non_newline_r*@\\*/", "")

  /**
    * Add parenthesis around the given string.
    */
  def parens(str: String): String = "(" + str + ")"

  /**
    * Add ghost keywords between parameters.
    */
  def addGhostKeywordToParamsList(paramsList: String): String =
    paramsList.trim.replaceAll("\\s+", " ").split("\\s*,\\s*").map(v => ghost_keyword + " " + v).mkString(", ")

  /**
    * Split string with list of addressable variables into a list
    * of all identifiers of the addressable variables.
    */
  def splitAddressableVariables(addressableVariables: String): List[String] = {
    addressableVariables.replaceAll(" ", "").split(",").toList
  }


  def gobrafyFileContents(fileContents: String): String = {

    /**
      * Replace ghost-parameters annotations by adding ghost parameters
      * back to the function parameters.
      */
    var newFileContents = ghostParamsRegex.replaceAllIn(fileContents, m => {
      "\n" + // added to maintain line consistency
      m.group("spec") +
      m.group("funcName") +
      parens(m.group("funcArgs") +
      (if (m.group("funcArgs") == "") "" else ", ") +
      addGhostKeywordToParamsList(m.group("ghostArgs")))
    })

    /**
      * Replace ghost-results annotations by adding ghost results
      * back to the function results.
      */
    newFileContents = ghostResultsRegex.replaceAllIn(newFileContents, m => {
      println(m.group("funcResults"))

      "\n" + // added to maintain line consistency
      m.group("spec") +
      m.group("funcName") +
      parens(m.group("funcArgs")) +
      " " +
      parens(m.group("funcResults") +
      (if (m.group("funcResults") == "") "" else ", ") +
      m.group("ghostResults"))
      
    })

    /**
      * Add pure keyword to function declaration.
      */
    newFileContents = pureKeywordRegex.replaceAllIn(newFileContents, m => {
      m.group("spec") +
      "pure func"
    })

    /**
      * Add exclamation mark to all addressable variables.
      */
    newFileContents = addressableVariablesRegex.replaceAllIn(newFileContents, m => {
      val addressableVariables = splitAddressableVariables(m.group("addressableVars"))

      var code = m.group("code")

      addressableVariables.foreach(addrVar => {
        // remove all control characters
        val variable = addrVar.filter(_ >= ' ')
        
        code = code.replaceAll(variable, variable + "!")
      })

      code
    })

    /**
      * Add ghost parameters back to method invocations.
      */
    newFileContents = ghostInvocationRegex.replaceAllIn(newFileContents, m => {
      m.group("funcName") + 
      parens(m.group("funcParams") + 
      (if (m.group("funcParams") == "") "" else ", ") +
      m.group("ghostParams"))
    })

    /**
      * Put unfolding expressions back.
      */
    newFileContents = unfoldingAccessRegex.replaceAllIn(newFileContents, m => {
      "unfolding" + m.group("predicate") + " in "
    })
    
    removeGoifyingComments(newFileContents)

  }


}