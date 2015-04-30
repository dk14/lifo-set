import akka.actor.{Props, ActorSystem, Actor}
import akka.pattern._
import java.util.concurrent.TimeUnit
import scala.collection._
import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.reflect.ClassTag

/**
 * Akka-based implementation.
 * Same can be achieved with @synchronized or locks.
 *
 * P.S. Remove operation is O(N) here, but it's also possible to make it O(1) by removing the element from set only
 * and ignoring all heads, that do not exists in the set during peek/take.
 */
class AkkaBasedCache[InetAddress: ClassTag](maxAge: Long, timeUnit: TimeUnit)(implicit sys: ActorSystem)
  extends AddressCache[InetAddress](maxAge, timeUnit){

  private implicit val timeout = Duration(maxAge, timeUnit)
  private implicit val akkaTimeout = akka.util.Timeout(timeout)

  val model = new Model[InetAddress]
  import model._

  val actor = sys.actorOf(Props(classOf[Underlying[InetAddress]], timeout, model))

  override def add(addr: InetAddress): Boolean = Await.result((actor ? Add(addr)).mapTo[Boolean], timeout)
  override def peek: InetAddress = Await.result((actor ? Peek).mapTo[Option[InetAddress]], timeout).getOrElse(nulll)
  override def take(): InetAddress = Await.result((actor ? Take).mapTo[Option[InetAddress]], timeout).getOrElse(nulll)
  override def remove(addr: InetAddress): Boolean = Await.result((actor ? Remove(addr)).mapTo[Boolean], timeout)

}

class Model[InetAddress] {

  sealed trait Command
  case class Add(addr: InetAddress) extends Command //O(1)
  case object Peek extends Command //O(1)
  case object Take extends Command //O(1)
  case class Remove(addr: InetAddress) extends Command //O(N), but could be rewritten as O(1)

}


class Underlying[InetAddress](timeout: FiniteDuration, model: Model[InetAddress]) extends Actor {
  import context._
  import model._

  /**
   * This is the state of the actor.
   * LinkedList + Set were chosen in preference to LinkedHashSet as the last one has linear access to last added element
   * @param set is used to check uniqueness
   * @param list is used to preserve order
   */

  case class State(set: Set[InetAddress] = Set.empty[InetAddress], list: List[InetAddress] = Nil)

  def receive = process(State())

  def process(s: State): Receive = {
    case Add(a) if !s.set.contains(a) =>
      system.scheduler.scheduleOnce(timeout, self, Remove(a))
      val newState = State(s.set + a, a :: s.list)
      become(process(newState)) //Erlang-style state transition
      sender ! true
    case Add(a) => sender ! false
    case Peek => sender ! s.list.headOption
    case Take =>
      val res = s.list.headOption map { h =>
        val newState = State(s.set - h, s.list.tail)
        become(process(newState))
        h
      }
      sender ! res
    case Remove(a) if s.set.contains(a) =>
      val newState = State(s.set - a, s.list.filter(a !=))
      become(process(newState))
      sender ! true
    case Remove(a) => sender ! false
  }
}
