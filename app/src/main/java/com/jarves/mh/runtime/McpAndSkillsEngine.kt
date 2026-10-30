package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.AgentSkill
import com.jarves.mh.model.McpServerConfig
import com.jarves.mh.model.McpSettings
import com.jarves.mh.model.McpTransportType
import com.jarves.mh.model.SkillActivationMode
import com.jarves.mh.model.SkillMarkdownCodec
import com.jarves.mh.model.SkillSettings
import com.jarves.mh.network.sharedHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages isolated OpenCode configuration staging under `/pocket-bridge/opencode/`
 * and builds `<mcp_environment>` and `<active_skills>` prompt blocks.
 */
object McpAndSkillsEngine {
    private const val GUEST_OPENCODE_DIR = "/pocket-bridge/opencode"
    private const val GUEST_OPENCODE_CONFIG = "/pocket-bridge/opencode/opencode.json"

    /**
     * Stages `/pocket-bridge/opencode/opencode.json` and `/pocket-bridge/opencode/skills/<id>/SKILL.md`
     * inside `context.filesDir/runtime-bridge/opencode`. Never touches project or global configs.
     */
    fun stageIsolatedBridgeFiles(
        context: Context,
        workspaceDir: File? = null,
        prompt: String = "",
    ) {
        val prefs = AppPreferences(context)
        val skillSettings = prefs.loadSkillSettings()
        val skills = prefs.loadSkills()
        val mcpSettings = prefs.loadMcpSettings()
        val mcpServers = prefs.loadMcpServers()

        val bridgeRoot = File(context.filesDir, "runtime-bridge/opencode").apply { mkdirs() }
        val skillsRoot = File(bridgeRoot, "skills")

        // 1. Stage native OpenCode SKILL.md files if enabled
        if (skillsRoot.exists()) skillsRoot.deleteRecursively()
        if (skillSettings.masterEnabled && skillSettings.stageNativeOpencodeSkills) {
            val activeSkills = skills.filter { it.shouldActivate(skillSettings.masterEnabled, workspaceDir, prompt) }
            for (skill in activeSkills) {
                val safeId = skill.id.replace(Regex("[^a-zA-Z0-9_-]"), "-").ifBlank { "skill" }
                val skillDir = File(skillsRoot, safeId).apply { mkdirs() }
                File(skillDir, "SKILL.md").writeText(SkillMarkdownCodec.formatSkillMarkdown(skill), Charsets.UTF_8)
            }
        }

        // 2. Stage isolated opencode.json with enabled MCP servers
        val configFile = File(bridgeRoot, "opencode.json")
        val enabledServers = if (mcpSettings.masterEnabled) {
            mcpServers.filter { it.enabled }
        } else {
            emptyList()
        }
        val configJson = buildOpencodeConfigJson(enabledServers)
        configFile.writeText(configJson.toString(2), Charsets.UTF_8)
    }

    /**
     * Builds the OpenCode v2 `opencode.json` object containing the `"mcp"` server map.
     */
    internal fun buildOpencodeConfigJson(enabledServers: List<McpServerConfig>): JSONObject {
        val root = JSONObject()
        root.put("\$schema", "https://opencode.ai/config.json")
        if (enabledServers.isEmpty()) return root

        val mcpObject = JSONObject()
        for (server in enabledServers) {
            val entry = buildMcpServerJsonEntry(server) ?: continue
            mcpObject.put(server.id.trim(), entry)
        }
        if (mcpObject.length() > 0) {
            root.put("mcp", mcpObject)
        }
        return root
    }

    internal fun buildMcpServerJsonEntry(server: McpServerConfig): JSONObject? {
        val cleanId = server.id.trim()
        if (cleanId.isEmpty()) return null
        val entry = JSONObject()
        when (server.transport) {
            McpTransportType.REMOTE -> {
                val cleanUrl = server.url.trim()
                if (cleanUrl.isEmpty()) return null
                entry.put("type", "remote")
                entry.put("url", cleanUrl)
                entry.put("enabled", true)
                if (server.headers.isNotEmpty()) {
                    val headersObj = JSONObject()
                    server.headers.forEach { (k, v) ->
                        if (k.isNotBlank()) headersObj.put(k.trim(), v)
                    }
                    if (headersObj.length() > 0) entry.put("headers", headersObj)
                }
                if (server.disableOAuth) {
                    entry.put("oauth", false)
                }
                if (server.timeoutMs > 0) {
                    entry.put("timeout", server.timeoutMs)
                }
            }
            McpTransportType.LOCAL_STDIO -> {
                val cmdList = server.command.map(String::trim).filter(String::isNotBlank)
                if (cmdList.isEmpty()) return null
                entry.put("type", "local")
                entry.put("command", JSONArray(cmdList))
                entry.put("enabled", true)
                if (server.environment.isNotEmpty()) {
                    val envObj = JSONObject()
                    server.environment.forEach { (k, v) ->
                        if (k.isNotBlank()) envObj.put(k.trim(), v)
                    }
                    if (envObj.length() > 0) entry.put("environment", envObj)
                }
                if (server.timeoutMs > 0) {
                    entry.put("timeout", server.timeoutMs)
                }
            }
        }
        return entry
    }

