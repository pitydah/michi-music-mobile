package org.michimusic.data.artist

/** D30: a shallow, bounded lexical duplicate-key defense; not a JSON parser.
 * The full JSON payload is validated separately by kotlinx.serialization.json.Json.
 */
internal object ArtistBulkJsonRootKeyGuard {
    private const val MAX_DEPTH = 48
    private const val MAX_ROOT_KEYS = 128
    /** Pure lexical check of root JSON object keys; full JSON syntax is checked by Json. */
    fun rootIdCount(source: String): Int? {
        var i = 0
        fun skipSpaces() {
            while (i < source.length && source[i] in charArrayOf(' ', '\t', '\r', '\n')) i++
        }
        // Reads one JSON string, including escaped key spellings (e.g. "\\u0069d").
        fun readString(decode: Boolean): String? {
            if (i >= source.length || source[i++] != '"') return null
            val result = StringBuilder()
            while (i < source.length) {
                when (val ch = source[i++]) {
                    '"' -> return if (decode) result.toString() else ""
                    '\\' -> {
                        if (i >= source.length) return null
                        when (val escaped = source[i++]) {
                            '"', '\\', '/' -> if (decode) result.append(escaped)
                            'b' -> if (decode) result.append('\b')
                            'f' -> if (decode) result.append('\u000c')
                            'n' -> if (decode) result.append('\n')
                            'r' -> if (decode) result.append('\r')
                            't' -> if (decode) result.append('\t')
                            'u' -> {
                                if (i + 4 > source.length) return null
                                val hex = source.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                if (decode) result.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> {
                        if (ch < ' ') return null
                        if (decode) result.append(ch)
                    }
                }
            }
            return null
        }
        skipSpaces()
        if (i >= source.length || source[i++] != '{') return null
        var idCount = 0
        var keys = 0
        while (true) {
            skipSpaces()
            if (i >= source.length) return null
            if (source[i] == '}') return idCount // Outer syntax validation rejects suffixes.
            if (++keys > MAX_ROOT_KEYS) return null
            val key = readString(decode = true) ?: return null
            if (key == "id") idCount++
            if (idCount > 1) return null
            skipSpaces()
            if (i >= source.length || source[i++] != ':') return null
            skipSpaces()
            var depth = 0
            var sawValue = false
            // The sole task here is locating the root-level comma / end. A validated JSON
            // parser subsequently checks all actual values, commas and bracket matching.
            while (i < source.length) {
                when (source[i]) {
                    '"' -> { if (readString(decode = false) == null) return null; sawValue = true }
                    '{', '[' -> { depth++; i++; sawValue = true; if (depth > MAX_DEPTH) return null }
                    '}', ']' -> {
                        if (depth == 0) {
                            if (source[i] == ']' || !sawValue) return null
                            return idCount
                        }
                        depth--; i++
                    }
                    ',' -> {
                        if (depth == 0) {
                            if (!sawValue) return null
                            i++; break
                        }
                        i++
                    }
                    else -> {
                        if (source[i] !in charArrayOf(' ', '\t', '\r', '\n')) sawValue = true
                        i++
                    }
                }
            }
            if (i >= source.length) return null
        }
    }
}
