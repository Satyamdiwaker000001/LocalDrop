package com.localdrop.protocol

// M02: minimal canonical JSON subset, dependency-free (pure Kotlin, usable in
// local JVM unit tests where android.org.json stubs are unavailable).
// Supports exactly what the protocol needs: objects (string keys), arrays,
// strings, 64-bit integers, booleans. No floats, no exponents, no null
// production. Canonical rules (shared-protocol/framing-resume-integrity.md):
// no whitespace, contract key order (insertion order of LinkedHashMap),
// fixed string escaping, integers only. Parsers accept any key order and
// JSON whitespace; unknown fields are ignored by the schema layer.
sealed interface JsonValue {
    data class Obj(val entries: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Long) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
}

sealed interface JsonParseResult {
    data class Ok(val value: JsonValue) : JsonParseResult
    data class Err(val detail: String) : JsonParseResult
}

object JsonCanonical {
    const val MAX_DEPTH = 32

    // ---- serialization (canonical) ----

    fun serialize(value: JsonValue): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is JsonValue.Arr -> {
                sb.append('[')
                var first = true
                for (item in value.items) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Str -> writeString(sb, value.value)
            is JsonValue.Num -> sb.append(value.value.toString())
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
        }
    }

    fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u00")
                    sb.append(Character.forDigit(c.code shr 4, 16))
                    sb.append(Character.forDigit(c.code and 0xF, 16))
                } else {
                    if (c.isLowSurrogate() || c.isHighSurrogate()) {
                        throw IllegalArgumentException("lone surrogate in output string")
                    }
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    // ---- parsing ----

    fun parse(text: String): JsonParseResult {
        val p = Parser(text)
        val v = p.parseValue(0)
        if (v == null) return JsonParseResult.Err(p.error)
        p.skipWs()
        if (!p.atEnd()) return JsonParseResult.Err("trailing content after JSON value")
        return JsonParseResult.Ok(v)
    }

    private class Parser(val text: String) {
        var pos = 0
        var error = "invalid JSON"

        fun atEnd(): Boolean = pos >= text.length

        fun skipWs() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                        text[pos] == '\n' || text[pos] == '\r')) pos++
        }

        fun <T> fail(detail: String): T? {
            error = detail
            return null
        }

        fun parseValue(depth: Int): JsonValue? {
            if (depth > MAX_DEPTH) return fail("nesting too deep")
            skipWs()
            if (atEnd()) return fail("unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()?.let { JsonValue.Str(it) }
                't' -> parseLiteral("true")?.let { JsonValue.Bool(true) }
                'f' -> parseLiteral("false")?.let { JsonValue.Bool(false) }
                'n' -> fail("'null' is not a valid protocol value")
                '-', in '0'..'9' -> parseNumber()
                else -> fail("unexpected character '${text[pos]}'")
            }
        }

        fun parseLiteral(word: String): String? {
            if (text.regionMatches(pos, word, 0, word.length)) {
                pos += word.length
                return word
            }
            return fail("invalid literal")
        }

        fun parseObject(depth: Int): JsonValue? {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWs()
                if (atEnd() || text[pos] != '"') return fail("expected string key")
                val key = parseString() ?: return null
                skipWs()
                if (atEnd() || text[pos] != ':') return fail("expected ':'")
                pos++
                val v = parseValue(depth + 1) ?: return null
                map[key] = v
                skipWs()
                if (atEnd()) return fail("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(map)
                    }
                    else -> return fail("expected ',' or '}'")
                }
            }
        }

        fun parseArray(depth: Int): JsonValue? {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                val v = parseValue(depth + 1) ?: return null
                items.add(v)
                skipWs()
                if (atEnd()) return fail("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> return fail("expected ',' or ']'")
                }
            }
        }

        fun parseString(): String? {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) return fail("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) return fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) return fail("bad \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: return fail("bad \\u escape")
                                pos += 4
                                val ch = code.toChar()
                                if (ch.isHighSurrogate()) {
                                    // Require a low-surrogate trail.
                                    if (pos + 6 <= text.length && text[pos] == '\\' &&
                                        text[pos + 1] == 'u'
                                    ) {
                                        val lo = text.substring(pos + 2, pos + 6)
                                            .toIntOrNull(16)
                                        if (lo != null && lo.toChar().isLowSurrogate()) {
                                            pos += 6
                                            sb.append(ch)
                                            sb.append(lo.toChar())
                                            continue
                                        }
                                    }
                                    return fail("lone surrogate in string")
                                }
                                if (ch.isLowSurrogate()) return fail("lone surrogate in string")
                                sb.append(ch)
                            }
                            else -> return fail("bad escape '\\$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') return fail("unescaped control character")
                        sb.append(c)
                    }
                }
            }
        }

        fun parseNumber(): JsonValue? {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            if (atEnd()) return fail("bad number")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                while (!atEnd() && text[pos] in '0'..'9') pos++
            } else {
                return fail("bad number")
            }
            if (!atEnd() && (text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E')) {
                return fail("non-integer numbers are not valid protocol values")
            }
            val token = text.substring(start, pos)
            val v = token.toLongOrNull() ?: return fail("integer out of range")
            return JsonValue.Num(v)
        }
    }
}

