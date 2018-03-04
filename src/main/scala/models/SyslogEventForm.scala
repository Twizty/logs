package models

import akka.util.ByteString

case class SyslogEventForm(
  priority: ByteString,
  version: ByteString,
  timestamp: ByteString,
  hostName: ByteString,
  appName: ByteString,
  procId: ByteString,
  msgId: ByteString,
  sourceType: ByteString,
  iana: ByteString,
  body: ByteString,
)
