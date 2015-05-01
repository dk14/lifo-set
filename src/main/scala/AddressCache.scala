import java.util.concurrent.{LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec


abstract class AddressCache[InetAddress](val maxAge: Long, val timeUnit: TimeUnit) {

  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) <= 21474835) //akka can't have more, so this is restriction for all other implementations to preserve LSP
  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) > 0)

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

  private val queue = new LinkedBlockingQueue[InetAddress](1)

  protected def change(a: InetAddress) = queue.offer(a) //notify

  @tailrec final override def take(): InetAddress = {
    val p = peek
    if (p != null) {
      if(remove(p)) p else take() // remove; pickup new if already removed
    } else {
      queue.poll(maxAge, timeUnit) //wait; use maxAge as timeout
      take() //...endless wait
    }
  }

}