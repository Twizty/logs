package tcp

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, Props}
import akka.io.{IO, Tcp}
import akka.io.Tcp._

class Server extends Actor with ActorLogging {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 1338))

  var lookup = new java.util.concurrent.ConcurrentHashMap[String, Unit]()

  def receive = {
    case b @ Bound(localAddress) =>
      log.debug(s"bound to $localAddress")
    case CommandFailed(_: Bind) ⇒ context stop self

    case c @ Connected(remote, local) =>
      val handler = context.actorOf(Props(classOf[EchoHandler], lookup))
      val connection = sender()
      connection ! Register(handler)
  }
}
