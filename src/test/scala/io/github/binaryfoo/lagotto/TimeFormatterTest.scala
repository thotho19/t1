package io.github.binaryfoo.lagotto

import io.github.binaryfoo.lagotto.TimeFormatter.formatterFor
import org.joda.time.{DateTimeZone, DateTime}
import org.scalatest.{Matchers, FlatSpec}

class TimeFormatterTest extends FlatSpec with Matchers {

  "time(millis)" should "print millis since epoch" in {
    val format = formatterFor("time(millis)")
    format.print(new DateTime(1970, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC)) shouldBe "0"
  }

  it should "parse millis since epoch" in {
    val format = formatterFor("time(millis)")
    format.parseDateTime("0") shouldBe new DateTime(0)
  }

  "time(ms)" should "print millis since epoch" in {
    val format = formatterFor("time(ms)")
    format.print(new DateTime(1970, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC)) shouldBe "0"
  }

  it should "parse millis since epoch" in {
    val format = formatterFor("time(ms)")
    format.parseDateTime("0") shouldBe new DateTime(0)
  }

  "time(s)" should "print seconds since epoch" in {
    val format = formatterFor("time(s)")
    format.print(new DateTime(61000)) shouldBe "61"
  }

  it should "parse seconds since epoch" in {
    val format = formatterFor("time(s)")
    format.parseDateTime("61") shouldBe new DateTime(61000)
  }

  "time(SSS)" should "print milliseconds of second (a value between 0 and 999)" in {
    val format = formatterFor("time(SSS)")
    format.print(new DateTime(61000)) shouldBe "000"
    format.print(new DateTime(61999)) shouldBe "999"
    format.print(new DateTime(61321)) shouldBe "321"
  }

  "time(seconds)" should "print seconds since epoch" in {
    val format = formatterFor("time(seconds)")
    format.print(new DateTime(61000)) shouldBe "61"
  }

  it should "parse seconds since epoch" in {
    val format = formatterFor("time(seconds)")
    format.parseDateTime("61") shouldBe new DateTime(61000)
  }

  "human time" should "bucket by hour" in {
    val format = formatterFor("time(yyyy-MM-dd HH:00:00)")
    println(format.print(new DateTime(2001, 3, 2, 13, 1, 2, 3)))
    format.print(new DateTime(2001, 3, 2, 13, 1, 2, 3)) shouldBe "2001-03-02 13:00:00"
  }

  "MsgPair item .time" should "match default time format" in {
    formatterFor("req.time") shouldBe DefaultTimeFormat
    formatterFor("request.time") shouldBe DefaultTimeFormat
    formatterFor("resp.time") shouldBe DefaultTimeFormat
    formatterFor("response.time") shouldBe DefaultTimeFormat
  }
}