    /**
     * Enriches OpenCode's environment map so it loads `/pocket-bridge/opencode` without
     * modifying any project or global configuration files.
     */
    fun enrichOpencodeEnvironment(
        context: Context,
        baseEnvironment: Map<String, String>,
        workspaceDir: File? = null,
        prompt: String = "",
    ): Map<String, String> {
        stageIsolatedBridgeFiles(context, workspaceDir, prompt)
        val prefs = AppPreferences(context)
        val skillSettings = prefs.loadSkillSettings()
        val mcpSettings = prefs.loadMcpSettings()
        val enabledMcp = if (mcpSettings.masterEnabled) {
            prefs.loadMcpServers().filter { it.enabled }
        } else {
            emptyList()
        }
        val anySkillsActive = skillSettings.masterEnabled &&
            prefs.loadSkills().any { it.shouldActivate(skillSettings.masterEnabled, workspaceDir, prompt) }

        if (enabledMcp.isEmpty() && !anySkillsActive) {
            return baseEnvironment
        }

        val result = LinkedHashMap(baseEnvironment)
        if (anySkillsActive && skillSettings.stageNativeOpencodeSkills) {
            result.putIfAbsent("OPENCODE_CONFIG_DIR", GUEST_OPENCODE_DIR)
        }
        if (enabledMcp.isNotEmpty()) {
            val generatedMcp = buildOpencodeConfigJson(enabledMcp).optJSONObject("mcp")
            if (generatedMcp != null) {
                val existingInline = result["OPENCODE_CONFIG_CONTENT"]
                val existingConfigPath = result["OPENCODE_CONFIG"]
                when {
                    !existingInline.isNullOrBlank() -> {
                        val merged = runCatching { JSONObject(existingInline) }.getOrDefault(JSONObject())
                        merged.put("mcp", generatedMcp)
                        result["OPENCODE_CONFIG_CONTENT"] = merged.toString()
                    }
                    !existingConfigPath.isNullOrBlank() && existingConfigPath.startsWith("/pocket-bridge/") -> {
                        val rel = existingConfigPath.removePrefix("/pocket-bridge/")
                        val hostFile = File(context.filesDir, "runtime-bridge/$rel")
                        val merged = runCatching {
                            if (hostFile.isFile) JSONObject(hostFile.readText()) else JSONObject()
                        }.getOrDefault(JSONObject())
                        merged.put("mcp", generatedMcp)
                        hostFile.parentFile?.mkdirs()
                        hostFile.writeText(merged.toString(2), Charsets.UTF_8)
                    }
                    else -> {
                        result["OPENCODE_CONFIG"] = GUEST_OPENCODE_CONFIG
                        result.putIfAbsent("OPENCODE_CONFIG_DIR", GUEST_OPENCODE_DIR)
                    }
                }
            }
        }
        return result
    }

    /**
     * Builds the `<mcp_environment>` and `<active_skills>` prompt sections.
     * Returns an empty string when both MCP and Skills are disabled (the default).
     */
    fun buildPromptInjection(
        context: Context,
        workspaceDir: File?,
        guestWorkspacePath: String,
        currentPrompt: String,
    ): String {
        val prefs = AppPreferences(context)
        return buildPromptInjectionFromState(
            skillSettings = prefs.loadSkillSettings(),
            skills = prefs.loadSkills(),
            mcpSettings = prefs.loadMcpSettings(),
            mcpServers = prefs.loadMcpServers(),
            workspaceDir = workspaceDir,
            guestWorkspacePath = guestWorkspacePath,
            currentPrompt = currentPrompt,
        )
    }

