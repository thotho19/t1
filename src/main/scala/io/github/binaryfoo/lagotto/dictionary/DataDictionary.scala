package io.github.binaryfoo.lagotto.dictionary
import io.github.binaryfoo.lagotto.LogEntry
import io.github.binaryfoo.lagotto.dictionary.FieldType.FieldType
import io.github.binaryfoo.lagotto.dictionary.NameType.NameType

trait DataDictionary {

  /**
   * Intent: Full wordy english to help somebody understand on first encounter.
   */
  final def englishNameOf(field: String, context: LogEntry): Option[String] = {
    nameOf(NameType.English, field, context)
  }

  /**
   * Intent: you know the meaning of the term you just can't remember the name for the number.
   *
   * Couple of letters for filters, field lists and export.
   */
  def shortNameOf(field: String, context: LogEntry): Option[String] = {
    nameOf(NameType.Short, field, context)
  }

  /**
   * Intent: short name, then snake cased english name, finally just fall back to field.
   */
  final def exportNameOf(field: String, context: LogEntry): String = {
    nameOf(NameType.Snake, field, context)
      .getOrElse(sanitizeForSQL(field))
  }

  final def sanitizeForSQL(field: String): String = {
    if (field.nonEmpty && field.charAt(0).isDigit) {
      "f_" + field.replace('.', '_')
    } else {
      field
    }
  }

  def nameOf(nameType: NameType, field: String, context: LogEntry): Option[String]

  /**
   * Always default to String.
   */
  final def typeOf(field: String, context: LogEntry): FieldType = {
    optionalTypeOf(field, context).getOrElse(FieldType.String)
  }

  def optionalTypeOf(field: String, context: LogEntry): Option[FieldType]

  def translateValue(field: String, context: LogEntry, value: String): Option[String]

  /**
   * Reverse lookup of the field path for the short name that applies based on the context.
   * Eg Given name = stan find 11 as the field.
   */
  def fieldForShortName(name: String, context: LogEntry): Option[String]
}

object NameType extends Enumeration {
  type NameType = Value
  val English, Short, Snake, Camel = Value
}

object FieldType extends Enumeration {
  type FieldType = Value
  val String, Integer, GZippedString = Value

  def forName(s: String): Option[Value] = values.find(_.toString == s)
}
