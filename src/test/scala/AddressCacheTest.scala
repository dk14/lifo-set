import akka.actor.ActorSystem
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import java.util.concurrent._
import org.scalatest.{BeforeAndAfterAll, Matchers, FunSuite}
import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, Await, Future}
import scala.util.Try

trait AddressCacheTestBase[A <: AddressCache[String]] extends FunSuite with Matchers with BeforeAndAfterAll {

  type InetAddr = String
  type Cache = A

  val timeoutProp = Option(System.getProperty("timeout")).map(_.toInt).getOrElse(1)
  val performanceTimeoutProp = Option(System.getProperty("perfTimeout")).map(_.toInt).getOrElse(10) //timeout for performance tests

  val duration = Duration(timeoutProp, TimeUnit.SECONDS)
  val performanceTimeout = Duration(performanceTimeoutProp, TimeUnit.SECONDS) //used for performance tests

  def cacheFactory(time: Long = duration.length, unit: TimeUnit = duration.unit, factor: Int = 1): A

  implicit lazy val es: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  def N = 100 //count of repetitions

  def systime = System.currentTimeMillis

  /**
   * Runs test several times and prints average time
   * @param name short name of the test
   * @param fullName full name
   * @param N number of repetitions
   * @param factor ho much maxAges's in one timeout (for Akka)
   * @param code test body
   */
  def test[T](name: String, fullName: String, N: Int = N, factor: Int = 1)(code: AddressCache[InetAddr] => T) {
    test(fullName) {
      val init = systime
      (0 to N) foreach { _ =>
        code(cacheFactory(factor = factor))
      }
      println("[" + this.getClass.getSimpleName + "] " + name + ": " + (System.currentTimeMillis - init).toDouble / N + " ms")
    }
  }

  def ignore[T](name: String, fullName: String, N: Int = N, factor: Int = 1)(code: AddressCache[InetAddr] => T){
    ignore(fullName) {}
  }

  def nonBlockingTake(cache: AddressCache[String]) = {
    val p = cache.peek
    Option(p).map(cache.remove)
    p
  }

  def after(c: Cache) = {}
}

trait CommonTests[A <: AddressCache[String]] extends AddressCacheTestBase[A] with ExpirationTests[A] {

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

  test("one-true", "true is returned only once") { cache =>
    val adds =  (1 to 100).par.map(_ => cache.add("A"))
    val removes = (1 to 100).par.map(_ => cache.remove("A"))
    adds count identity shouldBe 1
    removes count identity shouldBe 1

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

    (options.toSet -- allTaken.toSet) shouldBe empty //check that every element had been in cache
    allTaken.toSet.size shouldBe options.size
  }

  test("noloose", "do not loose any elements") { cache =>

    (1 to 100).par map (_.toString) map cache.add foreach identity //foreach here because par-collection's macro don't really execute code if you don't use it
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

}

trait ExpirationTests [A <: AddressCache[String]] extends AddressCacheTestBase[A] {

  test("expiration time") { (0 to 3) foreach { _ =>
    val cache = cacheFactory()
    cache add "A"
    cache.peek shouldBe "A"
    Thread.sleep(timeoutProp  * 750) //<-- that's why it's in separate trait
    cache.peek shouldBe "A"
    cache remove "A"
    cache add "A" //reschedule; it's not much precise check
    Thread.sleep(timeoutProp * 750) //<-- that's why it's in separate trait
    cache.peek shouldBe "A"
    Thread.sleep(timeoutProp * 2 * 1000) //<-- that's why it's in separate trait
    cache.peek shouldBe null
    after(cache)
  }}
}


trait PerformanceTests[A <: AddressCache[String]] extends AddressCacheTestBase[A] {

  def performance(name: String)(f: AddressCache[String] => String => Any) = test("performance of parallel " + name)  {
    import scala.concurrent.ExecutionContext.Implicits.global

    def fut: Future[Long] = Future { (0 to 3).map { _ => //warm-up
      val cache: A = cacheFactory(factor = 100)
      (0 to 10000) map (_.toString) foreach cache.add

      val r = try {
        (0 to 100).par map (_.toString) map { a =>
          val t1 = systime
          (0 to 1000) map (_.toString) foreach { b => f(cache)(a + "-" + b)}
          val t2 = systime
          t2 - t1
        } sum
      } finally {
        after(cache)
      }
      r
    }.tail.sum}

    val time = Try(Await.result(fut, performanceTimeout * 3).toDouble / 100).getOrElse("over9000")

    println("[" + this.getClass.getSimpleName + "] " + "performance-" + name + ": " + time + " ms")
  }

