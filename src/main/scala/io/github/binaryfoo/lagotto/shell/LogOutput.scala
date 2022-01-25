package io.github.binaryfoo.lagotto.shell

import java.io.PrintWriter

import io.github.binaryfoo.lagotto.highlight.{AnsiMarkup, XmlHighlighter}
import io.github.binaryfoo.lagotto._
import io.github.binaryfoo.lagotto.output.DeadSimpleJsonWriter

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

trait OutputFormat {
  def contentType: ContentType
  def header(): Option[String]
  def apply(e: LogEntry): Option[String]
  def footer(): Option[String]
}

object OutputFormat {

  def fieldsFor(f: OutputFormat): Seq[FieldExpr] = {
    f match {
      case Tabular(fields, _) => fields
      case _ => Seq()
    }
  }

  implicit class PipeToOutputFormatIterator(val it: Iterator[LogEntry]) extends AnyVal {
    def pipeTo(f: OutputFormat, out: PrintWriter): Unit = {
      f.header().foreach(out.println)
      it.flatMap(f.apply).foreach(out.println)
      f.footer().foreach(out.println)
    }
  }
}

sealed trait ContentType {
  def mimeType: String
}
object PlainText extends ContentType {
  override val mimeType: String = "text/plain; charset=UTF-8"
}
object RichText extends ContentType {
  override val mimeType: String = "text/plain; charset=UTF-8"
}
object Html extends ContentType {
  override val mimeType: String = "text/html; charset=UTF-8"
}
object Svg extends ContentType {
  override val mimeType: String = "image/svg+xml; charset=UTF-8"
}
object Json extends ContentType {
  override val mimeType: String = "text/json; charset=UTF-8"
}

object FullText extends OutputFormat {
  override def header(): Option[String] = None
  override def apply(e: LogEntry): Option[String] = Some(e.lines)
  override def footer(): Option[String] = None
  override val contentType: ContentType = PlainText
}

object HighlightedText extends OutputFormat {
  override def header(): Option[String] = None
  override def apply(e: LogEntry): Option[String] = {
    e match {
      case j: JposEntry => Some(XmlHighlighter.highlight(e.lines, AnsiMarkup))
      case _ => Some(e.lines)
    }
  }
  override def footer(): Option[String] = None
  override val contentType: ContentType = PlainText
}

trait FieldList {
  def fields: Seq[FieldExpr]
  def fieldNames: Seq[String] = fields.map(_.toString())
}

case class Tabular(fields: Seq[FieldExpr], tableFormatter: TableFormatter = DelimitedTableFormat(",")) extends OutputFormat with FieldList {
  override def header(): Option[String] = tableFormatter.header(fields.map(_.toString()))
  override def apply(e: LogEntry): Option[String] = {
    val row = e.exprToSeq(fields)
    if (row.exists(_.nonEmpty)) {
      tableFormatter.row(row)
    } else {
      None
    }
  }
  override def footer(): Option[String] = tableFormatter.footer()
  override def contentType: ContentType = tableFormatter.contentType
}

case class WildcardTable(tableFormatter: TableFormatter = DelimitedTableFormat(",")) extends OutputFormat with FieldList {
  var fields: Seq[FieldExpr] = _
  override def header(): Option[String] = {
    if (fields == null) {
      throw new IAmSorryDave("Need at least one output row before printing header")
    }
    tableFormatter.header(fieldNames)
  }
  override def apply(e: LogEntry): Option[String] = {
    val row = if (fields == null) {
      val (names: Seq[String], values: Seq[String]) = e.exportAsSeq.unzip(p => (p._1, p._2))
      fields = names.map(PrimitiveExpr)
      values
    } else {
      e.exprToSeq(fields)
    }
    tableFormatter.row(row)
  }
  override def footer(): Option[String] = tableFormatter.footer()
  override def contentType: ContentType = tableFormatter.contentType
}

trait TableFormatter {
  def header(fields: Seq[String]): Option[String]
  def row(row: Seq[String]): Option[String]
  def footer(): Option[String] = None
  def contentType: ContentType = PlainText
  def liveVersion: TableFormatter = this
}

case class DelimitedTableFormat(delimiter: String) extends TableFormatter {
  override def header(fields: Seq[String]): Option[String] = Some(fields.map(_.replace(delimiter, " ")).mkString(delimiter))
  override def row(row: Seq[String]): Option[String] = Some(row.mkString(delimiter))
}

