import akka.actor.ActorSystem
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import java.util.concurrent.{TimeoutException, Executors, ScheduledExecutorService, TimeUnit}
import org.scalatest.{BeforeAndAfterAll, Matchers, FunSuite}
import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, Await, Future}
import scala.util.Try
import sun.security.krb5.Config

trait AddressCacheTestBase extends FunSuite with Matchers with BeforeAndAfterAll {

  val timeoutProp = Option(System.getProperty("timeout")).map(_.toInt).getOrElse(1)
  val performanceTimeoutProp = Option(System.getProperty("perfTimeout")).map(_.toInt).getOrElse(10)

  val duration = Duration(timeoutProp, TimeUnit.SECONDS)
  val performanceTimeout = Duration(performanceTimeoutProp, TimeUnit.SECONDS) //used for performance tests

  def cacheFactory(time: Long = duration.length, unit: TimeUnit = duration.unit, factor: Int = 1): AddressCache[String]

  implicit lazy val es: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  def N = 100

  def systime = System.currentTimeMillis

  def test[T](name: String, fullName: String, N: Int = N, factor: Int = 1)(code: AddressCache[String] => T) {
    test(fullName) {
      val init = systime
      (0 to N) foreach { _ =>
        code(cacheFactory(factor = factor))
      }
      println("[" + this.getClass.getSimpleName + "] " + name + ": " + (System.currentTimeMillis - init).toDouble / N + " ms")
    }
  }

  def nonBlockingTake(cache: AddressCache[String]) = {
    val p = cache.peek
    Option(p).map(cache.remove)
    p
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

    cache add "A" shouldBe true
    cache take() shouldBe "A"
    cache add "A" shouldBe true
  }

  test("blocking-take", "blocking-take") { cache =>
    import scala.concurrent.ExecutionContext.Implicits.global

    val t1 = Future(List(cache.take(), cache.take(), cache.take()))
    val t2 = Future (List(cache.take(), cache.take()))

    Thread.sleep(10) // <--- it also works without this line

    Future {
      cache.add("A")
      cache.add("B")
    }

    Future {
      cache.add("C")
      cache.add("D")
      cache.add("E")
    }

    val res1 = Await.result(t1, duration).toSet
    val res2 = Await.result(t2, duration).toSet

    (res1 & res2) shouldBe empty
  }

  test("take really blocks") {
    import scala.concurrent.ExecutionContext.Implicits.global
    val cache = cacheFactory()

    val blockedThread = Promise[Thread]
    val interruptedException = Promise[Throwable]

    val f = Future {
      blockedThread success Thread.currentThread()
      try {
        cache take()
      } catch {
        case t: InterruptedException => interruptedException success t
        case t: Throwable => throw new Exception(t)
      }
    }

    a [TimeoutException] should be thrownBy Await.result(f, duration)

    blockedThread.future foreach(_.interrupt()) //https://gist.github.com/viktorklang/5409467
    Await.result(interruptedException future, duration * 2) shouldBe an [InterruptedException]
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

    cache.peek shouldBe null
    results should contain ("Z")
    results filter("Z" !=) should be (List("A", "C"))
  }

  test("multi-take", "multi-thread take") { cache =>

    val options = List("A", "B", "C")
    val processed = (1 to 100).par.flatMap { i =>
      if (i % 4 == 3) Option(nonBlockingTake(cache)) else {
        cache add options(i % 4)
        None
      }
    } toList

    val reminder = Stream continually nonBlockingTake(cache) takeWhile(null ne)

    reminder.size shouldBe reminder.toSet.size //no duplicates

    val allTaken = processed ++ reminder

    (options.toSet -- allTaken.toSet) shouldBe empty //check that all elements had been in cache
    allTaken.toSet.size shouldBe options.size
  }

  test("noloose", "do not loose any elements") { cache =>

    (1 to 100).par map (_.toString) map cache.add foreach identity
    (1 to 50).par map (_.toString) map cache.remove foreach identity

    val reminder = Stream continually nonBlockingTake(cache) takeWhile (null ne)
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

    cacheFactory(1, TimeUnit.NANOSECONDS)
    Try(cacheFactory(21474836, TimeUnit.SECONDS)).toOption shouldBe empty //due to LSP
    Try(cacheFactory(-5, TimeUnit.SECONDS)).toOption shouldBe empty
  }

  test("expiration time") {
    val cache = cacheFactory()
    cache.add("A")
    cache.peek shouldBe "A"
    Thread.sleep(timeoutProp * 2 * 1000)
    cache.peek shouldBe null
  }

  def performance(name: String)(f: AddressCache[String] => String => Any) = test("performance of parallel " + name)  {
    import scala.concurrent.ExecutionContext.Implicits.global


    def fut: Future[Long] = Future { (0 to 3).map { _ => //warm-up
      val cache: AddressCache[String] = cacheFactory(factor = 100)
      (0 to 10000) map (_.toString) foreach cache.add

      (0 to 100).par map (_.toString) map { a =>
        val t1 = systime
        (0 to 1000) map (_.toString) foreach {b => f(cache)(a + "-" + b)}
        val t2 = systime
        t2 - t1
      } sum
    }.tail.sum}

    val time = Try(Await.result(fut, performanceTimeout * 3).toDouble / 100).getOrElse("over9000")

    println("[" + this.getClass.getSimpleName + "] " + "performance-" + name + ": " + time + " ms")
  }

  performance("put")(_.add)

  performance("peek")(x => _ => x.peek)

  performance("remove")(_.remove)

}

class AkkaBasedCacheTest extends AddressCacheTestBase {
  val conf = ConfigFactory.defaultReference()
    .withValue("akka.log-dead-letters", ConfigValueFactory.fromAnyRef("off"))
    .withValue("akka.log-dead-letters-during-shutdown", ConfigValueFactory.fromAnyRef("off"))

  implicit val as = ActorSystem("MySys", conf)

  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new AkkaBasedCache[String](time, unit, factor)

  override def afterAll {
    as.terminate()
  }

}

class LinearAccessAndConstantPutTest extends AddressCacheTestBase {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new LinearAccessAndConstantPut[String](time, unit)
}

class ConstantOperationsTest extends AddressCacheTestBase {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new ConstantOperations[String](time, unit)
}

class ConstantOperationsFastScheduleTest extends AddressCacheTestBase {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new ConstantOperations[String](time, unit) with AddressCacheScheduleFast[String]

}

class TrivialCacheTest extends AddressCacheTestBase {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new TrivialCache[String](time, unit)
}

