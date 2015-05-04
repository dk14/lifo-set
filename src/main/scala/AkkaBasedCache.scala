import akka.actor.{Cancellable, ActorRefFactory, Props, Actor}
import java.util.concurrent.TimeUnit
import scala.collection._
import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.reflect.ClassTag
import akka.pattern._

/**
 * Akka-based implementation.
 * Same can be achieved with @synchronized or locks.
 *
 * P.S. Remove operation is O(N) here, but it's also possible to make it O(1) by removing the element from set only
 * and ignoring all heads, that do not exists in the set during peek/take.
 */
class AkkaBasedCache[InetAddress: ClassTag](maxAge: Long, timeUnit: TimeUnit, factor: Int = 1)(implicit sys: ActorRefFactory)
  extends AddressCache[InetAddress](maxAge, timeUnit) with TakeFromPeek[InetAddress] with AskSupport {

  private implicit val timeout = Duration(maxAge, timeUnit) * factor
  private implicit val akkaTimeout = akka.util.Timeout(timeout)

  assert (timeout.toSeconds <= 21474835)
  assert (timeout.toSeconds >= 0)

  private val model = new Model[InetAddress]
  import model._

  private val actor = sys.actorOf(Props(classOf[Underlying[InetAddress]], timeout / factor, model))

  override def add(addr: InetAddress): Boolean = {
    assert(addr != null)
    if (Await.result((actor ? Add(addr)).mapTo[Boolean], timeout)) {
      propagate()
      true
    } else false
  }

  override def peek: InetAddress = Await.result((actor ? Peek).mapTo[Option[InetAddress]], timeout).getOrElse(nulll)

  override def remove(addr: InetAddress): Boolean = {
    assert (addr != null)
    Await.result((actor ? Remove(addr)).mapTo[Boolean], timeout)
  }
  //"ask pattern" affects performance: http://stackoverflow.com/questions/20875837/why-isnt-ask-defined-directly-on-actorref-for-akka

}

private class Model[InetAddress] {

  sealed trait Command
  case class Add(addr: InetAddress) extends Command //O(1)
  case object Peek extends Command //O(1)
  case class Remove(addr: InetAddress) extends Command //O(N), but could be rewritten as O(1)

}


private class Underlying[InetAddress](timeout: FiniteDuration, model: Model[InetAddress]) extends Actor {
  import context._
  import model._

  /**
   * This is the state of the actor.
   * `LinkedList` + `Set` were chosen in preference to `LinkedHashSet`, as `LinkedList` is immutable and has constant access to last added element
   * @param set is used to check uniqueness
   * @param list is used to preserve order
   * @param sc cancels for schedules
   */

  case class State(set: Set[InetAddress] = Set.empty[InetAddress], list: List[InetAddress] = Nil, sc: Map[InetAddress, Cancellable] = Map.empty[InetAddress, Cancellable])

  def receive = process(State()) //initial state

  def process(s: State): Receive = {
    case Add(a) if !s.set.contains(a) =>
      val cancel = system.scheduler.scheduleOnce(timeout, self, Remove(a)) //it's not much precise - http://stackoverflow.com/questions/25845950/can-i-schedule-a-task-to-run-less-than-every-10-ms
      val newState = State(s.set + a, a :: s.list, s.sc + (a -> cancel))
      become(process(newState)) //Erlang-style state transition
      sender ! true
    case Add(a) => sender ! false
    case Peek => sender ! s.list.headOption
    case Remove(a) if s.set.contains(a) =>
      s.sc.get(a).map(_.cancel())
      val newState = State(s.set - a, s.list.filter(a !=), s.sc - a)
      become(process(newState))
      sender ! true
    case Remove(a) => sender ! false
  }
}
