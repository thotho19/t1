package io.github.binaryfoo.lagotto.reader

import java.util

import com.typesafe.config.{ConfigObject, ConfigValue, Config}
import io.github.binaryfoo.lagotto.{SourceRef, LogEntry}

import scala.collection.{mutable, JavaConversions}
import JavaConversions.asScalaBuffer
import JavaConversions.asScalaSet

/**
 * Convert one or more lines of text into a LogEntry instance.
 *
 * The default apply() implementation identifies the set of lines to parse into a single record and does the work of parsing.
 * The split two steps: readLinesForNexRecord() and parse() allows the reading to be done by a single thread whilst
 * the grunt work of parsing is split over N threads.
 *
 * @tparam T The specific type of LogEntry.
 */
trait LogType[+T <: LogEntry] extends (LineIterator => T) {
  type P <: Sourced

  def canParse(firstLine: String): Boolean = true

  def readLinesForNextRecord(it: LineIterator): P

  def parse(s: P): T

  def apply(it: LineIterator): T = {
    val record = readLinesForNextRecord(it)
    if (record != null) parse(record)
    else null.asInstanceOf[T]
  }
}

trait Sourced {
  def source: SourceRef
}

case class TextAndSource(text: String, source: SourceRef) extends Sourced

case class LineSet(lines: Seq[String], fullText: String, source: SourceRef) extends Sourced

case class PreParsed[E](entry: E, source: SourceRef) extends Sourced

/**
 * Load the set of log types from a configuration file.
 */
object LogTypes {

  type LogTypeMap = Map[String, LogType[LogEntry]]

  def lookup(config: Config, name: Option[String]) = {
    val logTypes = LogTypes.load(config)
    name.map(logTypes(_)).getOrElse(LogTypes.auto(config, logTypes))
  }

  /**
   * LogType that sniffs first few characters of each line to work out what it's reading.
   */
  def auto(config: Config): AutoDetectLog = auto(config, load(config))

  def auto(config: Config, logTypes: LogTypeMap): AutoDetectLog = {
    val autoTypes = JavaConversions.asScalaBuffer(config.getStringList("autoDetectLogTypes"))
    new AutoDetectLog(logTypes.list(autoTypes))
  }
  
  def load(config: Config): LogTypeMap = {
    load(config.getObject("logTypes"))
  }

  def load(types: ConfigObject): LogTypeMap = {
    types.entrySet().map { e =>
      val name = e.getKey
      val v = e.getValue
      val map = v.unwrapped().asInstanceOf[util.Map[String, ConfigValue]]
      val logType = if (map.containsKey("class")) {
        val clazz = map.get("class").asInstanceOf[String]
        val args = if (map.containsKey("args"))
          asScalaBuffer(map.get("args").asInstanceOf[util.List[Object]])
        else
          mutable.Buffer.empty[Object]
        newInstance(clazz, args)
      } else {
        val clazz = map.get("object").asInstanceOf[String]
        newObject(clazz)
      }
      name -> logType
    }.toMap
  }

  def newInstance(name: String, args: mutable.Buffer[Object]): LogType[LogEntry] = {
    val constructor = Class.forName(name).getConstructors()(0)
    val preparedArgs = constructor.getParameterTypes.zip(args).map {
      case (t, v) if t == classOf[Boolean] =>  java.lang.Boolean.valueOf(v.toString)
      case (t, v) if t == classOf[String] => v.toString
      case (t, v) if t == classOf[Char] => Character.valueOf(v.toString.charAt(0))
      case (t, v) if t == classOf[LineRecogniser] => newObject[LineRecogniser](v.toString)
    }
    constructor.newInstance(preparedArgs :_*).asInstanceOf[LogType[LogEntry]]
  }

  def newObject[T](name: String): T = {
    Class.forName(name + "$").getField("MODULE$").get(null).asInstanceOf[T]
  }

  implicit class RichLogTypes(val m: LogTypeMap) extends AnyVal {

    def list(names: Seq[String]): Seq[LogType[LogEntry]] = {
      names.map(m(_))
    }
  }
}