object DelimitedTableFormat {
  val Tsv = DelimitedTableFormat("\t")
  val Csv = DelimitedTableFormat(",")
}

object JiraTableFormat extends TableFormatter {
  override def header(fields: Seq[String]): Option[String] = Some(fields.mkString("||", "||", "||"))
  override def row(row: Seq[String]): Option[String] = Some(row.map(v => if (v.isEmpty) " " else v).mkString("|", "|", "|"))
}

object HtmlTableFormat extends TableFormatter {
  private val pre =
    """<html>
      |<head>
      |<style>
      |a {
      |  text-decoration: none
      |}
      |</style>
      |</head>""".stripMargin
  private val post = "</body></html>"
  override def header(fields: Seq[String]): Option[String] = Some(fields.mkString(s"$pre\n<table>\n<thead><tr><th>", "</th><th>", "</th></tr></thead>\n<tbody>"))
  override def row(row: Seq[String]): Option[String] = Some(row.mkString("<tr><td>", "</td><td>", "</td></tr>"))
  override def footer(): Option[String] = Some(s"</tbody>\n</table>$post")
  override val contentType: ContentType = Html
}

case class InfluxDBFormat(measurement: String = "", tags: Seq[FieldExpr] = Seq.empty, fields: Seq[FieldExpr] = Seq.empty) extends OutputFormat {
  override def contentType: ContentType = PlainText
  override def footer(): Option[String] = None
  override def header(): Option[String] = None

  def escape(s: String): String = {
    if (s == null) {
      s
    } else {
      s.replaceAll(" ", "\\\\ ")
    }
  }

  override def apply(e: LogEntry): Option[String] = {
    val tagsAndValues = tags.map(f => f.field + "=" + escape(f(e))).mkString(",")
    val fieldsAndValues = fields.map(f => f.field + "=" + escape(f(e))).mkString(",")
    val nanoTimestamp = e.timestamp.getMillis * 1000 * 1000
    Some(s"$measurement,$tagsAndValues $fieldsAndValues $nanoTimestamp")
  }
}

case class JposTimeline() extends OutputFormat {

  private val sessions = mutable.Map[String, ArrayBuffer[String]]()

  override def contentType: ContentType = PlainText
  override def header(): Option[String] = Some("[")
  override def footer(): Option[String] = {
    val b = new StringBuilder()
    for ((socket, session) <- sessions) {
      b.append(sessionToJson(socket, session)).append(",\n")
    }
    b.delete(b.length - 2, b.length)
    Some(b.append("]").toString())
  }

  override def apply(e: LogEntry): Option[String] = {
    e match {
      case jposEntry: JposEntry if jposEntry.realm.socket != "" && jposEntry.msgType != null =>
        val socket = jposEntry.realm.socket
        val entriesForSession = sessions.getOrElseUpdate(socket, new ArrayBuffer[String]())
        entriesForSession += entryToJson(jposEntry)
        if (jposEntry.msgType == "session-end") {
          sessions.remove(socket)
          Some(sessionToJson(socket, entriesForSession) + ",")
        } else {
          None
        }
      case _ =>
        None
    }
  }

  private def sessionToJson(socket: String, entries: Seq[String]): String = {
    val w = new DeadSimpleJsonWriter()
    w.add("socket", socket)
    w.add("times", entries)
    w.done()
    w.toString
  }

  private def entryToJson(e: JposEntry) = {
    val w = new DeadSimpleJsonWriter()
    w.add("starting_time", e.timestamp.getMillis - e.lifespan.getOrElse(0))
    w.add("ending_time", e.timestamp.getMillis)
    w.add("msgType", e.msgType)
    w.add("summary", summary(e))
    w.add("src", linkTo(e))
    w.done()
    w.toString
  }

  private def summary(e: JposEntry): String = {
    val mti = e.get("mti").getOrElse("")
    val nmic = e.get("70").getOrElse("")
    val stan = e.get("1").getOrElse("")
    s"$mti $nmic $stan"
  }

  private def linkTo(e: LogEntry): String = {
    e.source match {
      case FileRef(file, line) => SourceHrefExpr.urlFor(file, line, e)
      case r => r.toString
    }
  }
}
