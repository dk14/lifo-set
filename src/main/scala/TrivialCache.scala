import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

/**
 * Created by user on 5/2/15.
 */
class TrivialCache[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val ees: ScheduledExecutorService)
  extends AddressCacheSchedule[InetAddress](maxAge, timeUnit) with TakeFromPeek[InetAddress] {

  private val underlying = new scala.collection.mutable.LinkedHashSet[InetAddress]()

  override def add(addr: InetAddress): Boolean = synchronized {
    assert(addr != null)
    val r = underlying.add(addr)
    if(r) scheduleRemove(addr)
    propagate()
    r
  }

  override def peek: InetAddress = synchronized {  underlying.lastOption getOrElse nulll }

  override def remove(addr: InetAddress): Boolean = synchronized {
    assert(addr != null)
    underlying.remove(addr)
  }
}
