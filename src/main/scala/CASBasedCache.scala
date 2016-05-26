import com.google.common.cache.CacheBuilder
import java.util.concurrent._
import java.util.concurrent.atomic._
import scala.collection.concurrent._
import scala.collection.JavaConverters._
import scala.util.Try

/**
 * This should be the fastest one, especially for big stacks
 * It uses two collections: TrieMap and ConcurrentLinkedDeque, both are CompareAndSwap-based, so no locks here
 * It's thread-safe, sequentially-consistent
 *
 * @note the collection is consistent over `==`, but not over reference equality `eq`
 */
class ConstantOperations[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val es: ScheduledExecutorService)
  extends AddressCacheWithScheduledExecutor[InetAddress](maxAge, timeUnit) with AddressCacheSchedule[InetAddress] with TakeFromPeek[InetAddress] {

  private val stack = new ConcurrentLinkedDeque[InetAddress]

  private val stackScala = stack asScala

  private val set = new TrieMap[InetAddress, InetAddress]()

  override def add(addr: InetAddress): Boolean = { // effective O(1)
    assert (addr != null)
    stack addFirst addr // to preserve order and avoid races

    if (set.putIfAbsent(addr, addr).isEmpty) {
      scheduleRemove(addr)
      propagate()
      true
    } else {
      stack remove addr // it may not be previously added address, the point is to do remove N times, where N - is count of lost puts
      false
    }
  }

  override def peek: InetAddress =  // effective O(1)
    stackScala.find(set.contains) getOrElse nulll // skip removed and phantom elements

  override def remove(addr: InetAddress): Boolean = {
    assert (addr != null)
    removeScheduler(addr)
    set.remove(addr).map(stack.remove).nonEmpty
  } //O(N); can be easily changed to O(1) by removing `.map(stack.remove)`; however, it may affect memory consumption

}


/**
 * Vector-clock based solution
 * The synchronization here is much simpler and still non-blocking
 * @note access is slow for big collections
 */
abstract class LinearAccessAndConstantPutLike[InetAddress](maxAge: Long, timeUnit: TimeUnit)
  extends AddressCacheWithScheduledExecutor[InetAddress](maxAge, timeUnit) with AddressCacheScheduleBase[InetAddress] with TakeFromPeek[InetAddress] {

  protected case class Info(seqNumber: Long, v: InetAddress, epoch: Long = System.currentTimeMillis() / 100)

  protected val set: scala.collection.concurrent.Map[InetAddress, Info]

  private val clock = new AtomicLong(1) // this is the primitive vector clock: http://en.wikipedia.org/wiki/Vector_clock

  override def add(addr: InetAddress): Boolean = { // O(1)
    assert (addr != null)

    val nn = clock.incrementAndGet()
    if (nn == 0) Thread.sleep(102) //go to the next epoch
    val info = Info(nn, addr)

    if (set.putIfAbsent(addr, info).isEmpty) {
      scheduleRemove(addr)
      propagate()
      true
    } else false
  }

  /**
   * Getting the head. timestamp (epoch) is used in case of `Long`'s overflow
   * It's O(N), but it is possible to rewrite it to be effectively constant by introducing `val epochSeqToInetAddr = new TrieMap[(Long, Long), InetAddress]`
   * Then, we will have to try current `seqNumber`, if it's not found then `seqNumber - 1` and so on and so forth
   *
   * @return head
   */
  override def peek: InetAddress = Try(set.maxBy(x => x._2.epoch -> x._2.seqNumber)._1) getOrElse nulll

  override def remove(addr: InetAddress): Boolean = { // O(1)
    assert (addr != null)
    removeScheduler(addr)
    set.remove(addr).nonEmpty
  }

}

/**
 * TrieMap + AddressCacheSchedule based implementation
 */
class LinearAccessAndConstantPut[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val es: ScheduledExecutorService)
  extends LinearAccessAndConstantPutLike[InetAddress](maxAge, timeUnit) with AddressCacheSchedule[InetAddress] {

  protected val set = new TrieMap[InetAddress, Info]()
}

/**
 * Guava-based implementation
 */
class LinearAccessAndConstantPutGuava[InetAddress](maxAge: Long, timeUnit: TimeUnit)
  extends LinearAccessAndConstantPutLike[InetAddress](maxAge, timeUnit) {

  protected val set = CacheBuilder.newBuilder().expireAfterWrite(maxAge, timeUnit).build().asMap()
    .asInstanceOf[ConcurrentMap[InetAddress, Info]].asScala

  protected def scheduleRemove(addr: InetAddress) = {}

  protected def removeScheduler(addr: InetAddress) = {}

}
