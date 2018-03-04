package models

import scalikejdbc._

case class SyslogEvent(
  priority: Int,
  version: Int,
  ts: java.util.Date,
  hostName: String,
  appName: String,
  procId: String,
  msgId: String,
  sourceType: String,
  iana: Int,
  body: String,
)

object SyslogEvent extends SQLSyntaxSupport[SyslogEvent] {
  override val tableName = "syslog_events"
  override val columnNames = Seq("priority", "version", "ts", "host_name", "proc_id", "msg_id", "source_type", "iana", "body")
}