import java.util.concurrent._
import java.util.concurrent.atomic._
import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.util.Try
import Function._



/**
 * This should be the fastest one.
 *
 * It uses two collections
 *
 * It's thread-safe, sequentially-consistent in "read-your-own-writes" meaning
 *
 */
class ConstantAccessPut[InetAddress <: AnyRef](maxAge: Long, timeUnit: TimeUnit) extends AddressCache[InetAddress](maxAge, timeUnit) {

  private val stack = new ConcurrentLinkedDeque[InetAddress]

  private val stackScala = stack asScala

  private val set = new TrieMap[InetAddress, InetAddress]()

  override def add(addr: InetAddress): Boolean = if (set.putIfAbsent(addr, addr).isEmpty) { // O(1)
    stack addFirst addr
    true
  } else false


  override def peek: InetAddress =  // effective O(1)
    stackScala.find(set.contains) getOrElse nulll //skip removed elements

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
 */
class LinearAccessAndConstantPut[InetAddress <: AnyRef](maxAge: Long, timeUnit: TimeUnit) extends AddressCache[InetAddress](maxAge, timeUnit){

  private case class Info(seqNumber: Long, v: InetAddress, timestamp: Long = System.currentTimeMillis()) {override def toString = seqNumber.toString}

  private val set = new TrieMap[InetAddress, Info]()

  /*
    This is the primitive vector clock: http://en.wikipedia.org/wiki/Vector_clock
   */
  private val clock = new AtomicLong(1)

  override def add(addr: InetAddress): Boolean = { //O(1)
    val nn = clock.incrementAndGet()
    val info = Info(nn, addr)
    set.putIfAbsent(addr, info).isEmpty
  }

  override def peek: InetAddress =  //O(N)
    Try(set.maxBy(x => x._2.seqNumber -> x._2.timestamp)._1) getOrElse nulll //timestamp is used in case of `Long`'s overflow


  override def take(): InetAddress = set.remove(peek).map(_.v) getOrElse nulll //O(N)



  override def remove(addr: InetAddress): Boolean = set.remove(addr).nonEmpty
}

