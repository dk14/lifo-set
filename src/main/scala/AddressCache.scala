import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

abstract class AddressCache[InetAddress](maxAge: Long, timeUnit: TimeUnit) {

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

  override def take(): InetAddress = {
    val p = peek
    Option(p).map(remove)
    p
  }


}