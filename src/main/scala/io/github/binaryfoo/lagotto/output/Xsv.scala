package io.github.binaryfoo.lagotto.output

object Xsv {

  def toCsv(m: Seq[String]): String = toXsv(",", m)

  def toTsv(m: Seq[String]): String = toXsv("\t", m)

  def toXsv(separator: String, m: Seq[String]): String = m.mkString(separator)

  implicit class SeqToXsv(val m: Seq[String]) extends AnyVal {
    def toCsv: String = Xsv.toCsv(m)
    def toTsv: String = Xsv.toTsv(m)
  }

  implicit class IteratorSeqToXsv(val m: Iterator[Seq[String]]) extends AnyVal {
    def toCsv: String = m.map(Xsv.toCsv).mkString("\n")
    def toTsv: String = m.map(Xsv.toTsv).mkString("\n")
  }

}
