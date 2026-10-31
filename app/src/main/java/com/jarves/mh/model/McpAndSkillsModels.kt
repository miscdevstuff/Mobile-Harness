package com.jarves.mh.model

import androidx.compose.runtime.Immutable
import java.io.File
import kotlinx.serialization.Serializable

/**
 * Controls how an enabled [AgentSkill] is delivered to the coding agent.
 */
@Serializable
enum class SkillActivationMode {
    /** Always preload into the prompt (and stage in OPENCODE_CONFIG_DIR) whenever enabled. */
    ALWAYS_WHEN_ENABLED,
    /** Activate only when workspace files match [AgentSkill.filePatterns] or prompt matches [AgentSkill.triggerKeywords]. */
    AUTO_DETECT,
    /** Stage only in OPENCODE_CONFIG_DIR/skills/<id>/SKILL.md for OpenCode's native `skill` tool without prompt preload. */
    NATIVE_ONLY,
}

/**
 * Global power-user settings for Agent Skills, tuned to make free/small models reliable.
 * Disabled by default until the user explicitly turns on skills.
 */
@Immutable
@Serializable
data class SkillSettings(
    val masterEnabled: Boolean = false,
    val forcePreloadInPrompt: Boolean = true,
    val stageNativeOpencodeSkills: Boolean = true,
    val readBeforeEditGuardrail: Boolean = true,
    val conciseCodeFirstOutput: Boolean = true,
    val maxSkillContextChars: Int = 12_000,
)

@Immutable
@Serializable
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = false,
    val builtIn: Boolean = false,
    val activationMode: SkillActivationMode = SkillActivationMode.ALWAYS_WHEN_ENABLED,
    val filePatterns: List<String> = emptyList(),
    val triggerKeywords: List<String> = emptyList(),
    val allowedTools: List<String> = emptyList(),
    val content: String,
) {
    /**
     * Evaluates whether this skill should be active for the given [workspaceDir] and [prompt].
     */
    fun shouldActivate(masterEnabled: Boolean, workspaceDir: File?, prompt: String): Boolean {
        if (!masterEnabled || !enabled) return false
        if (activationMode != SkillActivationMode.AUTO_DETECT) return true
        val lowerPrompt = prompt.lowercase()
        if (triggerKeywords.any { kw -> kw.isNotBlank() && lowerPrompt.contains(kw.lowercase()) }) {
            return true
        }
        if (workspaceDir == null || !workspaceDir.isDirectory || filePatterns.isEmpty()) {
            return false
        }
        val namesAndExtensions = workspaceDir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile }
            .map { it.name.lowercase() }
            .toSet()
        return filePatterns.any { pattern ->
            val clean = pattern.trim().lowercase()
            when {
                clean.isEmpty() -> false
                clean.startsWith("*.") -> {
                    val ext = clean.removePrefix("*.")
                    namesAndExtensions.any { it.endsWith(".$ext") }
                }
                else -> clean in namesAndExtensions
            }
        }
    }
}

/**
 * Transport mechanism for an MCP server in OpenCode (`opencode.json`).
 */
@Serializable
enum class McpTransportType {
    /** Local stdio MCP server spawned inside the PRoot Ubuntu container. */
    LOCAL_STDIO,
    /** Remote HTTP / Streamable HTTP / SSE MCP server via URL. */
    REMOTE,
}

/**
 * Tells the AI model whether the MCP server's underlying tools/binaries exist locally in PRoot or only remotely.
 */
@Serializable
enum class McpLocalityMode {
    /** Automatically infer: [McpTransportType.LOCAL_STDIO] -> Local in container; [McpTransportType.REMOTE] -> Remote tools only. */
    AUTO_BY_TRANSPORT,
    /** Tools and underlying binaries ARE installed locally inside the PRoot Ubuntu container. */
    LOCAL_IN_CONTAINER,
    /** Tools are hosted remotely; underlying binaries are NOT installed in PRoot PATH. */
    REMOTE_TOOLS_ONLY,
}

@Immutable
@Serializable
data class McpSettings(
    val masterEnabled: Boolean = false,
    val injectLocalityPrompt: Boolean = true,
)

