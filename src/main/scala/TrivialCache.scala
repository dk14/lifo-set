import java.util.concurrent.{ScheduledExecutorService, TimeUnit}


class TrivialCache[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val es: ScheduledExecutorService)
  extends AddressCacheWithScheduledExecutor[InetAddress](maxAge, timeUnit) with AddressCacheSchedule[InetAddress] with TakeFromPeek[InetAddress] {

  private val underlying = new scala.collection.mutable.LinkedHashSet[InetAddress]() //Scala has no Collections.synchronizedSet(), SynchronizedSet trait is deprecated

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

class TrivialCacheFast[InetAddress](maxAge: Long, timeUnit: TimeUnit)(implicit val es: ScheduledExecutorService)
  extends AddressCacheWithScheduledExecutor[InetAddress](maxAge, timeUnit) with AddressCacheSchedule[InetAddress] with TakeFromPeek[InetAddress] {

  private val set = new scala.collection.mutable.HashSet[InetAddress]() //Scala has no Collections.synchronizedSet(), SynchronizedSet trait is deprecated
  private val list = new scala.collection.mutable.ListBuffer[InetAddress]()


  override def add(addr: InetAddress): Boolean = synchronized {
    assert(addr != null)
    if (set.add(addr)) {
      propagate()
      list.prepend(addr)
      scheduleRemove(addr)
      true
    } else false
  }

  override def peek: InetAddress = synchronized {  list.headOption getOrElse nulll }

  override def remove(addr: InetAddress): Boolean = synchronized {
    assert(addr != null)
    removeScheduler(addr)
    list -= addr
    set.remove(addr) //linear
  }
}