// ---- schema reading helper ----

// Outcome of payload/object parsing. Failures map to INVALID_PROTOCOL.
sealed interface PayloadResult<out T> {
    data class Success<T>(val value: T) : PayloadResult<T>
    data class Failure(val detail: String) : PayloadResult<Nothing>
}

// Reads + validates typed fields from a parsed JSON object. Records the
// first error; callers check error() before using values.
class FieldReader(val obj: Map<String, JsonValue>, val context: String) {
    private var firstError: String? = null

    fun error(): String? = firstError

    private fun note(detail: String) {
        if (firstError == null) firstError = "$context.$detail"
    }

    private fun raw(key: String, required: Boolean): JsonValue? {
        val v = obj[key]
        if (v == null && required) note("$key: missing required field")
        return v
    }

    fun string(key: String, minLen: Int, maxLen: Int, required: Boolean = true): String? {
        val v = raw(key, required) ?: return null
        if (v !is JsonValue.Str) {
            note("$key: expected string")
            return null
        }
        if (v.value.length < minLen || v.value.length > maxLen) {
            note("$key: length out of range ($minLen..$maxLen)")
            return null
        }
        return v.value
    }

    fun uuid(key: String, required: Boolean = true): String? {
        val s = string(key, 36, 36, required) ?: return null
        if (!Uuids.isValid(s)) {
            note("$key: invalid UUID syntax")
            return null
        }
        return s.lowercase()
    }

    fun int(key: String, min: Long, max: Long, required: Boolean = true): Long? {
        val v = raw(key, required) ?: return null
        if (v !is JsonValue.Num) {
            note("$key: expected integer")
            return null
        }
        if (v.value < min || v.value > max) {
            note("$key: out of range ($min..$max)")
            return null
        }
        return v.value
    }

    fun bool(key: String, required: Boolean = true): Boolean? {
        val v = raw(key, required) ?: return null
        if (v !is JsonValue.Bool) {
            note("$key: expected boolean")
            return null
        }
        return v.value
    }

    fun obj(key: String, required: Boolean = true): Map<String, JsonValue>? {
        val v = raw(key, required) ?: return null
        if (v !is JsonValue.Obj) {
            note("$key: expected object")
            return null
        }
        return v.entries
    }

    fun stringList(key: String, maxItems: Int, itemMin: Int, itemMax: Int): List<String>? {
        val v = raw(key, true) ?: return null
        if (v !is JsonValue.Arr) {
            note("$key: expected array")
            return null
        }
        if (v.items.size > maxItems) {
            note("$key: too many entries (max $maxItems)")
            return null
        }
        val out = ArrayList<String>(v.items.size)
        for ((i, item) in v.items.withIndex()) {
            if (item !is JsonValue.Str) {
                note("$key[$i]: expected string")
                return null
            }
            if (item.value.length < itemMin || item.value.length > itemMax) {
                note("$key[$i]: length out of range ($itemMin..$itemMax)")
                return null
            }
            out.add(item.value)
        }
        return out
    }
}

// UUID syntax validation (accepts any case; output is normalized lowercase).
// No network, no crypto: pure shape check.
object Uuids {
    private val RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValid(s: String): Boolean = RE.matches(s)

    fun random(): String = java.util.UUID.randomUUID().toString()
}