@Immutable
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = false,
    val builtInPreset: Boolean = false,
    val transport: McpTransportType = McpTransportType.LOCAL_STDIO,
    val localityMode: McpLocalityMode = McpLocalityMode.AUTO_BY_TRANSPORT,
    // Remote (HTTP / SSE) configuration
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val disableOAuth: Boolean = true,
    // Local (PRoot stdio) configuration
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val cwd: String = "",
    // Power-user & free-model tuning
    val directToolExposure: Boolean = true,
    val protocolVersion: String = "auto",
    val timeoutMs: Int = 30_000,
    val usageHint: String = "",
) {
    val isEffectivelyRemote: Boolean
        get() = when (localityMode) {
            McpLocalityMode.AUTO_BY_TRANSPORT -> transport == McpTransportType.REMOTE
            McpLocalityMode.REMOTE_TOOLS_ONLY -> true
            McpLocalityMode.LOCAL_IN_CONTAINER -> false
        }

    /**
     * Builds the explicit locality descriptor injected into `<mcp_environment>` so the AI
     * knows whether the underlying binaries exist locally in PRoot or only via remote MCP tools.
     */
    fun toLocalityPromptLine(guestWorkspacePath: String): String = buildString {
        if (isEffectivelyRemote) {
            append("- MCP Server `$id` ($name) — [REMOTE MCP SERVER | TOOLS ONLY | NO LOCAL BINARY]: ")
            append("Connected remotely via `${url.trim()}`. ")
            append("The underlying runtime/binaries for `$name` are hosted externally and are NOT installed in this container's local PATH. ")
            append("Do NOT attempt to invoke its binaries directly via the `Bash` tool; call the `$id` MCP tools exclusively.")
        } else {
            val cmdPreview = command.joinToString(" ").ifBlank { id }
            append("- MCP Server `$id` ($name) — [LOCAL CONTAINER MCP SERVER | LOCAL BINARIES AVAILABLE]: ")
            append("Running locally inside this Ubuntu PRoot container (`$cmdPreview`). ")
            append("Both the `$id` MCP tools and the local binary toolchain in PATH are available inside `$guestWorkspacePath`.")
        }
        if (usageHint.isNotBlank()) {
            append(" Hint: ")
            append(usageHint.trim())
        }
    }
}

/**
 * Built-in starter skills for Free-Model Reliability, Godot 4.7, and Android SDK 36 (all disabled by default).
 */
object BuiltInSkills {
    const val PRECISION_CODER_SKILL_ID = "free-model-precision-coder"
    const val GODOT_4_SKILL_ID = "godot-4-gdscript"
    const val GODOT_SCENE_SKILL_ID = "godot-4-scene-architect"
    const val ANDROID_SDK_SKILL_ID = "android-arm64-sdk"

    val PRECISION_CODER_SKILL = AgentSkill(
        id = PRECISION_CODER_SKILL_ID,
        name = "Free-Model Precision Coder",
        description = "Eliminates lazy code truncation, blind edits, and tool-call loops on free and lightweight coding models.",
        enabled = false,
        builtIn = true,
        activationMode = SkillActivationMode.ALWAYS_WHEN_ENABLED,
        filePatterns = emptyList(),
        triggerKeywords = emptyList(),
        content = """# Free-Model Precision Execution Rules

## 1. Zero Truncation or Placeholders
- NEVER write `// ... existing code ...`, `# ... rest of script ...`, or `// TODO: implement` when writing or editing files.
- Always write complete, syntactically valid functions and files.

## 2. Inspect Before Modifying
- Before editing an existing file, always read it first so imports, indentation, and symbol names match the real codebase.
- Before creating new files, list the directory structure to avoid duplicate or misplaced files.

## 3. Fail-Fast Verification & No Blind Retry Loops
- If a shell command or tool call fails twice in a row with the same error, STOP repeating the identical command. Read the error message, inspect the relevant file or config, and change your approach.
- After making code edits, run the fastest available syntax/compile check before finishing your turn.
""".trimIndent(),
    )

