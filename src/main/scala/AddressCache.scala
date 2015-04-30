import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * Created by user on 4/29/15.
 */
abstract class AddressCache[InetAddress](maxAge: Long, timeUnit: TimeUnit) {

 // http://www.scala-lang.org/api/current/index.html#scala.collection.concurrent.Map
  // http://stackoverflow.com/questions/17540467/thread-safe-map-which-preserves-the-insertion-order
  def add(addr: InetAddress): Boolean

  def remove(addr: InetAddress): Boolean

  def peek: InetAddress

  def take(): InetAddress

  protected def nulll = null.asInstanceOf[InetAddress]
}
