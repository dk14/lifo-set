import java.util.concurrent._
import java.util.concurrent.atomic._
import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.util.Try

/**
 * This should be the fastest one, especially for big stacks
 * It uses two collections TrieMap and ConcurrentLinkedDeque, both are CompareAndSwap-based, so no locks here
 * It's thread-safe, sequentially-consistent
 *
 * @note the collection is consistent over `==`, but not over reference equality `eq`
 */
class ConstantOperations[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val ees: ScheduledExecutorService)
  extends AddressCacheSchedule[InetAddress](maxAge, timeUnit) with TakeFromPeek[InetAddress] {

  private val stack = new ConcurrentLinkedDeque[InetAddress]

  private val stackScala = stack asScala

  private val set = new TrieMap[InetAddress, InetAddress]()

  override def add(addr: InetAddress): Boolean = { // effective O(1)
    assert (addr != null)
    stack addFirst addr // to preserve order and avoid races
    scheduleRemove(addr)
    if (set.putIfAbsent(addr, addr).isEmpty) {
      propagate()
      true
    } else {
      stack remove addr // it may not be previously added address, the point is to do remove N times, where N - is count of loosed puts
      false
    }
  }

  override def peek: InetAddress =  // effective O(1)
    stackScala.find(set.contains) getOrElse nulll // skip removed and phantom elements

  override def remove(addr: InetAddress): Boolean = {
    assert (addr != null)
    //propagate(addr)
    set.remove(addr).map(stack.remove).nonEmpty
  } //O(N); can be easily changed to O(1) by removing `.map(stack.remove)`; however, it may affect memory consumption

}


/**
 * Vector-clock based solution
 * The synchronization here is much simpler and still non-blocking
 * @note access is slow for big collections
 */
class LinearAccessAndConstantPut[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val ees: ScheduledExecutorService)
  extends AddressCacheSchedule[InetAddress](maxAge, timeUnit) with TakeFromPeek[InetAddress] {

  private case class Info(seqNumber: Long, v: InetAddress, epoch: Long = System.currentTimeMillis() / 100)

  private val set = new TrieMap[InetAddress, Info]()

  private val clock = new AtomicLong(1) // this is the primitive vector clock: http://en.wikipedia.org/wiki/Vector_clock

  override def add(addr: InetAddress): Boolean = { // O(1)
    assert (addr != null)
    scheduleRemove(addr)
    val nn = clock.incrementAndGet()
    val info = Info(nn, addr)

    if (set.putIfAbsent(addr, info).isEmpty){
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

  override def remove(addr: InetAddress): Boolean = {
    assert (addr != null)
    //propagate(addr)
    set.remove(addr).nonEmpty
  } // O(1)

}

