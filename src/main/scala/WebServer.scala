import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import models.SyslogEvent
import scalikejdbc.async.AsyncConnectionPool
import scalikejdbc.async.{AsyncDB, AsyncDBSession}
import scalikejdbc.interpolation.SQLSyntax
import scalikejdbc._
import async._

import scala.concurrent.Future
import scala.io.StdIn
import scala.util.{Failure, Success}
import java.time._

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

import scalikejdbc.interpolation.SQLSyntax

final case class Result(items: List[SyslogEvent])

final case class Item(name: String, id: Long)
trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object DateFormat extends JsonFormat[java.util.Date] {
    def write(date: java.util.Date) = JsString(dateToIsoString(date))
    def read(json: JsValue) = json match {
      case JsString(rawDate) =>
        parseIsoDateString(rawDate)
          .fold(deserializationError(s"Expected ISO Date format, got $rawDate"))(identity)
      case error => deserializationError(s"Expected JsString, got $error")
    }
  }

  private val localIsoDateFormatter = new ThreadLocal[java.text.SimpleDateFormat] {
    override def initialValue() = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  }

  private def dateToIsoString(date: java.util.Date) =
    localIsoDateFormatter.get().format(date)

  private def parseIsoDateString(date: String): Option[java.util.Date] =
    scala.util.Try{ localIsoDateFormatter.get().parse(date) }.toOption
  implicit val eventFormat = jsonFormat10(SyslogEvent.apply)
  implicit val resultFormat = jsonFormat1(Result) // contains List[Item]
}

object WebServer extends Directives with JsonSupport {

  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    AsyncConnectionPool.singleton(
      "jdbc:postgresql://localhost:5432/maksim.davydov",
      "maksim.davydov",
      ""
    )

    val route =
      path("") {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
        }
      } ~
        pathPrefix("api") {
          path("logs") {
            get {
              parameters(
                'appname,
                'time.as[Long].?,
                'time_from.as[Long].?,
                'time_to.as[Long].?,
                'priority.as[Int].?,
                'hostname.?,
                'sourceType.?,
                'body.?,
                'fromId.as[Long].?
              ) { (appname, time, timeFrom, timeTo, priority, hostname, sourceType, body, fromId) =>
                onComplete(fetchLogs(
                  appname = appname,
                  time = time,
                  timeFrom = timeFrom,
                  timeTo = timeTo,
                  priority = priority,
                  hostname = hostname,
                  sourceType = sourceType,
                  body = body,
                  fromId = fromId
                )) {
                  case Success(l) =>
                    complete(Result(l))
                  case Failure(e) => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>${e.getMessage}}</h1>"))
                }
              }
            }
          }
        }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }

  def fetchLogs(appname: String,
                sourceType: Option[String],
                body: Option[String],
                hostname: Option[String],
                fromId: Option[Long],
                time: Option[Long],
                timeFrom: Option[Long],
                timeTo: Option[Long],
                priority: Option[Int]): Future[List[SyslogEvent]] = {
    val tableName = SQLSyntax.createUnsafely(s"syslog_events_of_$appname")
    val timeClause = getTableNamesWithTimeRanges(time, timeFrom, timeTo)

    val clauses = List(
      clause("body", "@@", body),
      clause("hostname", "=", hostname),
      clause("sourceType", "=", sourceType),
      clause("priority", "=", priority),
      clause("id", ">", fromId)
    ).flatten

    val whereClause = clauses.length match {
      case 0 => sqls""
      case _ => clauses.tail.fold(sqls"AND ${clauses.head}")((curr, acc) => sqls"$acc AND $curr")
    }

    AsyncDB.withPool { implicit s =>
      sql"""SELECT * FROM $tableName WHERE $timeClause $whereClause LIMIT 1000""".map(rs => {
        SyslogEvent(
          rs.int("priority"),
          rs.int("version"),
          rs.date("ts"),
          rs.string("host_name"),
          rs.string("proc_id"),
          appname,
          rs.string("msg_id"),
          rs.string("source_type"),
          rs.int("iana"),
          rs.string("body"),
        )
      }).list().future()
    }
  }

  def getTableNamesWithTimeRanges(time: Option[Long], timeFrom: Option[Long], timeTo: Option[Long]): SQLSyntax = {
    time match {
      case Some(ts) => getTableNameWithTimeRange(ts)
      case None => getTableNamesWithTimeRanges(timeFrom, timeTo)
    }
  }

  def clause[A](field: String, sign: String, body: Option[A]): Option[SQLSyntax] = {
    val f = SQLSyntax.createUnsafely(field)
    val s = SQLSyntax.createUnsafely(sign)
    body.map(x => sqls"$f $s $x")
  }

  def getTableNamesWithTimeRanges(timeFrom: Option[Long], timeTo: Option[Long]): SQLSyntax = {
    timeFrom.map(x => sqls"extract(epoch from ts) >= ${x}").getOrElse(sqls"") and timeTo.map(x => sqls"extract(epoch from ts) <= ${x}").getOrElse(sqls"")
  }

  def getTableNameWithTimeRange(time: Long): SQLSyntax = {
    val inst = Instant.ofEpochSecond(time)
    val zdt = ZonedDateTime.ofInstant(inst, ZoneOffset.UTC)
    val beginningOfDay = zdt.toLocalDate.atStartOfDay(ZoneId.of("UTC"))
    val endOfDay = beginningOfDay.plusDays(1).minusSeconds(1)

    sqls"extract(epoch from ts) BETWEEN ${beginningOfDay.toInstant.getEpochSecond} AND ${endOfDay.toInstant.getEpochSecond}"
  }
}