    val GODOT_4_SKILL = AgentSkill(
        id = GODOT_4_SKILL_ID,
        name = "Godot 4.7 & GDScript 2.0",
        description = "Strict Godot 4.7 syntax guardrails, typed GDScript 2.0 rules, .tscn serialization, and local headless verification.",
        enabled = false,
        builtIn = true,
        activationMode = SkillActivationMode.ALWAYS_WHEN_ENABLED,
        filePatterns = listOf("project.godot", "*.gd", "*.tscn", "*.tres"),
        triggerKeywords = listOf("godot", "gdscript", ".tscn", "node2d", "node3d", "characterbody"),
        content = """# Godot 4.7 & GDScript 2.0 Engineering Rules

## 1. Strict GDScript 2.0 (Godot 4.7) Syntax — Never Use Godot 3 Syntax
- **Annotations:** Use `@export var speed: float = 200.0` and `@onready var sprite: Sprite2D = ${'$'}Sprite2D` (NEVER `export` or `onready` keywords).
- **Coroutines & Signals:** Use `await signal_name` (NEVER `yield(obj, "signal")`). Connect signals via `button.pressed.connect(_on_button_pressed)` (NEVER `connect("pressed", self, "_on_button_pressed")`).
- **Physics & Movement:** Use `CharacterBody2D` / `CharacterBody3D`, assign `velocity = ...`, and call `move_and_slide()` with zero arguments (NEVER `KinematicBody2D` or `velocity = move_and_slide(velocity)`).
- **Instantiation:** Call `packed_scene.instantiate()` (NEVER `.instance()`).
- **File & Directory I/O:** Use `FileAccess.open(path, FileAccess.ModeFlags.READ)` and `DirAccess.open(path)` (NEVER `File.new()` or `Directory.new()`).
- **Tweens:** Create tweens via `var tween := create_tween()` (NEVER add a `Tween.new()` node).
- **Static Typing:** Always use explicit type annotations for variables, arrays (`Array[Node2D]`), dictionaries (`Dictionary[String, int]`), and return types (`func calc(delta: float) -> void:`).

## 2. Scene (`.tscn`) & `project.godot` File Integrity
- `project.godot` must use `config_version=5`.
- `.tscn` files must start with `[gd_scene load_steps=... format=3]`.
- Order sections strictly: `[ext_resource]` -> `[sub_resource]` -> `[node]` -> `[connection]`.
- Never invent random `uid://...` strings; omit the `uid` attribute when hand-authoring new `.tscn` files so Godot assigns a valid UID.

## 3. Local Godot 4.7 Toolchain & MCP Workflow
- When `godot-mcp` is enabled in `<mcp_environment>`, prefer `godot-mcp` tools for inspecting and modifying scenes, nodes, and project settings.
- When `/usr/local/bin/godot` is installed in the container (`DevStack.GODOT`), validate modified GDScript files headlessly before finishing:
  `godot --headless --audio-driver Dummy --check-only --script res://path/to/script.gd`
""".trimIndent(),
    )

    val GODOT_SCENE_SKILL = AgentSkill(
        id = GODOT_SCENE_SKILL_ID,
        name = "Godot 4 Scene, Physics & UI Architect",
        description = "Blueprints for 2D/3D node hierarchies, CharacterBody physics, Camera/UI CanvasLayer layouts, and InputMap setup.",
        enabled = false,
        builtIn = true,
        activationMode = SkillActivationMode.AUTO_DETECT,
        filePatterns = listOf("project.godot", "*.tscn", "*.gd"),
        triggerKeywords = listOf("scene", "player", "camera", "ui", "hud", "collision", "tilemap", "control"),
        content = """# Godot 4 Scene, Physics & UI Architecture

## 1. Standard Node Hierarchies
- **2D/3D Character (`CharacterBody2D` / `CharacterBody3D`):** Always include a `CollisionShape2D`/`3D` child with a concrete shape resource (`CapsuleShape2D`, `RectangleShape2D`, etc.) and a visual child (`Sprite2D`, `AnimatedSprite2D`, or `MeshInstance3D`).
- **HUD & Menus:** Place UI inside a `CanvasLayer` -> root `Control` (`anchors_preset = 15` for full-rect) -> layout containers (`MarginContainer`, `VBoxContainer`, `HBoxContainer`, `PanelContainer`) rather than hardcoding pixel coordinates.

## 2. Physics & Frame Loop Discipline
- Put movement, velocity math, and `move_and_slide()` inside `_physics_process(delta: float) -> void`.
- Apply gravity in 2D using `if not is_on_floor(): velocity += get_gravity() * delta`.
- Use `Input.get_axis("move_left", "move_right")` or `Input.get_vector(...)` for smooth movement, and ensure matching actions exist under `[input]` in `project.godot`.
""".trimIndent(),
    )

