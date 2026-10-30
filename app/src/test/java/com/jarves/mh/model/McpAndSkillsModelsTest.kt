package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAndSkillsModelsTest {
    @Test
    fun defaultsAreDisabledByDefault() {
        val skillSettings = SkillSettings()
        val mcpSettings = McpSettings()
        assertFalse(skillSettings.masterEnabled)
        assertFalse(mcpSettings.masterEnabled)
        assertTrue(BuiltInSkills.all().all { !it.enabled })
        assertTrue(BuiltInMcpServers.all().all { !it.enabled })
    }

    @Test
    fun localityPromptDistinguishesRemoteAndLocalMcpServers() {
        val remoteServer = BuiltInMcpServers.GODOT_MCP_PRESET.copy(
            enabled = true,
            transport = McpTransportType.REMOTE,
            url = "http://127.0.0.1:6505/mcp",
        )
        val remotePrompt = remoteServer.toLocalityPromptLine("/workspace/demo")
        assertTrue(remotePrompt.contains("REMOTE MCP SERVER"))
        assertTrue(remotePrompt.contains("NOT installed in this container's local PATH"))

        val localServer = remoteServer.copy(transport = McpTransportType.LOCAL_STDIO)
        val localPrompt = localServer.toLocalityPromptLine("/workspace/demo")
        assertTrue(localPrompt.contains("LOCAL CONTAINER MCP SERVER"))
        assertTrue(localPrompt.contains("Running locally inside this Ubuntu PRoot container"))
    }

    @Test
    fun skillMarkdownCodecRoundTripsFrontmatterAndBody() {
        val original = BuiltInSkills.GODOT_4_SKILL
        val markdown = SkillMarkdownCodec.formatSkillMarkdown(original)
        assertTrue(markdown.startsWith("---\nname: godot-4-gdscript"))
        val parsed = SkillMarkdownCodec.parseSkillMarkdown(markdown)
        assertEquals("godot-4-gdscript", parsed.id)
        assertFalse(parsed.enabled)
        assertTrue(parsed.content.contains("Strict GDScript 2.0"))
    }
}
