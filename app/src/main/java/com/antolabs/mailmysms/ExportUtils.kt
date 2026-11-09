package com.antolabs.mailmysms

object XmlUtils {
    // Control characters in this range are simply illegal in XML 1.0 regardless of escaping -
    // a message body containing one (rare, but it happens) would otherwise produce a file that
    // fails to parse.
    private val ILLEGAL = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    /** Escapes a string for safe use inside a double-quoted XML attribute value. */
    fun attr(value: String?): String {
        val v = ILLEGAL.replace(value ?: "", "")
        val sb = StringBuilder(v.length)
        for (c in v) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

object CsvUtils {
    /** RFC 4180 field escaping: quote the field if it contains a comma, quote, or newline, doubling any embedded quotes. */
    fun field(value: String?): String {
        val v = value ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }

    fun row(fields: List<String?>): String = fields.joinToString(",") { field(it) }

    /** Excel (unlike Sheets/Numbers) needs a UTF-8 BOM to reliably detect encoding for non-ASCII text. */
    const val UTF8_BOM = "﻿"
}
