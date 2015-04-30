import akka.actor.ActorSystem
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import org.scalatest.{Sequential, Matchers, FunSuite}
import scala.util.Random

/**
 * Created by user on 4/30/15.
 */
trait AddressCacheTestBase extends FunSuite with Matchers {
  def cacheFactory: AddressCache[String]

  implicit lazy val es: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  def N = 100

  def time[T](name: String)(code: => T) {
    val init = System.currentTimeMillis()
    (0 to N) foreach { _ =>
      code
    }
    println("[" + this.getClass.getSimpleName + "]" + name + ": " + (System.currentTimeMillis() - init).toDouble / N + " ms")
  }

  test("single-thread") { time("single") {
    val cache = cacheFactory
    cache add "A" shouldBe true
    cache add "A" shouldBe false
    cache add "B" shouldBe true
    cache.peek shouldBe "B"
    cache take() shouldBe "B"
    cache add "B" shouldBe true
    cache remove "B" shouldBe true
    cache remove "A" shouldBe true
    cache remove "A" shouldBe false
    cache remove "C" shouldBe false
    cache.peek shouldBe null
    cache take() shouldBe null

    cache add "A" shouldBe true
    cache take() shouldBe "A"
    cache add "A" shouldBe true
  }}

  test ("multi-thread: put A,A,B,C -> delete B; put A, C, Z; afterAll: check A, C, Z ") { time("multi") {
    val cache = cacheFactory

    def task1 = List(
      cache add "A",
      cache add "A",
      cache add "B",
      cache add "C",
      cache remove "B"
    )

    def task2 = List(
      cache add "A",
      cache add "C",
      cache add "Z"
    )

    val processed = (0 to 100).par flatMap { i =>
       if (i % 2 == 0) task1 else task2
    }

    val oks = processed filter identity size

    oks should be > 5 // putA + putB + putC + putZ + deleteB = 5

    val results = List(cache.take, cache.take, cache.take) reverse

    //println(results ++ Stream.continually(cache.take).takeWhile(null ne).toList.reverse)

    cache.take() shouldBe null
    results should contain ("Z")
    results filter("Z" !=) should be (List("A", "C"))

  }}

  test("multi-thread take") { time("multi-take") {

      val cache = cacheFactory

      val options = List("A", "B", "C")
      val processed = (1 to 100).par.flatMap { i =>
        if (i % 4 == 3) Option(cache.take()) else {
          cache add options(i % 4)
          None
        }
      }

      val reminder = Stream.continually(cache.take).takeWhile(null ne).toList

      reminder.size shouldBe reminder.toSet.size //no duplicates

      val allTaken = processed ++ reminder

      (options.toSet -- allTaken.toSet) shouldBe empty //check that all elements had been in cache
      allTaken.toSet.size shouldBe options.size
  }}

  test("do not loose any elements") { time("noloose") {
    val cache = cacheFactory
    (1 to 100).par map (_.toString) map cache.add
    (1 to 50).par map (_.toString) map cache.remove
    val reminder = Stream continually cache.take takeWhile (null ne)
    reminder.size shouldBe 50
  }}

  test ("expiration time") {
    val cache = cacheFactory
    cache.add("A")
    cache.peek shouldBe "A"
    Thread.sleep(200)
    cache.take() shouldBe null

  }

}




class AddressCacheTest extends AddressCacheTestBase {
  implicit val as = ActorSystem()
  def cacheFactory = new AkkaBasedCache[String](100, TimeUnit.MILLISECONDS)
}

class AddressCacheCASTest extends AddressCacheTestBase {
  def cacheFactory = new LinearAccessAndConstantPut[String](100, TimeUnit.MILLISECONDS)
}

class AddressCacheCASTest2 extends AddressCacheTestBase {
  def cacheFactory = new ConstantOperations[String](100, TimeUnit.MILLISECONDS)
}