    val ANDROID_SDK_SKILL = AgentSkill(
        id = ANDROID_SDK_SKILL_ID,
        name = "Android SDK 36 & Compose (ARM64)",
        description = "On-device ARM64 Android toolchain rules, memory-safe Gradle flags, and Jetpack Compose Material 3 patterns.",
        enabled = false,
        builtIn = true,
        activationMode = SkillActivationMode.ALWAYS_WHEN_ENABLED,
        filePatterns = listOf("AndroidManifest.xml", "build.gradle.kts", "settings.gradle.kts"),
        triggerKeywords = listOf("android", "compose", "apk", "gradle", "activity", "viewmodel"),
        content = """# Android SDK 36 & Jetpack Compose (ARM64) Rules

## 1. Mobile Harness On-Device Toolchain Invariants
- When the Android stack is installed, the container provides **JDK 17**, **Android SDK 36**, **ARM64 Build Tools 35.0.0 (`aapt2`)**, **Gradle 8.14.3**, and a pre-seeded offline Maven cache (`/root/maven/localMvnRepository`).
- Target `compileSdk = 36`, `minSdk = 28`, Java 17, AGP `8.11.0`, and Kotlin `1.9.22` (or `2.2.x` if configured in the project).
- **CRITICAL:** Never add or modify `android.aapt2FromMavenOverride` in `gradle.properties`; Mobile Harness injects the ARM64 `aapt2` path globally via its Gradle init script.
- When running Gradle from `Bash`, always use memory-bounded flags:
  `gradle --no-daemon --max-workers=2 --console=plain assembleDebug`

## 2. Jetpack Compose & Kotlin Best Practices
- Use `androidx.compose.material3` components (`Scaffold`, `Surface`, `TopAppBar`, `ModalBottomSheet`).
- Use `Icons.AutoMirrored.Filled.*` for directional icons (`ArrowBack`, `ArrowForward`, `Send`, `Chat`, `Undo`, `Redo`).
- Expose UI state via `StateFlow<UiState>` from `ViewModel` and collect with `collectAsStateWithLifecycle()`.
- Write complete, compilable Kotlin files without placeholder comments.
""".trimIndent(),
    )

    fun all(): List<AgentSkill> = listOf(
        PRECISION_CODER_SKILL,
        GODOT_4_SKILL,
        GODOT_SCENE_SKILL,
        ANDROID_SDK_SKILL,
    )

    fun findById(id: String): AgentSkill? = all().firstOrNull { it.id == id }

    fun mergeWithSaved(saved: List<AgentSkill>): List<AgentSkill> {
        if (saved.isEmpty()) return all()
        val byId = LinkedHashMap<String, AgentSkill>()
        for (builtIn in all()) {
            byId[builtIn.id] = builtIn
        }
        for (item in saved) {
            val defaultBuiltIn = findById(item.id)
            if (defaultBuiltIn != null && item.builtIn) {
                // Keep user's enabled/activationMode choices while refreshing legacy Termux text in built-in skills
                val refreshedContent = if (item.content.contains("Termux")) defaultBuiltIn.content else item.content
                val refreshedDesc = if (item.description.contains("remote/")) defaultBuiltIn.description else item.description
                byId[item.id] = item.copy(
                    name = defaultBuiltIn.name,
                    description = refreshedDesc,
                    content = refreshedContent,
                )
            } else {
                byId[item.id] = item
            }
        }
        return byId.values.toList()
    }
}

/**
 * Built-in MCP server presets (all disabled by default).
 */
object BuiltInMcpServers {
    const val GODOT_MCP_ID = "godot-mcp"

