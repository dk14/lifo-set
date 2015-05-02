import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec


abstract class AddressCache[InetAddress](val maxAge: Long, val timeUnit: TimeUnit) {

  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) <= 21474835) //akka can't have more, so this is restriction for all other implementations to preserve LSP
  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) >= 0)

  def add(addr: InetAddress): Boolean

  def remove(addr: InetAddress): Boolean

  def peek: InetAddress

  def take(): InetAddress

  protected def nulll = null.asInstanceOf[InetAddress]

}

abstract class AddressCacheSchedule[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val es: ScheduledExecutorService)
  extends AddressCache[InetAddress](maxAge, timeUnit) {

  protected def scheduleRemove(addr: InetAddress) = es.schedule(new Runnable {
    override def run(): Unit = remove(addr)
  }, maxAge, timeUnit)

}

trait TakeFromPeek[InetAddress] {
  this: AddressCache[InetAddress] =>


  /**
   * Used to send notifications.
   *
   * it's faster and simpler than `var a = Promise; ...; {a.complete(); a = Promise}` as it doesn't require `ExecutorService`
   * it can be faster than `monitor.notify()` as queue uses `Unsafe.unpark()` + some CAS directly
   * using `Condition.signal` from `java.util.concurrent` is a little faster, but it takes more lines of code (explicit `ReentrantLock`)
   *
   */
  private val queue = new LinkedBlockingQueue[Unit](1)

  var ask = new AtomicInteger(0) //just to make notifying faster

  protected def propagate() = if (ask.get() > 0) queue.offer(()) //notify

  @tailrec override final def take(): InetAddress = {
    val p = peek
    ask.incrementAndGet() // "I need an element!"
    if (p == null) {
      scala.concurrent.blocking { // marks this thread as blocked (pool uses this info to prevent starvation); see http://stackoverflow.com/questions/29068064/scala-concurrent-blocking-what-does-it-actually-do
        queue.poll(maxAge, timeUnit) // wait for a while
      } // but be aware of this: http://stackoverflow.com/questions/29663410/bad-use-cases-of-scala-concurrent-blocking
      take() //...endless wait
    } else {
      propagate() // propagate notification to other threads (in case if several elements was added after previous propagate)
      ask.decrementAndGet() // "I've got an element!"
      if (remove(p)) p else take() // remove; pickup new if already removed
    }
  }

}