    internal fun buildPromptInjectionFromState(
        skillSettings: SkillSettings,
        skills: List<AgentSkill>,
        mcpSettings: McpSettings,
        mcpServers: List<McpServerConfig>,
        workspaceDir: File?,
        guestWorkspacePath: String,
        currentPrompt: String,
    ): String {
        val sb = StringBuilder()

        // 1. MCP Environment & Locality Block
        val enabledServers = if (mcpSettings.masterEnabled) {
            mcpServers.filter { it.enabled }
        } else {
            emptyList()
        }
        if (enabledServers.isNotEmpty() && mcpSettings.injectLocalityPrompt) {
            sb.appendLine("<mcp_environment>")
            sb.appendLine("The following MCP (Model Context Protocol) servers are connected for this session:")
            for (server in enabledServers) {
                sb.appendLine(server.toLocalityPromptLine(guestWorkspacePath))
            }
            if (enabledServers.any { it.directToolExposure }) {
                sb.appendLine("When calling tools from these MCP servers, invoke their registered MCP tool names directly with valid JSON arguments.")
            }
            sb.appendLine("</mcp_environment>")
        }

        // 2. Active Skills & Free-Model Booster Guardrails
        if (skillSettings.masterEnabled) {
            val preloadSkills = skills.filter { skill ->
                skill.shouldActivate(skillSettings.masterEnabled, workspaceDir, currentPrompt) &&
                    (skillSettings.forcePreloadInPrompt || skill.activationMode != SkillActivationMode.NATIVE_ONLY)
            }
            val hasBoosters = skillSettings.readBeforeEditGuardrail || skillSettings.conciseCodeFirstOutput
            if (preloadSkills.isNotEmpty() || hasBoosters) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.appendLine("<active_skills>")
                if (skillSettings.readBeforeEditGuardrail) {
                    sb.appendLine("- **Read-Before-Edit & Verify Guardrail:** Always read the target file before modifying it, apply complete edits without `// ... existing code ...` placeholders, and verify syntax after editing.")
                }
                if (skillSettings.conciseCodeFirstOutput) {
                    sb.appendLine("- **Code-First Execution:** Prioritize direct tool execution and accurate code changes over long conversational preambles.")
                }
                var remainingChars = skillSettings.maxSkillContextChars.coerceIn(1_000, 64_000)
                for (skill in preloadSkills) {
                    if (remainingChars <= 0) break
                    val body = skill.content.trim()
                    if (body.isEmpty()) continue
                    val clipped = if (body.length <= remainingChars) body else body.take(remainingChars) + "\n… [skill truncated to fit context budget]"
                    remainingChars -= clipped.length
                    sb.appendLine()
                    sb.appendLine("### Skill: ${skill.name} (`${skill.id}`)")
                    sb.appendLine(clipped)
                }
                sb.appendLine("</active_skills>")
            }
        }

        return sb.toString().trim()
    }

    /**
     * Tests connectivity to a Remote MCP URL or verifies a Local Stdio MCP command inside PRoot.
     */
    suspend fun testMcpServer(
        installer: RuntimeInstaller,
        server: McpServerConfig,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (server.transport) {
                McpTransportType.REMOTE -> {
                    val url = server.url.trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) {
                        "Remote MCP URL must start with http:// or https://"
                    }
                    val client = sharedHttpClient.newBuilder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build()
                    val probePayload = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", 1)
                        .put("method", "initialize")
                        .put(
                            "params",
                            JSONObject()
                                .put("protocolVersion", if (server.protocolVersion == "auto") "2024-11-05" else server.protocolVersion)
                                .put("capabilities", JSONObject())
                                .put("clientInfo", JSONObject().put("name", "mobile-harness").put("version", "1.0.7")),
                        )
                        .toString()
                    val reqBuilder = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json")
                        .post(probePayload.toRequestBody("application/json".toMediaType()))
                    server.headers.forEach { (k, v) ->
                        if (k.isNotBlank()) reqBuilder.header(k.trim(), v)
                    }
                    client.newCall(reqBuilder.build()).execute().use { response ->
                        val code = response.code
                        when {
                            code in 200..299 -> "Connected to remote MCP server ($url · HTTP $code)"
                            code in listOf(400, 405, 406) -> "Remote MCP endpoint is reachable ($url · HTTP $code)"
                            code == 401 || code == 403 -> error("Remote MCP server rejected authentication (HTTP $code) — check headers")
                            else -> error("Remote MCP server returned HTTP $code")
                        }
                    }
                }
                McpTransportType.LOCAL_STDIO -> {
                    val executable = server.command.firstOrNull()?.trim().orEmpty()
                    require(executable.isNotEmpty()) { "Local MCP command cannot be empty" }
                    check(installer.isInstalled()) { "Linux runtime is not installed yet" }
                    val rootfs = installer.installedRuntime().rootfs
                    if (executable.startsWith("/")) {
                        val fileInRootfs = File(rootfs, executable.trimStart('/'))
                        check(fileInRootfs.exists()) {
                            "Local binary '$executable' not found in container. Install the required toolchain or switch this server to Remote."
                        }
                    }
                    "Local container command verified (${server.command.joinToString(" ")})"
                }
            }
        }
    }
}
