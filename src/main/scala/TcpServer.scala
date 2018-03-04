import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import scalikejdbc._
import async._

import scala.concurrent._
import duration._
import ExecutionContext.Implicits.global

// <PRI>VER TIMESTAMP HOSTNAME APP-NAME PROCID MSGID [SOURCETYPE@NM_IANA key1="val1" key2="val2" etc.]

object TcpServer {
  def main(args: Array[String]): Unit = {
    AsyncConnectionPool.singleton(
      "jdbc:postgresql://localhost:5432/maksim.davydov",
      "maksim.davydov",
      ""
    )

    val system: ActorSystem = ActorSystem("helloAkka")
    val server = system.actorOf(Props[tcp.Server])
  }
}