    val GODOT_MCP_PRESET = McpServerConfig(
        id = GODOT_MCP_ID,
        name = "Godot 4 Engine MCP",
        description = "Local Godot 4.7 scene, node, script, and project manipulation server running inside the PRoot container.",
        enabled = false,
        builtInPreset = true,
        transport = McpTransportType.LOCAL_STDIO,
        localityMode = McpLocalityMode.AUTO_BY_TRANSPORT,
        url = "",
        headers = emptyMap(),
        disableOAuth = true,
        command = listOf("/usr/local/bin/node", "/usr/local/lib/godot-mcp/build/index.js"),
        environment = mapOf("GODOT_PATH" to "/usr/local/bin/godot"),
        directToolExposure = true,
        protocolVersion = "auto",
        timeoutMs = 30_000,
        usageHint = "Prefer godot-mcp tools for inspecting and editing Godot scenes (.tscn), nodes, signals, and project settings.",
    )

    fun all(): List<McpServerConfig> = listOf(GODOT_MCP_PRESET)

    fun findById(id: String): McpServerConfig? = all().firstOrNull { it.id == id }

    fun mergeWithSaved(saved: List<McpServerConfig>): List<McpServerConfig> {
        if (saved.isEmpty()) return all()
        val byId = LinkedHashMap<String, McpServerConfig>()
        for (preset in all()) {
            byId[preset.id] = preset
        }
        for (item in saved) {
            if (item.id == GODOT_MCP_ID && item.builtInPreset && item.url == "http://127.0.0.1:6505/mcp") {
                // Migrate legacy Termux remote default to the new Local PRoot default
                byId[item.id] = GODOT_MCP_PRESET.copy(enabled = item.enabled)
            } else {
                byId[item.id] = item
            }
        }
        return byId.values.toList()
    }
}

/**
 * Parses and formats standard `SKILL.md` files with `---` YAML frontmatter.
 */
object SkillMarkdownCodec {
    fun formatSkillMarkdown(skill: AgentSkill): String = buildString {
        appendLine("---")
        appendLine("name: ${skill.id}")
        appendLine("description: ${skill.description.replace("\n", " ").trim()}")
        if (skill.filePatterns.isNotEmpty() || skill.triggerKeywords.isNotEmpty()) {
            val triggers = (skill.filePatterns + skill.triggerKeywords).joinToString(", ")
            appendLine("when_to_use: $triggers")
        }
        if (skill.allowedTools.isNotEmpty()) {
            appendLine("allowed-tools: ${skill.allowedTools.joinToString(", ")}")
        }
        appendLine("---")
        appendLine()
        appendLine(skill.content.trim())
    }

    fun parseSkillMarkdown(rawMarkdown: String, fallbackName: String = "Custom Skill"): AgentSkill {
        val normalized = rawMarkdown.replace("\r\n", "\n").replace('\r', '\n').trim()
        var name = fallbackName
        var description = "Imported custom skill"
        val filePatterns = mutableListOf<String>()
        val triggerKeywords = mutableListOf<String>()
        val allowedTools = mutableListOf<String>()
        var body = normalized

        if (normalized.startsWith("---\n")) {
            val endIdx = normalized.indexOf("\n---", startIndex = 4)
            if (endIdx > 0) {
                val frontmatter = normalized.substring(4, endIdx)
                body = normalized.substring(endIdx + 4).trim()
                frontmatter.lineSequence().forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                        when (key) {
                            "name", "id" -> if (value.isNotBlank()) name = value
                            "description" -> if (value.isNotBlank()) description = value
                            "file_patterns", "files" -> filePatterns += value.split(',').map(String::trim).filter(String::isNotBlank)
                            "when_to_use", "keywords", "triggers" -> triggerKeywords += value.split(',').map(String::trim).filter(String::isNotBlank)
                            "allowed-tools", "allowed_tools" -> allowedTools += value.split(',').map(String::trim).filter(String::isNotBlank)
                        }
                    }
                }
            }
        }
        val slug = projectSlug(name).ifBlank { "custom-skill" }
        return AgentSkill(
            id = slug,
            name = name,
            description = description,
            enabled = false,
            builtIn = false,
            activationMode = SkillActivationMode.ALWAYS_WHEN_ENABLED,
            filePatterns = filePatterns.distinct(),
            triggerKeywords = triggerKeywords.distinct(),
            allowedTools = allowedTools.distinct(),
            content = body.ifBlank { "# $name" },
        )
    }
}
