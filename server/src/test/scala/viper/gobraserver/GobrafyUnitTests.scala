package viper.gobraserver

import org.scalatest.{Assertion, Inside}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GobrafyUnitTests extends AnyFunSuite with Matchers with Inside {
  private val frontend = new TestFrontend()

  test("call with ghost args") {
    val input =
      """
        |//@ ghost-parameters: b int, c int
        |//@ requires a >= 0 && b >= 0
        |func foo(a int) {}
        |""".stripMargin
    val expected =
      """
        |requires a >= 0 && b >= 0
        |func foo(a int, ghost b int, ghost c int) {}
        |""".stripMargin
    frontend.gobrafy(input, expected)
  }

  test("call with ghost results") {
    val input =
      """
        |//@ ghost-results: b int, c int
        |//@ requires a >= 0
        |//@ ensures a == b && b == c
        |func foo(a int) {}
        |""".stripMargin
    val expected =
      """
        |requires a >= 0
        |ensures a == b && b == c
        |func foo(a int) (ghost b int, ghost c int) {}
        |""".stripMargin
    frontend.gobrafy(input, expected)
  }

  /* ** Stubs, mocks and other test setup */

  class TestFrontend {
    def gobrafy(input: String, expected: String): Assertion = {
      val actual = Gobrafier.gobrafyFileContents(input)
      actual.strip() should be (expected.strip())
    }
  }
}