  performance("put")(_.add)

  performance("peek")(x => _ => x.peek)

  performance("remove")(_.remove)

}

trait AllTests extends CommonTests[AddressCache[String]] with PerformanceTests[AddressCache[String]]

trait AllTestsA[A[T] <: AddressCache[T]] extends CommonTests[A[String]] with PerformanceTests[A[String]] //high-order type A[T] allows to not specify `[String]`



//------------------CacheScheduleFastSuspendable------------------------------------------------------------------------

trait AsyncAdaptor[InetAddress] extends AddressCacheScheduleFastSuspendable[InetAddress] {
  this: AddressCacheWithScheduledExecutor[InetAddress] =>

  var delta: Long = 0
  override def systime = super.systime + delta //time travels
  def forward() = {delta += 10; tick()} //time travels

  lazy val suspends = List.fill(5)(Promise[String])
  lazy val resumes = List.fill(5)(Promise[String])

  protected override def suspend(): Unit = {
    suspends.find(x => Try(x.success("OK")).isSuccess) //notify tests
    super.suspend()
  }

  protected  override def resume(): Unit = {
    resumes.find(x => Try(x.success("OK")).isSuccess) //notify tests
    super.resume()
  }

  def isEmpty = noActivity

}

trait TypeFactory{ type GetSuspendable[T] = AddressCacheWithScheduledExecutor[T] with AsyncAdaptor[T]} //alias

trait CacheWithSuspendableSchedulerTests extends AllTestsA[TypeFactory#GetSuspendable] {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def after(c: Cache) = c.close()

  test("suspend cache") { (0 to 100) foreach { _ =>
    val cache = cacheFactory(1, TimeUnit.MILLISECONDS)
    import cache._

    forward()

    val scenario = for { //monadic processing
      _ <- suspends(0).future
      _ = add("A")
      _ = forward()
      _ <- resumes(0).future
      _ = forward() // "A" removed automatically
      _ = if (peek != null) println(peek + delta)
      _ <- suspends(1).future
      _ = close()
    } yield ()

    def okerror(b: Promise[String]) = if(b.isCompleted) "[PASSED]" else "[NOT_PASSED]"

    try {
      Await.result(scenario, duration)
    } catch {
      case t: TimeoutException =>
        fail(s"flow incomplete: suspend${okerror(suspends(0))} -> resume${okerror(resumes(0))} -> suspend${okerror(suspends(1))}")
      case t: Throwable => throw t
    }

    peek shouldBe null
    isCancelled shouldBe true
    isEmpty shouldBe true

  }}

}

//--------------------------Implementations-----------------------------------------------------------------------------

class AkkaBasedCacheTest extends AllTests {
  val conf = ConfigFactory.defaultReference()
    .withValue("akka.log-dead-letters", ConfigValueFactory.fromAnyRef("off"))
    .withValue("akka.log-dead-letters-during-shutdown", ConfigValueFactory.fromAnyRef("off"))

  implicit val as = ActorSystem("MySys", conf)

  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new AkkaBasedCache[InetAddr](time, unit, factor)

  override def afterAll {
    as.terminate()
  }

}

class LinearAccessAndConstantPutTest extends AllTests {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new LinearAccessAndConstantPut[InetAddr](time, unit)
}

class ConstantOperationsTest extends AllTests {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new ConstantOperations[InetAddr](time, unit)
}

class ConstantOperationsFastScheduleTest extends CacheWithSuspendableSchedulerTests {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new ConstantOperations[InetAddr](time, unit) with AsyncAdaptor[InetAddr]
}

class TrivialCacheTest extends AllTests {
  def cacheFactory(time: Long, unit: TimeUnit, factor: Int) = new TrivialCache[InetAddr](time, unit)
}