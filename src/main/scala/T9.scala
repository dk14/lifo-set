import scala.io.Source

/**
 * Created by user on 4/30/15.
 */
trait T9 {

  val translate = Map(
    ' ' -> '1',
    'a' -> '2',
    'b' -> '2',
    'c' -> '2',
    'd' -> '3',
    'e' -> '3',
    'f' -> '3',
    'g' -> '4',
    'h' -> '4',
    'i' -> '4',
    'j' -> '5',
    'k' -> '5',
    'l' -> '5',
    'm' -> '6',
    'n' -> '6',
    'o' -> '6',
    'p' -> '7',
    'q' -> '7',
    'r' -> '7',
    's' -> '7',
    't' -> '8',
    'u' -> '8',
    'v' -> '8',
    'w' -> '9',
    'x' -> '9',
    'y' -> '9',
    'z' -> '9')



  def search(file: Iterator[String], query: String) = {
    val translated = file map (x => x -> (x map translate)) //to not loose letters after translating

    translated filter (_._2.contains(query)) map (_._1) toList match {
      case Nil => "No matches"
      case h :: Nil => h
      case l => s"${l.size} matches:\n${l mkString "\n"}"
    }
  }

}

object T9 extends T9 with App {
  val filename = args.head
  val query = args.tail.head
  val file = Source.fromFile(filename).getLines
  println(search(file, query))

}
