import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{LinkedBlockingQueue, BlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.concurrent.{Future, Promise}

abstract class AddressCache[InetAddress](val maxAge: Long, val timeUnit: TimeUnit) {


  assert (TimeUnit.SECONDS.convert(maxAge, timeUnit) <= 21474835) //akka can't have more, so this is restriction for all to preserve LSP
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

  val queue = new LinkedBlockingQueue[InetAddress](1)

  val lock = new ReentrantLock()
  val cond = lock.newCondition()

  def elementAdded(a: InetAddress) = queue.offer(a) //notify

  override def take(): InetAddress = {
    Option(peek) map { p =>
      if(remove(p)) p else take()
    } getOrElse {
      queue.poll(maxAge, timeUnit) //used maxAge as timeout
      take() //wait
    }
  }


}