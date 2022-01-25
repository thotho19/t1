package io.github.binaryfoo.lagotto.reader

import java.io._
import java.util.concurrent.ArrayBlockingQueue

import io.github.binaryfoo.lagotto._

import scala.annotation.tailrec
import scala.collection.AbstractIterator
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.io.{BufferedSource, Source}
import scala.util.Try

trait SkeletonLogReader[T <: LogEntry] {

  def progressMeter: ProgressMeter

  def readFilesOrStdIn(args: Iterable[String], follow: Boolean = false, stdin: InputStream = System.in): Iterator[T] = {
    if (args.isEmpty)
      read(stdin, StdInRef())
    else
      read(args.map(new File(_)), follow)
  }

  def read(files: File*): Iterator[T] = read(files.toIterable, follow = false)

  def read(files: Iterable[File], follow: Boolean): Iterator[T] = {
    progressMeter.startRun(files.size)
    files.toIterator.flatMap { f =>
      val in = if (follow) TailInputStream(f) else FileIO.open(f)
      read(in, FileRef(f))
    }
  }

  /**
   * @deprecated Use read(InputStream,String) instead.
   */
  def read(source: Source): Iterator[T] = read(source, "")

  /**
   * @deprecated Use read(InputStream,String) instead.
   */
  def read(source: Source, sourceName: String): Iterator[T] = {
    source match {
      case s: BufferedSource =>
        val field = classOf[BufferedSource].getDeclaredField("inputStream")
        field.setAccessible(true)
        read(field.get(s).asInstanceOf[InputStream], FileRef(new File(sourceName)))
    }
  }

  final def read(in: InputStream, sourceName: SourceRef = StdInRef()): Iterator[T] = {
    readWithProgress(new ProgressInputStream(in, progressMeter, sourceName))
  }

  def readWithProgress(in: ProgressInputStream): Iterator[T]

}

/**
 * Kicks off a daemon thread to read lines. Parsing is delegated to the default ExecutionContext.
 *
 * @param strict Whinge with an exception on unexpected input
 * @param keepFullText If false keep only the parsed fields. If true keep the full text of every record. Maybe this should be removed.
 * @param logType
 * @tparam T
 */
case class LogReader[T <: LogEntry](strict: Boolean = false, keepFullText: Boolean = true, progressMeter: ProgressMeter = NullProgressMeter, logType: LogType[T] = JposLog) extends SkeletonLogReader[T] {

  override def readWithProgress(source: ProgressInputStream): Iterator[T] = new LogEntryIterator(source)

  private val processors = Runtime.getRuntime.availableProcessors()

  class LogEntryIterator(source: ProgressInputStream) extends AbstractIterator[T] {

    private val queue = new ArrayBlockingQueue[Future[T]](processors * processors * 2)
    private var current: T = null.asInstanceOf[T]
    private var started = false
    private var done = false
    private val reader = new Thread(new Runnable {
      private val lines = new LineIterator(source, strict, keepFullText)

      override def run(): Unit = {
        var more = true
        do {
          val f = try {
            val entry = logType.readLinesForNextRecord(lines)
            if (entry != null) {
              parseInTheFuture(entry)
            } else {
              more = false
              Future.successful(null.asInstanceOf[T])
            }
          }
          catch {
            case e: Exception => Future.failed(e)
          }
          queue.put(f)
        } while (more)
      }
    }, s"${source.sourceRef.name}-reader")
    reader.setDaemon(true)

    override def hasNext: Boolean = {
      ensureStartedAndCurrent()
      !done
    }

    override def next(): T = {
      ensureStartedAndCurrent()
      consumeNext
    }

    private def readNext() = {
      if (!done) {
        current = readNextWithRetry()
        done = current == null
        source.publishProgress(done)
        if (done) {
          source.close()
        }
      }
    }

    private def consumeNext: T = {
      val v = current
      current = null.asInstanceOf[T]
      v
    }

    @tailrec
    private def readNextWithRetry(): T = {
      val future = queue.take()
      Await.ready(future, 1.minute)
      val maybe = future.value.get
      if (strict && maybe.isFailure) {
        maybe.get
      } else {
        if (maybe.isSuccess) maybe.get else readNextWithRetry()
      }
    }

    @inline
    private def ensureStartedAndCurrent() = {
      if (!started) {
        reader.start()
        started = true
      }
      if (current == null)
        readNext()
    }

    override def foreach[U](f: (T) => U): Unit = {
      try {
        super.foreach(f)
      }
      finally {
        close()
      }
    }

    override def finalize(): Unit = close()

    def close(): Unit = {
      reader.interrupt()
      source.close()
    }

    @inline
    private def parseInTheFuture(entry: logType.P): Future[T] = {
      Future {
        try {
          logType.parse(entry)
        }
        catch {
          case e: Exception => throw new IAmSorryDave(s"Failed record ending at ${entry.source}", e)
        }
      }
    }

  }
}

case class SingleThreadLogReader[T <: LogEntry](strict: Boolean = false, keepFullText: Boolean = true, progressMeter: ProgressMeter = NullProgressMeter, logType: LogType[T] = JposLog) extends SkeletonLogReader[T] {
  override def readWithProgress(source: ProgressInputStream): Iterator[T] = new EntryIterator[T](source, strict, keepFullText, logType)
}

/**
 * Each entry may consume more than one line.
 */
class EntryIterator[T <: LogEntry](val source: ProgressInputStream, val strict: Boolean = false, val keepFullText: Boolean = true, logType: LogType[T] = JposLog) extends AbstractIterator[T] {

  private val lines = new LineIterator(source, strict, keepFullText)
  private var current = readNext()

  override def next(): T = {
    val c = current
    current = readNext()
    c
  }

  override def hasNext: Boolean = current != null

  private def readNext(): T = {
    val next = readNextWithRetry()
    val done = next == null
    source.publishProgress(done)
    if (done)
      source.close()
    next
  }

  @tailrec
  private def readNextWithRetry(): T = {
    val maybe = Try(logType.apply(lines))
    if (strict && maybe.isFailure || maybe.isSuccess) {
      maybe.get
    } else {
      readNextWithRetry()
    }
  }
}

/**
 * Adds a line number, name and a single line push back over Source.getLines().
 */
class LineIterator(in: ProgressInputStream, val strict: Boolean = false, val keepFullText: Boolean = true) extends AbstractIterator[String] with BufferedIterator[String] {

  private val lines = new BufferedReader(new InputStreamReader(in))
  private var linesRead = 0
  private var currentLineNo = 0
  private var current: String = null

  readNext()

  /**
   * Zero when next() has not been called.
   * After next() has been called, the line number for the most recently returned value of next().
   */
  def lineNumber: Int = currentLineNo

  /**
   * @return Line number and file name for most recently returned value of next().
   */
  def sourceRef: SourceRef = in.sourceRef.at(currentLineNo)

  /**
   * @return Line number and file name for most recently returned value of head.
   */
  def headRef: SourceRef = in.sourceRef.at(linesRead)

  def hasNext = current != null || readNext()

  def next(): String = {
    if (current == null) readNext()
    val c = current
    currentLineNo = linesRead
    current = null
    c
  }

  def head: String = current

  private def readNext(): Boolean = {
    current = lines.readLine()
    val readOne = current != null
    if (readOne) {
      linesRead += 1
    }
    readOne
  }
}
