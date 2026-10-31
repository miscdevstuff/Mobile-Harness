package com.jarves.mh.runtime

import com.jarves.mh.model.BuiltInMcpServers
import com.jarves.mh.model.BuiltInSkills
import com.jarves.mh.model.McpSettings
import com.jarves.mh.model.McpTransportType
import com.jarves.mh.model.SkillSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAndSkillsEngineTest {
    @Test
    fun disabledMasterSwitchesProduceEmptyPromptAndConfig() {
        val prompt = McpAndSkillsEngine.buildPromptInjectionFromState(
            skillSettings = SkillSettings(masterEnabled = false),
            skills = listOf(BuiltInSkills.GODOT_4_SKILL.copy(enabled = true)),
            mcpSettings = McpSettings(masterEnabled = false),
            mcpServers = listOf(BuiltInMcpServers.GODOT_MCP_PRESET.copy(enabled = true)),
            workspaceDir = null,
            guestWorkspacePath = "/workspace/demo",
            currentPrompt = "Create a CharacterBody2D player script",
        )
        assertEquals("", prompt)
        val json = McpAndSkillsEngine.buildOpencodeConfigJson(emptyList())
        assertFalse(json.has("mcp"))
    }

    @Test
    fun enabledRemoteAndLocalMcpGenerateValidOpencodeConfigAndLocalityBlock() {
        val remoteGodot = BuiltInMcpServers.GODOT_MCP_PRESET.copy(
            enabled = true,
            transport = McpTransportType.REMOTE,
            url = "http://127.0.0.1:6505/mcp",
        )
        val config = McpAndSkillsEngine.buildOpencodeConfigJson(listOf(remoteGodot))
        val mcpObj = config.getJSONObject("mcp").getJSONObject("godot-mcp")
        assertEquals("remote", mcpObj.getString("type"))
        assertEquals("http://127.0.0.1:6505/mcp", mcpObj.getString("url"))
        assertEquals(false, mcpObj.getBoolean("oauth"))

        val block = McpAndSkillsEngine.buildPromptInjectionFromState(
            skillSettings = SkillSettings(masterEnabled = true),
            skills = listOf(BuiltInSkills.GODOT_4_SKILL.copy(enabled = true)),
            mcpSettings = McpSettings(masterEnabled = true),
            mcpServers = listOf(remoteGodot),
            workspaceDir = null,
            guestWorkspacePath = "/workspace/game",
            currentPrompt = "Add player jump",
        )
        assertTrue(block.contains("<mcp_environment>"))
        assertTrue(block.contains("REMOTE MCP SERVER | TOOLS ONLY | NO LOCAL BINARY"))
        assertTrue(block.contains("<active_skills>"))
        assertTrue(block.contains("Godot 4.7 & GDScript 2.0"))
    }
}
