import org.scalatest.{Matchers, FunSuite}

/**
 * Created by user on 4/30/15.
 */
class T9Test  extends FunSuite with Matchers with T9 {


  def file =
    """|cappuccino
       |chocolate
       |cinnamon
       |coffee
       |latte
       |vanilla""".stripMargin.split("\n").toIterator


  test("test T9") {
    search(file, "22222") shouldBe "No matches"
    search(file, "333") shouldBe "coffee"
    search(file, "626") shouldBe
      """|2 matches:
         |chocolate
         |cinnamon""".stripMargin
  }



}
