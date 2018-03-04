package tcp

import akka.actor.Actor
import akka.io.Tcp
import scalikejdbc._
import async._
import akka.util.ByteString
import cats.data.{StateT, Validated}
import cats.implicits._
import scalikejdbc.async.{AsyncDB, AsyncDBSession}
import scalikejdbc.interpolation.SQLSyntax

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import ExecutionContext.Implicits.global

object EchoHandler {
  val format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
}

class EchoHandler(lookup: java.util.concurrent.ConcurrentHashMap[String, Unit]) extends Actor {
  import models._

  type FormValidator[A] = Either[String, A]
  type OptionalState[S, A] = StateT[Option, S, A]

  import Tcp._
  def receive = {
    case PeerClosed     => context stop self
    case Received(data) =>
      val s = sender()
      val res = buildSyslogEventForm.runA((data, 0))
      res match {
        case None => s ! Write(ByteString("Invalid format\n"))
        case Some(c) =>
          val ev = validateSyslogEventForm(c).toValidated

          ev match {
            case Validated.Valid(ve) =>
              saveLog(ve) onComplete {
                case Success(_) => s ! Write(data.reverse.drop(1) ++ ByteString("\n"))
                case Failure(_) =>
                  s ! Write(ByteString("Can't save log to database"))
              }
            case Validated.Invalid(msg) => s ! Write(ByteString(msg))
          }
      }
  }

  def hasApp(app: String): Boolean = {
    lookup.get(app) != null
  }

  def save(se: SyslogEvent, tableName: SQLSyntax)(implicit s: AsyncDBSession): Future[Int] = {
    sql"""INSERT INTO ${tableName} (priority, version, ts, host_name, proc_id, msg_id, source_type, iana, body)
            VALUES (${se.priority}, ${se.version}, ${se.ts}, ${se.hostName}, ${se.procId}, ${se.msgId}, ${se.sourceType}, ${se.iana}, ${se.body})""".update.future()
  }

  def createTable(tableName: SQLSyntax)(implicit s: AsyncDBSession): Future[Int] = {
    val f = sql"""CREATE TABLE IF NOT EXISTS ${tableName} (LIKE syslog_events EXCLUDING ALL INCLUDING INDEXES) INHERITS (syslog_events)""".update.future()

    f.onComplete { case Success(_) => lookup.put(tableName, ()) }

    f
  }

  def saveLog(se: SyslogEvent): Future[Int] = {
    val suffix = se.appName
    val tableName = SQLSyntax.createUnsafely(s"syslog_events_of_$suffix")

    if (hasApp(se.appName)) {
      AsyncDB.withPool { implicit s =>
        save(se, tableName)
      }
    } else {
      AsyncDB.localTx { implicit tx =>
        for {
          _ <- createTable(tableName)
          res <- save(se, tableName)
        } yield res
      }
    }

//    AsyncDB.withPool { implicit s =>
//      sql"""CREATE TABLE IF NOT EXISTS ${tableName} (LIKE %s EXCLUDING ALL INCLUDING INDEXES) INHERITS syslog_events)""".update.future().flatMap { _ =>
//        sql"""INSERT INTO ${tableName} (priority, version, ts, host_name, proc_id, msg_id, source_type, iana, body)
//            VALUES (${se.priority}, ${se.version}, ${se.ts}, ${se.hostName}, ${se.procId}, ${se.msgId}, ${se.sourceType}, ${se.iana}, ${se.body})
//        """.update.future()
//      }
//    }

//    AsyncDB.withPool { implicit s =>
//      withSQL {
//        insert.into(SyslogEvent).namedValues(
//          SyslogEvent.column.priority -> se.priority,
//          SyslogEvent.column.version -> se.version,
//          SyslogEvent.column.ts -> se.ts,
//          SyslogEvent.column.hostName -> se.hostName,
//          SyslogEvent.column.appName -> se.appName,
//          SyslogEvent.column.procId -> se.procId,
//          SyslogEvent.column.msgId -> se.msgId,
//          SyslogEvent.column.sourceType -> se.sourceType,
//          SyslogEvent.column.iana -> se.iana,
//          SyslogEvent.column.body -> se.body,
//        )
//      }.update().future()
//    }
  }

  def buildSyslogEventForm: OptionalState[(ByteString, Int), SyslogEventForm] = {
    for {
      priority <- readChunk('>')
      version <- readChunk(' ')
      timestamp <- readChunk(' ')
      hostName <- readChunk(' ')
      appName <- readChunk(' ')
      procId <- readChunk(' ')
      msgId <- readChunk(' ')
      sourceType <- readChunk('@')
      iana <- readChunk(' ')
      body <- readChunk(' ')
    } yield SyslogEventForm(
      priority.slice(1, priority.length),
      version,
      timestamp,
      hostName,
      appName,
      procId,
      msgId,
      sourceType.slice(1, sourceType.length),
      iana,
      body.slice(0, priority.length-2)
    )
  }

  def readChunk(delimiter: Char): OptionalState[(ByteString, Int), ByteString] = {
    StateT[Option, (ByteString, Int), ByteString] { state =>
      val (data, offset) = state

      if (data.length <= offset) {
        None
      } else {
        val res = data.slice(offset, data.length - 1).takeWhile(_ != delimiter)
        Some(((data, offset + res.length + 1), res))
      }
    }
  }

  def validateSyslogEventForm(f: SyslogEventForm): FormValidator[SyslogEvent] = {
    for {
      priority <- validatePriority(f.priority)
      version <- validateVersion(f.version)
      timestamp <- validateTimestamp(f.timestamp)
      iana <- validateIana(f.iana)
    } yield SyslogEvent(
      priority,
      version,
      timestamp,
      f.hostName.utf8String,
      f.appName.utf8String,
      f.procId.utf8String,
      f.msgId.utf8String,
      f.sourceType.utf8String,
      iana,
      f.body.utf8String,
    )
  }

  def validatePriority(pr: ByteString): FormValidator[Int] = {
    parseInt(pr.utf8String).toRight("Can't parse priority")
  }

  def validateVersion(vr: ByteString): FormValidator[Int] = {
    parseInt(vr.utf8String).toRight("Can't parse version")
  }

  def validateIana(iana: ByteString): FormValidator[Int] = {
    parseInt(iana.utf8String).toRight("Can't parse iana")
  }

  def validateTimestamp(ts: ByteString): FormValidator[java.util.Date] = {
    parseTime(ts.utf8String).toRight("Can't parse timestamp")
  }

  def parseInt(i: String): Option[Int] = {
    try {
      Some(i.toInt)
    } catch {
      case _: Exception => None
    }
  }

  def parseTime(t: String): Option[java.util.Date] = {
    try {
      Some(EchoHandler.format.parse(t))
    } catch {
      case _: Exception => None
    }
  }
}