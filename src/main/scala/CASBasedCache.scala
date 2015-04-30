import java.util.concurrent._
import java.util.concurrent.atomic._
import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.util.Try
import Function._



/**
 * This should be the fastest one, especially for big buffers
 *
 * It uses two collections, both are CAS-based, so no locks there
 *
 * It's thread-safe, sequentially-consistent in "read-your-own-writes" meaning
 *
 */
class ConstantOperations[InetAddress <: AnyRef](maxAge: Long, timeUnit: TimeUnit)(implicit val ees: ScheduledExecutorService)
  extends AddressCacheScheduler[InetAddress](maxAge, timeUnit) {

  private val stack = new ConcurrentLinkedDeque[InetAddress]

  private val stackScala = stack asScala

  private val set = new TrieMap[InetAddress, InetAddress]()

  override def add(addr: InetAddress): Boolean = {
    stack addFirst addr //to preserve order and avoid races by guarantee that this put happens-before all putIfAbscents
    scheduleRemove(addr)
    if (set.putIfAbsent(addr, addr).isEmpty) true else {
      stack remove addr //it may not be previously added address, the point is to do remove N times, where N - is count of loosed puts
      false
    }
  }

  override def peek: InetAddress =  // effective O(1)
    stackScala.find(set.contains) getOrElse nulll //skip removed and phantom elements

  override def take(): InetAddress = { // effective O(1)
    val p = peek
    remove(p)
    p
  }

  override def remove(addr: InetAddress): Boolean = set.remove(addr).map(stack.remove).nonEmpty

}


/**
 * Atomic, non-blocking, but slow access
 *
 * The synchronization here is much simpler (it's based on vector clock) and still non-blocking,
 * but it works slower for big collections
 *
 */
class LinearAccessAndConstantPut[InetAddress <: AnyRef](maxAge: Long, timeUnit: TimeUnit)(implicit val ees: ScheduledExecutorService)
  extends AddressCacheScheduler[InetAddress](maxAge, timeUnit){

  private case class Info(seqNumber: Long, v: InetAddress, timestamp: Long = System.currentTimeMillis()) {override def toString = seqNumber.toString}

  private val set = new TrieMap[InetAddress, Info]()

  private val clock = new AtomicLong(1) //This is the primitive vector clock: http://en.wikipedia.org/wiki/Vector_clock

  override def add(addr: InetAddress): Boolean = { //O(1)
    scheduleRemove(addr)
    val nn = clock.incrementAndGet()
    val info = Info(nn, addr)
    set.putIfAbsent(addr, info).isEmpty
  }

  override def peek: InetAddress =  Try(set.maxBy(x => x._2.seqNumber -> x._2.timestamp)._1) getOrElse nulll //it's O(N); timestamp is used in case of `Long`'s overflow

  override def take(): InetAddress = set.remove(peek).map(_.v) getOrElse nulll //O(N)

  override def remove(addr: InetAddress): Boolean = set.remove(addr).nonEmpty //O(1)
}

