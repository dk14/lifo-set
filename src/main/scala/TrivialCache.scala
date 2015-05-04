import java.util.concurrent.{ScheduledExecutorService, TimeUnit}


class TrivialCache[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val es: ScheduledExecutorService)
  extends AddressCacheWithScheduledExecutor[InetAddress](maxAge, timeUnit) with AddressCacheSchedule[InetAddress] with TakeFromPeek[InetAddress] {

  private val underlying = new scala.collection.mutable.LinkedHashSet[InetAddress]()

  override def add(addr: InetAddress): Boolean = synchronized {
    assert(addr != null)
    val r = underlying.add(addr)
    if (r) scheduleRemove(addr)
    propagate()
    r
  }

  override def peek: InetAddress = synchronized {  underlying.lastOption getOrElse nulll }

  override def remove(addr: InetAddress): Boolean = synchronized {
    assert(addr != null)
    removeScheduler(addr)
    underlying.remove(addr)
  }
}
