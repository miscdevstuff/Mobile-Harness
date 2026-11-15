package com.jarves.mh.runtime

/**
 * Strips ANSI/OSC/CSI terminal control sequences from plain-text log streams
 * (Gradle builds, CLI diagnostics, model lists). Not used by the interactive PTY terminal.
 */
object AnsiTextSanitizer {
    private val ANSI_SEQUENCE_REGEX = Regex(
        "\\u001B(?:\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])",
    )

    fun strip(text: String): String {
        if (text.isEmpty()) return ""
        return text
            .replace(ANSI_SEQUENCE_REGEX, "")
            .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
    }

    /**
     * Stateful chunk sanitizer that buffers incomplete trailing escape sequences across
     * arbitrary byte-read boundaries so split CSI/OSC codes are never leaked into logs.
     */
    class StreamSanitizer {
        private val carry = StringBuilder()

        fun processChunk(incoming: String): String {
            if (incoming.isEmpty()) return ""
            carry.append(incoming)
            val len = carry.length
            val searchStart = maxOf(0, len - MAX_ESCAPE_LOOKBACK)
            var escIndex = -1
            for (i in len - 1 downTo searchStart) {
                if (carry[i] == '\u001B') {
                    escIndex = i
                    break
                }
            }
            if (escIndex >= 0) {
                val tail = carry.subSequence(escIndex, len)
                val complete = ANSI_SEQUENCE_REGEX.matchesAt(tail, 0)
                val incomplete = !complete && (
                    escIndex == len - 1 ||
                        tail[1] in charArrayOf('[', ']', '(', ')')
                    )
                if (incomplete) {
                    val readyPrefix = carry.substring(0, escIndex)
                    carry.delete(0, escIndex)
                    return strip(readyPrefix)
                }
            }
            val all = carry.toString()
            carry.clear()
            return strip(all)
        }

        fun flush(): String {
            if (carry.isEmpty()) return ""
            val remainder = strip(carry.toString())
            carry.clear()
            return remainder
        }
    }

    private const val MAX_ESCAPE_LOOKBACK = 64
}
