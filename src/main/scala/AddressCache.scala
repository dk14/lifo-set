import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ScheduledFuture, LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration

/**
 * The main interface
 * @param maxAge
 * @param timeUnit
 * @tparam InetAddress
 */
abstract class AddressCache[InetAddress](val maxAge: Long, val timeUnit: TimeUnit) {

  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) <= 21474835) //akka can't have more, so this is restriction for all other implementations to preserve LSP
  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) >= 0)

  def add(addr: InetAddress): Boolean

  def remove(addr: InetAddress): Boolean

  def peek: InetAddress

  def take(): InetAddress

  protected def nulll = null.asInstanceOf[InetAddress]

}

/**
 * Marker for nonAkkaScheduler-based caches
 */
abstract class AddressCacheWithScheduledExecutor[InetAddress](maxAge: Long, timeUnit: TimeUnit) extends AddressCache[InetAddress](maxAge, timeUnit)

/**
 * Simple scheduling support
 */
trait AddressCacheSchedule[InetAddress] extends  {
  self: AddressCacheWithScheduledExecutor[InetAddress] =>

  implicit def es: ScheduledExecutorService

  private val futures = TrieMap[InetAddress, ScheduledFuture[_]]()

  protected def scheduleRemove(addr: InetAddress): Unit = futures.putIfAbsent(addr,es.schedule(new Runnable { //it will cause a small lock to put task in to the queue
    override def run(): Unit = remove(addr)
  }, maxAge, timeUnit))

  def removeScheduler(addr: InetAddress): Unit = futures.remove(addr).map(_.cancel(true))

}

/**
 * Locks-free schedule but may need more CPU
 * @note CPU consumption and time approximation depends on `timeUnit`
 * @note you should close it after usage - otherwise it will keep ticking
 * P.S. It's significantly faster on my MacBook but not much on Travis-CI
 */
trait AddressCacheScheduleFast[InetAddress]  extends AddressCacheSchedule[InetAddress] {
  self: AddressCacheWithScheduledExecutor[InetAddress] =>
  private val timestamps = TrieMap[InetAddress, Long]()

  protected def systime = System.currentTimeMillis()

  protected final def noActivity = timestamps.isEmpty

  protected def tick(): Unit = {
    timestamps retain { case (addr, timestamp) =>
      val delta = systime - timestamp
      if (delta >= timeUnit.toMillis(maxAge)){
        remove(addr)
        false
      } else {
        true
      }
    }
  }

  override def removeScheduler(addr: InetAddress): Unit = timestamps.remove(addr)

  protected def schedule() = es.scheduleAtFixedRate(new Runnable {
    override def run(): Unit = try {
      tick()
    } catch {
      case t: Throwable => t.printStackTrace()
    }
  }, 0, Math.max(timeUnit.toMillis(1) / 4, 1), TimeUnit.MILLISECONDS) //may choose nanoseconds, but what's the point

  protected var scheduled = schedule()

  override protected def scheduleRemove(addr: InetAddress): Unit = {
    timestamps.putIfAbsent(addr, systime)
  }

  def close(): Unit = scheduled.cancel(true)

  def isCancelled = scheduled.isCancelled

}

/**
 * This scheduler supports auto-suspension, so no need to call `close` method
 */
trait AddressCacheScheduleFastSuspendable[InetAddress] extends AddressCacheScheduleFast[InetAddress] {
  this: AddressCacheWithScheduledExecutor[InetAddress] =>

  private var lastActivity = systime
  var lastInActivity = systime

  private var isClosed = false

  override def isCancelled = isClosed

  def inactivityFactor = 2
  private def inactivityTimeout = inactivityFactor * Duration(maxAge, timeUnit)

  protected def suspend(): Unit = scheduled cancel true

  protected def resume(): Unit = scheduled = schedule()

  protected override def tick() = synchronized { //just in case
    if ((scheduled ne null) && !isSuspended) {
      if (noActivity) {
        if (systime - lastActivity > inactivityTimeout.toMillis) suspend()
        if (!noActivity) resume()
      } else {
        lastActivity = systime
      }
    }
    super.tick()
  }

  override protected def scheduleRemove(addr: InetAddress): Unit = {
    if (isSuspended && !isClosed) resume()
    super.scheduleRemove(addr)
  }

  override def close() = {
    isClosed = true
    super.close
  }

  def isSuspended = scheduled.isCancelled
  
}

/**
 * Take or wait implementation
 *
 */
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

  var ask = new AtomicInteger(0) //just to make notifying faster by avoiding `ReentrantLock`

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