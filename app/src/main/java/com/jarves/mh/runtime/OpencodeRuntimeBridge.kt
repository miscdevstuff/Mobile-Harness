package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.isKeylessAllowed
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelResponseParser
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal sealed interface OpencodeParsedEvent {
    data class SessionIdentified(val sessionId: String) : OpencodeParsedEvent
    data class Reasoning(val partId: String, val text: String) : OpencodeParsedEvent
    data class ToolUse(
        val callId: String,
        val toolName: String,
        val detail: String,
        val completed: Boolean,
    ) : OpencodeParsedEvent
    data class Text(val text: String) : OpencodeParsedEvent
    data class StepFinished(val reason: String, val sessionId: String?) : OpencodeParsedEvent
    data class Error(val message: String) : OpencodeParsedEvent
}

internal object OpencodeEventParser {
    fun parse(line: String): List<OpencodeParsedEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val events = mutableListOf<OpencodeParsedEvent>()

        val topSessionId = json.optString("sessionID").takeIf(String::isNotBlank)
            ?: json.optJSONObject("part")?.optString("sessionID")?.takeIf(String::isNotBlank)
        if (topSessionId != null) {
            events += OpencodeParsedEvent.SessionIdentified(topSessionId)
        }

        when (val type = json.optString("type")) {
            "error" -> {
                val errorObj = json.optJSONObject("error")
                val message = errorObj?.let { err ->
                    err.optString("message")
                        .ifBlank { err.optJSONObject("data")?.optString("message").orEmpty() }
                        .ifBlank { err.optString("name") }
                }.orEmpty().ifBlank { json.optString("message") }.ifBlank { trimmed.take(500) }
                events += OpencodeParsedEvent.Error(message)
            }
            "reasoning" -> {
                val part = json.optJSONObject("part") ?: json
                val partId = part.optString("id").ifBlank { part.optString("partID") }.ifBlank { "reasoning" }
                val text = part.optString("text").trim()
                if (text.isNotBlank()) {
                    events += OpencodeParsedEvent.Reasoning(partId, text)
                }
            }
            "tool_use" -> {
                val part = json.optJSONObject("part") ?: json
                val callId = part.optString("id").ifBlank { part.optString("partID") }.ifBlank { UUID.randomUUID().toString() }
                val rawTool = part.optString("tool").ifBlank { part.optString("name") }.ifBlank { "tool" }
                val state = part.optJSONObject("state")
                val status = state?.optString("status").orEmpty()
                val displayName = opencodeToolDisplayName(rawTool)
                val detail = opencodeToolDetail(rawTool, state)
                val completed = status.isEmpty() || status == "completed" || status == "error"
                events += OpencodeParsedEvent.ToolUse(
                    callId = callId,
                    toolName = displayName,
                    detail = detail,
                    completed = completed,
                )
            }
            "text" -> {
                val part = json.optJSONObject("part")
                val text = part?.optString("text").orEmpty().ifBlank { json.optString("text") }
                if (text.isNotEmpty()) {
                    events += OpencodeParsedEvent.Text(text)
                }
            }
            "step_finish" -> {
                val part = json.optJSONObject("part")
                val reason = part?.optString("reason").orEmpty()
                events += OpencodeParsedEvent.StepFinished(reason, topSessionId)
            }
            else -> {
                if (type.contains("tool", ignoreCase = true)) {
                    val part = json.optJSONObject("part") ?: json
                    val rawTool = part.optString("tool").ifBlank { part.optString("name") }.ifBlank { "tool" }
                    val displayName = opencodeToolDisplayName(rawTool)
                    val detail = opencodeToolDetail(rawTool, part.optJSONObject("state"))
                    events += OpencodeParsedEvent.ToolUse(
                        callId = part.optString("id").ifBlank { UUID.randomUUID().toString() },
                        toolName = displayName,
                        detail = detail,
                        completed = true,
                    )
                }
            }
        }
        return events
    }

    internal fun opencodeToolDisplayName(rawTool: String): String = when (rawTool.lowercase()) {
        "shell", "bash" -> "Bash"
        "read", "view" -> "Read"
        "write", "create" -> "Write"
        "edit", "patch", "multiedit", "str_replace_editor" -> "Edit"
        "glob", "list" -> "Glob"
        "grep", "search", "websearch" -> "Grep"
        "webfetch" -> "Read"
        else -> rawTool.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    internal fun opencodeToolDetail(rawTool: String, state: JSONObject?): String {
        if (state == null) return "Running ${opencodeToolDisplayName(rawTool)}"
        val input = state.optJSONObject("input")
        val output = redactSecrets(state.optString("output").replace("\r\n", "\n").replace('\r', '\n').trim())
        val error = redactSecrets(state.optString("error").replace("\r\n", "\n").replace('\r', '\n').trim())
        val body = output.ifBlank { error }

        return when (rawTool.lowercase()) {
            "shell", "bash" -> {
                val command = redactSecrets(input?.optString("command").orEmpty().trim())
                val exitCode = state.optJSONObject("metadata")
                    ?.optJSONObject("metadata")
                    ?.takeIf { it.has("exit") }
                    ?.optInt("exit", 0)
                buildString {
                    append(if (command.isNotBlank()) "$ $command" else "$ shell")
                    if (body.isNotBlank()) {
                        append("\n")
                        append(if (body.length <= MAX_TOOL_OUTPUT_CHARS) body else "…\n" + body.takeLast(MAX_TOOL_OUTPUT_CHARS))
                    }
                    if (exitCode != null && exitCode != 0 && !body.contains("exit", ignoreCase = true)) {
                        append("\n[exit $exitCode]")
                    }
                }
            }
            "write", "create" -> {
                val path = input?.optString("path").orEmpty()
                    .ifBlank { input?.optString("filePath").orEmpty() }
                    .ifBlank { input?.optString("file_path").orEmpty() }
                val content = input?.optString("content").orEmpty()
                buildString {
                    if (path.isNotBlank()) append(path)
                    if (body.isNotBlank() && body != path) {
                        if (isNotEmpty()) append(" — ")
                        append(body.lineSequence().firstOrNull().orEmpty().take(160))
                    }
                    if (content.isNotBlank()) {
                        val preview = content.replace("\r\n", "\n").trimEnd()
                        val ext = path.substringAfterLast('.', "").lowercase()
                        append("\n\n```")
                        append(ext)
                        append("\n")
                        append(preview.take(MAX_CODE_PREVIEW_CHARS))
                        if (preview.length > MAX_CODE_PREVIEW_CHARS) append("\n…")
                        append("\n```")
                    }
                }.ifBlank { "Wrote file" }
            }
            "edit", "patch", "multiedit", "str_replace_editor" -> {
                val path = input?.optString("path").orEmpty()
                    .ifBlank { input?.optString("filePath").orEmpty() }
                    .ifBlank { input?.optString("file_path").orEmpty() }
                val newString = input?.optString("newString").orEmpty()
                    .ifBlank { input?.optString("new_str").orEmpty() }
                    .ifBlank { input?.optString("patch").orEmpty() }
                buildString {
                    if (path.isNotBlank()) append(path)
                    if (body.isNotBlank() && body != path) {
                        if (isNotEmpty()) append(" — ")
                        append(body.take(400))
                    }
                    if (newString.isNotBlank()) {
                        val ext = path.substringAfterLast('.', "").lowercase()
                        append("\n\n```")
                        append(ext)
                        append("\n")
                        append(newString.trimEnd().take(MAX_CODE_PREVIEW_CHARS))
                        if (newString.length > MAX_CODE_PREVIEW_CHARS) append("\n…")
                        append("\n```")
                    }
                }.ifBlank { "Edited file" }
            }
            "read", "view" -> {
                val path = input?.optString("path").orEmpty()
                    .ifBlank { input?.optString("filePath").orEmpty() }
                    .ifBlank { input?.optString("file_path").orEmpty() }
                buildString {
                    if (path.isNotBlank()) append(path)
                    if (body.isNotBlank() && body != path) {
                        if (isNotEmpty()) append(" — ")
                        append(body.take(MAX_TOOL_OUTPUT_CHARS))
                    }
                }.ifBlank { "Read file" }
            }
            "webfetch", "websearch", "glob", "grep", "search", "list" -> {
                val target = listOf("url", "query", "pattern", "path")
                    .firstNotNullOfOrNull { key -> input?.optString(key)?.takeIf(String::isNotBlank) }
                    .orEmpty()
                buildString {
                    if (target.isNotBlank()) append(target)
                    if (body.isNotBlank() && body != target) {
                        if (isNotEmpty()) append("\n\n")
                        append(body.take(MAX_TOOL_OUTPUT_CHARS))
                    }
                }.ifBlank { "Completed $rawTool" }
            }
            else -> {
                val target = listOf("path", "filePath", "command", "query", "pattern", "url")
                    .firstNotNullOfOrNull { key -> input?.optString(key)?.takeIf(String::isNotBlank) }
                    .orEmpty()
                buildString {
                    if (target.isNotBlank()) append(target)
                    if (body.isNotBlank() && body != target) {
                        if (isNotEmpty()) append(" — ")
                        append(body.take(MAX_TOOL_OUTPUT_CHARS))
                    }
                }.ifBlank { "Completed $rawTool" }
            }
        }
    }

    private fun redactSecrets(value: String): String = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
        .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")

    private const val MAX_CODE_PREVIEW_CHARS = 1_200
    private const val MAX_TOOL_OUTPUT_CHARS = 2_400
}

