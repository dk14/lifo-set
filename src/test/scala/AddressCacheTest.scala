import akka.actor.ActorSystem
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import org.scalatest.{Matchers, FunSuite}
import scala.concurrent.duration.Duration
import scala.util.Try


/**
 * Created by user on 4/30/15.
 */
trait AddressCacheTestBase extends FunSuite with Matchers {

  def cacheFactory(time: Long = 1000, unit: TimeUnit = TimeUnit.MILLISECONDS): AddressCache[String]

  implicit lazy val es: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  def N = 100

  def test[T](name: String, fullName: String)(code: AddressCache[String] => T) {
    test(fullName) {
      val init = System.currentTimeMillis
      (0 to N) foreach { _ =>
        code(cacheFactory())
      }
      println("[" + this.getClass.getSimpleName + "] " + name + ": " + (System.currentTimeMillis - init).toDouble / N + " ms")
    }
  }


  test("single", "single-thread") { cache =>

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
  }

  test("multi", "multi-thread: put A,A,B,C -> delete B; put A, C, Z; afterAll: check A, C, Z ") { cache =>

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

    oks should be >= 5 // putA + putB + putC + putZ + deleteB = 5

    val results = List(cache.take(), cache.take(), cache.take()) reverse

    cache.take() shouldBe null
    results should contain ("Z")
    results filter("Z" !=) should be (List("A", "C"))

  }

  test("multi-take", "multi-thread take") { cache =>

      val options = List("A", "B", "C")
      val processed = (1 to 100).par.flatMap { i =>
        if (i % 4 == 3) Option(cache take()) else {
          cache add options(i % 4)
          None
        }
      } toList

      val reminder = Stream continually cache.take() takeWhile(null ne)

      reminder.size shouldBe reminder.toSet.size //no duplicates

      val allTaken = processed ++ reminder

      (options.toSet -- allTaken.toSet) shouldBe empty //check that all elements had been in cache
      allTaken.toSet.size shouldBe options.size
  }

  test("noloose", "do not loose any elements") { cache =>

    (1 to 100).par map (_.toString) map cache.add foreach identity
    (1 to 50).par map (_.toString) map cache.remove foreach identity

    val reminder = Stream continually cache.take() takeWhile (null ne)
    reminder.size shouldBe 50
  }

  test("null test") {
    val cache = cacheFactory()
    Try(cache.add(null)).toOption shouldBe empty
    Try(cache.remove(null)).toOption shouldBe empty
  }

  test("big time") {
    Duration(Long.MaxValue, TimeUnit.NANOSECONDS) //max for Duration
    Duration(-1, TimeUnit.NANOSECONDS)
    val cache = cacheFactory(21474835, TimeUnit.SECONDS) //max for Akka
    cache.add("A")

    Try(cacheFactory(21474836, TimeUnit.SECONDS)).toOption shouldBe empty //due to LSP
    Try(cacheFactory(-5, TimeUnit.SECONDS)).toOption shouldBe empty
  }

  test("expiration time") {
    val cache = cacheFactory()
    cache.add("A")
    cache.peek shouldBe "A"
    Thread.sleep(2000)
    cache.take() shouldBe null
  }

}

class AkkaBasedCacheTest extends AddressCacheTestBase {
  implicit val as = ActorSystem()
  def cacheFactory(time: Long, unit: TimeUnit) = new AkkaBasedCache[String](time, unit)
}

class LinearAccessAndConstantPutTest extends AddressCacheTestBase {
  def cacheFactory(time: Long, unit: TimeUnit) = new LinearAccessAndConstantPut[String](time, unit)
}

class ConstantOperationsTest extends AddressCacheTestBase {
  def cacheFactory(time: Long, unit: TimeUnit) = new ConstantOperations[String](time, unit)
}
