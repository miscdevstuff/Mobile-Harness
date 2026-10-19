package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBoundsTest {
    @Test
    fun headReaderStopsAtByteLimit() {
        val directory = Files.createTempDirectory("mh-head-test").toFile()
        try {
            val file = directory.resolve("large.txt")
            file.writeText("0123456789")

            assertEquals("0123", file.readHeadBytes(4).toString(Charsets.UTF_8))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun tailReaderNeverLoadsEarlierOutput() {
        val directory = Files.createTempDirectory("mh-tail-test").toFile()
        try {
            val file = directory.resolve("output.log")
            file.writeText("earlier-output\nlatest-output")

            assertEquals("latest-output", file.readTailText("latest-output".length))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun conversationHistoryKeepsNewestTextWithinBudget() {
        val history = listOf(
            ChatMessage(fromUser = true, text = "old-message"),
            ChatMessage(fromUser = false, text = "middle-message"),
            ChatMessage(fromUser = true, text = "new-message"),
        )

        val bounded = history.recentWithinCharacterBudget(15)

        assertEquals(2, bounded.size)
        assertEquals("sage", bounded.first().text)
        assertEquals("new-message", bounded.last().text)
        assertEquals(15, bounded.sumOf { it.text.length })
    }

}