/**
 * [RuntimeBridge] driving the official OpenCode v2 CLI in isolated standalone mode:
 * `/usr/local/bin/opencode run --standalone --format json --thinking --auto --agent build`.
 */
class OpencodeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val conversationId: (String) -> String? = { null },
    private val saveConversationId: (String, String?) -> Unit = { _, _ -> },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    /**
     * Runs a fast one-turn CLI probe inside PRoot to verify that the selected model
     * and credentials work through the official OpenCode v2 binary.
     */
    suspend fun hello(
        provider: ProviderProfile,
        secretOverride: String? = null,
        timeoutMillis: Long = 60_000L,
    ): String = withContext(Dispatchers.IO) {
        check(installer.isAgentInstalled(AgentKind.OPENCODE)) {
            "OpenCode is not installed. Open Settings → Coding agent to install it."
        }
        val secret = secretOverride?.takeIf(String::isNotBlank)
            ?: secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank() && !provider.kind.isKeylessAllowed(provider.model)) {
            error("No API key is saved for ${provider.kind.title}.")
        }
        val installed = installer.installedRuntime()
        val probeDir = File(context.cacheDir, "opencode-hello-${System.nanoTime()}").apply { mkdirs() }
        val outputFile = File(context.cacheDir, "opencode-hello-${System.nanoTime()}.log")
        val modelFlag = opencodeModelFlag(provider)
        val command = buildList {
            add(OPENCODE_GUEST_PATH)
            add("run")
            add("--standalone")
            add("--format")
            add("json")
            add("--auto")
            add("--agent")
            add("build")
            if (modelFlag != null) {
                add("--model")
                add(modelFlag)
            }
            add("Reply with exactly: OK")
        }
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = probeDir,
            environment = opencodeEnvironment(provider, secret),
            guestCommand = command,
            guestWorkspacePath = "/workspace/opencode-hello",
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        try {
            runCatching { process.outputStream.close() }
            withTimeout(timeoutMillis) {
                while (process.isAlive) delay(80)
            }
            val exit = process.waitFor()
            val output = outputFile.takeIf(File::isFile)?.readTailText(MAX_PENDING_CHARS).orEmpty()
            var cliError: String? = null
            var replyText: String? = null
            output.lineSequence().forEach { line ->
                OpencodeEventParser.parse(line).forEach { event ->
                    when (event) {
                        is OpencodeParsedEvent.Error -> if (cliError.isNullOrBlank()) cliError = event.message
                        is OpencodeParsedEvent.Text -> if (replyText.isNullOrBlank()) replyText = event.text.trim()
                        else -> Unit
                    }
                }
            }
            if (!cliError.isNullOrBlank()) {
                throw OpencodeSessionException(cliError!!)
            }
            check(exit == 0) {
                val diagnostic = output.lineSequence()
                    .map(String::trim)
                    .filter { it.isNotBlank() && !it.startsWith("proot warning:", ignoreCase = true) }
                    .lastOrNull()
                    .orEmpty()
                diagnostic.ifBlank { "OpenCode exited with code $exit" }
            }
            replyText?.takeIf(String::isNotBlank) ?: "OK"
        } catch (e: TimeoutCancellationException) {
            throw OpencodeSessionException("OpenCode did not answer in time. Check your connection and try again.")
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
            runCatching { probeDir.deleteRecursively() }
        }
    }

    /**
     * Queries `/usr/local/bin/opencode models --standalone` inside PRoot to list the
     * canonical models registered in the installed OpenCode v2 binary.
     */
    suspend fun discoverCliModels(
        provider: ProviderProfile,
        secretOverride: String? = null,
        timeoutMillis: Long = 30_000L,
    ): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.OPENCODE)) return@withContext emptyList()
        val secret = secretOverride?.takeIf(String::isNotBlank) ?: secretFor(provider).orEmpty()
        val installed = installer.installedRuntime()
        val probeDir = File(context.cacheDir, "opencode-models-${System.nanoTime()}").apply { mkdirs() }
        val outputFile = File(context.cacheDir, "opencode-models-${System.nanoTime()}.log")
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = probeDir,
            environment = opencodeEnvironment(provider, secret),
            guestCommand = listOf(OPENCODE_GUEST_PATH, "models", "--standalone"),
            guestWorkspacePath = "/workspace/opencode-models",
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        try {
            runCatching { process.outputStream.close() }
            withTimeout(timeoutMillis) {
                while (process.isAlive) delay(60)
            }
            val exit = process.waitFor()
            if (exit != 0) return@withContext emptyList()
            val output = outputFile.takeIf(File::isFile)?.readTailText(MAX_PENDING_CHARS).orEmpty()
            ModelResponseParser.parseOpencodeCliOutput(output, providerPrefixForKind(provider.kind))
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
            runCatching { probeDir.deleteRecursively() }
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress(R.string.rt_bridge_starting, AGENT_DISPLAY_NAME)

        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank() && !provider.kind.isKeylessAllowed(provider.model)) {
            emitFailureOnce(
                sessionId,
                "No API key is saved for ${provider.kind.title}.",
            )
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(AgentKind.OPENCODE)) {
                "OpenCode is not installed. Open Settings → Coding agent to install it."
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val guestWorkspacePath = "/workspace/$projectSlug"
            val attachments = conversationHistory.lastOrNull { it.fromUser }?.attachments.orEmpty()
            val savedOpencodeSession = conversationId(projectId)?.takeIf { it.startsWith("ses_") }

            var outcome = executeOpencodeRun(
                installed = installed,
                workspace = workspace,
                guestWorkspacePath = guestWorkspacePath,
                projectId = projectId,
                projectKind = projectKind,
                prompt = prompt,
                conversationHistory = conversationHistory,
                attachments = attachments,
                provider = provider,
                secret = secret,
                sessionId = sessionId,
                resumeOpencodeSessionId = savedOpencodeSession,
            )

            // If a previously saved OpenCode session ID was pruned or belonged to another
            // directory, clear it and retry once with the full conversation context.
            if (!outcome.completed && !userStopRequested && savedOpencodeSession != null && !outcome.emittedAssistantText) {
                Log.w("OpencodeBridge", "Resumed session $savedOpencodeSession failed (${outcome.failure}); retrying fresh session")
                saveConversationId(projectId, null)
                outcome = executeOpencodeRun(
                    installed = installed,
                    workspace = workspace,
                    guestWorkspacePath = guestWorkspacePath,
                    projectId = projectId,
                    projectKind = projectKind,
                    prompt = prompt,
                    conversationHistory = conversationHistory,
                    attachments = attachments,
                    provider = provider,
                    secret = secret,
                    sessionId = sessionId,
                    resumeOpencodeSessionId = null,
                )
            }

            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("OpencodeBridge", "Changed files: $changed")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }

            if (outcome.completed && outcome.failure.isBlank() && !userStopRequested) {
                emitCompletedOnce(sessionId)
            } else {
                if (userStopRequested) throw OpencodeSessionException("Stopped by user")
                error(outcome.failure.ifBlank { "OpenCode stopped with exit code ${outcome.exitCode}" })
            }
        }.onFailure { error ->
            Log.e("OpencodeBridge", "Session failed", error)
            emitFailureOnce(sessionId, friendlyError(error))
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    private data class OpencodeRunOutcome(
        val completed: Boolean,
        val exitCode: Int,
        val failure: String,
        val emittedAssistantText: Boolean,
    )

    private suspend fun executeOpencodeRun(
        installed: InstalledRuntime,
        workspace: File,
        guestWorkspacePath: String,
        projectId: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        attachments: List<com.jarves.mh.model.ChatAttachment>,
        provider: ProviderProfile,
        secret: String,
        sessionId: String,
        resumeOpencodeSessionId: String?,
    ): OpencodeRunOutcome {
        val contextPrompt = buildContextPrompt(
            currentPrompt = prompt,
            history = if (resumeOpencodeSessionId != null) emptyList() else conversationHistory,
            guestWorkspacePath = guestWorkspacePath,
            projectKind = projectKind,
        )
        val command = buildOpencodeCommand(
            provider = provider,
            prompt = contextPrompt,
            resumeSessionId = resumeOpencodeSessionId,
            attachmentGuestPaths = attachments.map { "$guestWorkspacePath/${it.relativePath}" },
        )
        Log.d("OpencodeBridge", "Launching opencode model=${opencodeModelFlag(provider) ?: "<default>"} resume=${resumeOpencodeSessionId ?: "new"}")
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = opencodeEnvironment(provider, secret),
            guestCommand = command,
            guestWorkspacePath = guestWorkspacePath,
            emulateHardLinks = false,
        )
        // Close stdin immediately (same as `< /dev/null`) so opencode run never waits for piped EOF.
        runCatching { process.outputStream.close() }

        activeProcess = process
        if (userStopRequested) process.destroy()

        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        var offset = 0L
        val pending = StringBuilder()
        val streamed = StringBuilder()
        var failure = ""
        var nextReasoningBlockId = 1L
        val reasoningBlocks = LinkedHashMap<String, Long>()

        suspend fun processLine(line: String) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return
            val parsed = OpencodeEventParser.parse(trimmed)
            if (parsed.isEmpty()) {
                if (!trimmed.startsWith("proot warning:", ignoreCase = true) &&
                    !trimmed.startsWith("{")
                ) {
                    ProviderRuntimeErrorDetector.detect(trimmed)?.let { detected ->
                        if (failure.isBlank()) failure = detected
                    }
                }
                return
            }
            for (event in parsed) {
                when (event) {
                    is OpencodeParsedEvent.SessionIdentified -> {
                        saveConversationId(projectId, event.sessionId)
                    }
                    is OpencodeParsedEvent.Error -> {
                        if (failure.isBlank()) failure = event.message
                    }
                    is OpencodeParsedEvent.Reasoning -> {
                        val isNew = event.partId !in reasoningBlocks
                        val blockId = reasoningBlocks.getOrPut(event.partId) { nextReasoningBlockId++ }
                        pushForegroundProgress(R.string.rt_bridge_thinking)
                        eventBus.emit(
                            RuntimeEvent.ReasoningSummary(
                                sessionId = sessionId,
                                summary = event.text.take(MAX_REASONING_SUMMARY_CHARS),
                                blockId = blockId,
                                startsNewBlock = isNew,
                                isFinal = true,
                            ),
                        )
                    }
                    is OpencodeParsedEvent.ToolUse -> {
                        val compactDetail = event.detail.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { event.toolName }
                        pushForegroundProgress(R.string.rt_bridge_running_tool, event.toolName, compactDetail)
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.toolName, event.detail))
                        if (event.completed) {
                            eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.toolName, event.detail))
                        }
                        detectPreviewUrlInToolOutput(event.detail)?.let { url ->
                            eventBus.emit(RuntimeEvent.PreviewStarted(sessionId, url))
                        }
                    }
                    is OpencodeParsedEvent.Text -> {
                        pushForegroundProgress(R.string.rt_bridge_working, AGENT_DISPLAY_NAME)
                        emitTextDelta(sessionId, streamed, event.text)
                    }
                    is OpencodeParsedEvent.StepFinished -> Unit
                }
            }
        }

        while (process.isAlive || (nativeProcess.outputFile.length() > offset)) {
            val file = nativeProcess.outputFile
            if (file.length() > offset) {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                    val bytes = ByteArray(available)
                    input.readFully(bytes)
                    offset += available
                    pending.append(bytes.toString(Charsets.UTF_8).replace('\r', '\n'))
                }
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    val line = pending.substring(0, newline)
                    pending.delete(0, newline + 1)
                    processLine(line)
                    newline = pending.indexOf("\n")
                    if (pending.length > MAX_PENDING_CHARS) {
                        pending.delete(0, pending.length - MAX_PENDING_CHARS)
                    }
                }
            } else {
                delay(60)
            }
        }
        val tail = pending.toString().trim()
        if (tail.isNotBlank()) {
            processLine(tail)
        }
        val exit = process.waitFor()
        Log.d("OpencodeBridge", "opencode run exited with code $exit")
        return OpencodeRunOutcome(
            completed = exit == 0 && failure.isBlank(),
            exitCode = exit,
            failure = failure,
            emittedAssistantText = streamed.isNotEmpty(),
        )
    }

    private fun detectPreviewUrlInToolOutput(detail: String): String? {
        val match = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d{2,5})(?:/[^\\s]*)?")
            .findAll(detail)
            .lastOrNull()
            ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "http://127.0.0.1:$port/"
    }

    private suspend fun emitTextDelta(sessionId: String, streamed: StringBuilder, text: String) {
        if (streamed.length >= MAX_STREAMED_CHARS) return
        val bounded = text.take(MAX_STREAMED_CHARS - streamed.length)
        if (bounded.isEmpty()) return
        streamed.append(bounded)
        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, bounded))
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // `--auto` approves non-denied permissions in headless standalone mode.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) return@withContext false
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile || target.exists()) {
                target.deleteRecursively()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                detail = AppStrings.get(context, R.string.rt_bridge_finished, AGENT_DISPLAY_NAME),
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is OpencodeSessionException && message.isNotBlank() -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("unauthorized", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("not installed", true) -> message.take(300)
            message.isBlank() -> "OpenCode could not start."
            else -> message.take(500)
        }
    }

    private fun pushForegroundProgress(@androidx.annotation.StringRes id: Int, vararg args: Any) {
        if (activeSessionId == null) return
        if (android.os.SystemClock.elapsedRealtime() - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        pushForegroundProgress(AppStrings.get(context, id, *args))
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("OpencodeBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    companion object {
        const val OPENCODE_GUEST_PATH = "/usr/local/bin/opencode"
        private const val AGENT_DISPLAY_NAME = "OpenCode"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        private const val MAX_REASONING_SUMMARY_CHARS = 2_000
        private const val MAX_STREAMED_CHARS = 200_000
        private const val MAX_PENDING_CHARS = 256 * 1024

        internal fun buildOpencodeCommand(
            provider: ProviderProfile,
            prompt: String,
            resumeSessionId: String? = null,
            attachmentGuestPaths: List<String> = emptyList(),
        ): List<String> = buildList {
            add(OPENCODE_GUEST_PATH)
            add("run")
            add("--standalone")
            add("--format")
            add("json")
            add("--thinking")
            add("--auto")
            add("--agent")
            add("build")
            opencodeModelFlag(provider)?.let { model ->
                add("--model")
                add(model)
            }
            resumeSessionId?.takeIf(String::isNotBlank)?.let { session ->
                add("--session")
                add(session)
            }
            attachmentGuestPaths.forEach { filePath ->
                if (filePath.isNotBlank()) {
                    add("--file")
                    add(filePath)
                }
            }
            add(prompt)
        }

        internal fun providerPrefixForKind(kind: ProviderKind): String = when (kind) {
            ProviderKind.OPENCODE_CLI, ProviderKind.OPENCODE_ZEN -> "opencode/"
            ProviderKind.LLM_ROUTER -> "openrouter/"
            ProviderKind.NVIDIA_NIM -> "nvidia/"
            ProviderKind.DEEPSEEK -> "deepseek/"
            ProviderKind.ANTHROPIC, ProviderKind.CLAUDE -> "anthropic/"
            ProviderKind.KIMI -> "moonshotai/"
            ProviderKind.CUSTOM -> ""
        }

        /**
         * Formats the selected provider and model into OpenCode v2's required `<providerID>/<modelID>` format.
         * Safely handles models that already contain a `/` (such as OpenRouter and NVIDIA NIM models).
         */
        fun opencodeModelFlag(provider: ProviderProfile): String? {
            val rawModel = provider.model.trim().removePrefix("~")
            if (rawModel.isEmpty() || rawModel == "default") return null
            if (provider.kind == ProviderKind.CUSTOM) {
                return rawModel.takeIf { "/" in it }
            }
            val prefix = providerPrefixForKind(provider.kind)
            return if (rawModel.startsWith(prefix)) rawModel else "$prefix$rawModel"
        }

        /** API keys travel in the process environment under each provider's conventional variable. */
        fun opencodeEnvironment(provider: ProviderProfile, secret: String): Map<String, String> {
            val environment = linkedMapOf(
                "DISABLE_AUTOUPDATER" to "1",
                "DISABLE_TELEMETRY" to "1",
                "DO_NOT_TRACK" to "1",
            )
            val cleanSecret = secret.trim().removePrefix("Bearer ").removePrefix("bearer ").filterNot(Char::isWhitespace)
            if (cleanSecret.isBlank()) return environment
            val baseUrl = provider.resolvedBaseUrl.trim().trimEnd('/')
            when (provider.kind) {
                ProviderKind.CLAUDE -> {
                    environment["ANTHROPIC_API_KEY"] = cleanSecret
                    environment["CLAUDE_CODE_OAUTH_TOKEN"] = cleanSecret
                }
                ProviderKind.ANTHROPIC -> environment["ANTHROPIC_API_KEY"] = cleanSecret
                ProviderKind.LLM_ROUTER -> environment["OPENROUTER_API_KEY"] = cleanSecret
                ProviderKind.DEEPSEEK -> environment["DEEPSEEK_API_KEY"] = cleanSecret
                ProviderKind.KIMI -> environment["MOONSHOT_API_KEY"] = cleanSecret
                ProviderKind.OPENCODE_CLI, ProviderKind.OPENCODE_ZEN -> environment["OPENCODE_API_KEY"] = cleanSecret
                ProviderKind.NVIDIA_NIM -> {
                    environment["NVIDIA_API_KEY"] = cleanSecret
                    environment["NVIDIA_NIM_API_KEY"] = cleanSecret
                }
                ProviderKind.CUSTOM -> {
                    when (provider.kind.protocol) {
                        ProviderProtocol.OPENAI_CHAT,
                        ProviderProtocol.OPENAI_RESPONSES,
                        -> {
                            environment["OPENAI_API_KEY"] = cleanSecret
                            if (baseUrl.isNotBlank()) environment["OPENAI_BASE_URL"] = baseUrl
                        }
                        else -> {
                            environment["ANTHROPIC_API_KEY"] = cleanSecret
                            if (baseUrl.isNotBlank()) environment["ANTHROPIC_BASE_URL"] = baseUrl
                        }
                    }
                    return environment
                }
            }
            if (baseUrl.isNotBlank() && baseUrl != provider.kind.defaultBaseUrl &&
                (provider.kind == ProviderKind.ANTHROPIC || provider.kind == ProviderKind.CLAUDE)
            ) {
                environment["ANTHROPIC_BASE_URL"] = baseUrl
            }
            return environment
        }
    }
}

internal class OpencodeSessionException(message: String) : IllegalStateException(message)
