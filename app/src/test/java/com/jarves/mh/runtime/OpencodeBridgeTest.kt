package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.isKeylessAllowed
import com.jarves.mh.network.ModelResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeBridgeTest {
    @Test
    fun formatsModelFlagsAcrossProvidersIncludingSlashModels() {
        assertEquals(
            "opencode/mimo-v2.6-flash-free",
            OpencodeRuntimeBridge.opencodeModelFlag(
                ProviderProfile(ProviderKind.OPENCODE_CLI, model = "mimo-v2.6-flash-free"),
            ),
        )
        assertEquals(
            "opencode/mimo-v2.6-flash-free",
            OpencodeRuntimeBridge.opencodeModelFlag(
                ProviderProfile(ProviderKind.OPENCODE_CLI, model = "opencode/mimo-v2.6-flash-free"),
            ),
        )
        assertEquals(
            "openrouter/anthropic/claude-sonnet-4",
            OpencodeRuntimeBridge.opencodeModelFlag(
                ProviderProfile(ProviderKind.LLM_ROUTER, model = "~anthropic/claude-sonnet-4"),
            ),
        )
        assertEquals(
            "nvidia/qwen/qwen2.5-coder-32b-instruct",
            OpencodeRuntimeBridge.opencodeModelFlag(
                ProviderProfile(ProviderKind.NVIDIA_NIM, model = "qwen/qwen2.5-coder-32b-instruct"),
            ),
        )
    }

    @Test
    fun buildsStandaloneCommandWithSessionAndFileAttachments() {
        val command = OpencodeRuntimeBridge.buildOpencodeCommand(
            provider = ProviderProfile(ProviderKind.OPENCODE_CLI, model = "mimo-v2.6-flash-free"),
            prompt = "Inspect main.py",
            resumeSessionId = "ses_abc123",
            attachmentGuestPaths = listOf("/workspace/demo/main.py"),
        )
        assertTrue(command.containsAll(listOf(
            "/usr/local/bin/opencode",
            "run",
            "--standalone",
            "--format",
            "json",
            "--thinking",
            "--auto",
            "--agent",
            "build",
            "--model",
            "opencode/mimo-v2.6-flash-free",
            "--session",
            "ses_abc123",
            "--file",
            "/workspace/demo/main.py",
            "Inspect main.py",
        )))
    }

    @Test
    fun parsesV2ReasoningToolUseAndTextEvents() {
        val reasoningLine = """{"type":"reasoning","sessionID":"ses_1","part":{"id":"prt_r1","type":"reasoning","text":"Checking tmp.txt first."}}"""
        val writeLine = """{"type":"tool_use","sessionID":"ses_1","part":{"partID":"prt_t1","tool":"write","state":{"status":"completed","input":{"path":"/workspace/tmp/hello.py","content":"print('hello')\n"},"output":"Wrote file successfully: hello.py","title":"write"}}}"""
        val shellLine = """{"type":"tool_use","sessionID":"ses_1","part":{"partID":"prt_t2","tool":"shell","state":{"status":"completed","input":{"command":"python3 hello.py"},"output":"hello\n","title":"shell","metadata":{"metadata":{"exit":0}}}}}"""
        val textLine = """{"type":"text","sessionID":"ses_1","part":{"id":"prt_txt1","type":"text","text":"Done!"}}"""

        val reasoningEvents = OpencodeEventParser.parse(reasoningLine)
        assertTrue(reasoningEvents.contains(OpencodeParsedEvent.SessionIdentified("ses_1")))
        assertTrue(reasoningEvents.any { it is OpencodeParsedEvent.Reasoning && it.text == "Checking tmp.txt first." })

        val writeTool = OpencodeEventParser.parse(writeLine).filterIsInstance<OpencodeParsedEvent.ToolUse>().single()
        assertEquals("Write", writeTool.toolName)
        assertTrue("/workspace/tmp/hello.py" in writeTool.detail)
        assertTrue("print('hello')" in writeTool.detail)

        val shellTool = OpencodeEventParser.parse(shellLine).filterIsInstance<OpencodeParsedEvent.ToolUse>().single()
        assertEquals("Bash", shellTool.toolName)
        assertTrue("$ python3 hello.py" in shellTool.detail)
        assertTrue("hello" in shellTool.detail)

        val textEvent = OpencodeEventParser.parse(textLine).filterIsInstance<OpencodeParsedEvent.Text>().single()
        assertEquals("Done!", textEvent.text)
    }

    @Test
    fun parsesOpencodeModelsCliListAndBadgesFreeModels() {
        val raw = """
            opencode/big-pickle
            opencode/mimo-v2.6-flash-free
            opencode/gpt-5.5
            anthropic/claude-sonnet-4-5
        """.trimIndent()
        val parsed = ModelResponseParser.parseOpencodeCliOutput(raw, "opencode/")
        assertEquals(listOf("big-pickle", "mimo-v2.6-flash-free", "gpt-5.5"), parsed.map { it.id })
        assertTrue(parsed.first { it.id == "big-pickle" }.isFree)
        assertTrue(parsed.first { it.id == "mimo-v2.6-flash-free" }.isFree)
        assertFalse(parsed.first { it.id == "gpt-5.5" }.isFree)
        assertTrue(ProviderKind.OPENCODE_CLI.isKeylessAllowed("mimo-v2.6-flash-free"))
        assertFalse(ProviderKind.OPENCODE_CLI.isKeylessAllowed("gpt-5.5"))
    }
}
