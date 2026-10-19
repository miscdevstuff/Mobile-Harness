package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.appJson
import com.jarves.mh.model.DevStack
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class RuntimeSetupStatus { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

@Serializable
data class RuntimeSetupSnapshot(
    val status: RuntimeSetupStatus = RuntimeSetupStatus.IDLE,
    val message: String = "Preparing your private coding workspace",
    val progress: Float = 0f,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val indeterminate: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val offline: Boolean = false,
)

object RuntimeSetupController {
    private val mutableSnapshot = MutableStateFlow(RuntimeSetupSnapshot())
    val snapshot: StateFlow<RuntimeSetupSnapshot> = mutableSnapshot.asStateFlow()
    @Volatile private var lastPersistAtElapsedRealtime: Long = 0L

    @Synchronized
    fun restore(context: Context) {
        val file = stateFile(context)
        if (!file.isFile) return
        runCatching {
            val decoded = appJson.decodeFromString<RuntimeSetupSnapshot>(file.readText())
            mutableSnapshot.value = decoded.copy(
                message = decoded.message.ifBlank { AppStrings.get(context, R.string.rt_setup_preparing_workspace) },
                logs = decoded.logs.takeLast(MAX_LOG_LINES),
                errorMessage = decoded.errorMessage?.takeIf(String::isNotBlank),
            )
        }
    }

    @Synchronized
    fun begin(context: Context) {
        val previous = mutableSnapshot.value.logs
        val logs = (previous + "— Resuming Mobile Harness setup —").takeLast(MAX_LOG_LINES)
        set(
            context,
            RuntimeSetupSnapshot(
                status = RuntimeSetupStatus.RUNNING,
                message = AppStrings.get(context, R.string.rt_setup_preparing_workspace),
                progress = 0.01f,
                logs = logs,
            ),
        )
    }

    @Synchronized
    fun update(context: Context, event: RuntimeInstallProgress) {
        val current = mutableSnapshot.value
        val line = event.terminalLine ?: when {
            event.downloadedBytes != null && event.totalBytes != null ->
                "Downloading: %.1f / %.1f MB".format(event.downloadedBytes / MB, event.totalBytes / MB)
            event.event == RuntimeInstallEvent.STAGE -> "• ${event.message}"
            else -> null
        }
        val sanitizedLine = line?.takeIf(String::isNotBlank)?.let(::sanitize)
        val isDownloadUpdate = event.downloadedBytes != null && event.totalBytes != null
        val logs = when {
            sanitizedLine == null -> current.logs
            isDownloadUpdate && current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) == true ->
                (current.logs.dropLast(1) + sanitizedLine).takeLast(MAX_LOG_LINES)
            else -> (current.logs + sanitizedLine).takeLast(MAX_LOG_LINES)
        }
        // Persist the beginning of a transfer as a checkpoint, while the JSON snapshot
        // continuously replaces that row with the newest byte count.
        if (!isDownloadUpdate || current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) != true) {
            appendLog(context, sanitizedLine)
        }
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.RUNNING,
                // Raw command output changes length constantly. Keep the headline stage
                // stable and show changing lines only in the live terminal panel.
                message = when (event.event) {
                    RuntimeInstallEvent.STAGE, RuntimeInstallEvent.DOWNLOAD -> event.message
                    else -> current.message
                },
                progress = maxOf(current.progress, event.fraction.coerceIn(0f, 1f)),
                downloadedBytes = event.downloadedBytes,
                totalBytes = event.totalBytes,
                indeterminate = event.indeterminate,
                logs = logs,
                errorMessage = null,
                offline = false,
            ),
            forcePersist = event.event == RuntimeInstallEvent.STAGE ||
                event.event == RuntimeInstallEvent.COMMAND_COMPLETED ||
                event.event == RuntimeInstallEvent.COMPLETED,
        )
    }

    @Synchronized
    fun complete(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.COMPLETE,
                message = AppStrings.get(context, R.string.rt_setup_ready),
                progress = 1f,
                indeterminate = false,
                downloadedBytes = null,
                totalBytes = null,
                logs = (current.logs + "✓ Setup completed successfully").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun fail(context: Context, error: Throwable) {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val offline = causes.any { cause ->
            val msg = cause.message.orEmpty()
            cause is UnknownHostException ||
                msg.contains("unable to resolve host", true) ||
                msg.contains("no address associated with hostname", true) ||
                msg.contains("temporary failure in name resolution", true) ||
                msg.contains("network is unreachable", true) ||
                msg.contains("failed to connect to", true) ||
                msg.contains("connection timed out", true) ||
                msg.contains("ssl connection timeout", true) ||
                listOf("curl: (6)", "curl: (7)", "curl: (28)", "curl: (35)", "curl: (56)").any { msg.contains(it, true) }
        }
        val interruptedDpkg = causes.any {
            it.message.orEmpty().contains("dpkg was interrupted", true) ||
                it.message.orEmpty().contains("dpkg --configure -a", true)
        }
        val friendly = when {
            offline -> AppStrings.get(context, R.string.rt_setup_offline)
            interruptedDpkg -> AppStrings.get(context, R.string.rt_setup_dpkg_interrupted)
            else -> error.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                ?.take(220)
                ?: AppStrings.get(context, R.string.rt_setup_failed_generic)
        }
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.ERROR,
                errorMessage = friendly,
                offline = offline,
                indeterminate = false,
                logs = (current.logs + "✕ $friendly").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun cancelled(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.CANCELLED,
                message = AppStrings.get(context, R.string.rt_setup_paused),
                indeterminate = false,
                logs = (current.logs + "• Setup paused safely").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    fun fullLog(context: Context): String = logFile(context).readTailText(MAX_LOG_BYTES.toInt())

    private fun set(context: Context, value: RuntimeSetupSnapshot, forcePersist: Boolean = true) {
        mutableSnapshot.value = value
        val now = android.os.SystemClock.elapsedRealtime()
        if (forcePersist || now - lastPersistAtElapsedRealtime >= PERSIST_THROTTLE_MS) {
            lastPersistAtElapsedRealtime = now
            persist(context, value)
        }
    }

    private fun persist(context: Context, value: RuntimeSetupSnapshot) {
        val target = stateFile(context)
        val staged = File(target.parentFile, "${target.name}.tmp")
        staged.writeText(appJson.encodeToString(value))
        target.delete()
        staged.renameTo(target)
    }

    private fun appendLog(context: Context, line: String?) {
        if (line.isNullOrBlank()) return
        val file = logFile(context)
        file.parentFile?.mkdirs()
        file.appendText(sanitize(line) + "\n")
        if (file.length() > MAX_LOG_BYTES) {
            val tail = file.readTailText(MAX_LOG_BYTES.toInt())
            file.writeText(tail.substringAfter('\n', tail))
        }
    }

    private fun sanitize(line: String): String = line.filter { it == '\t' || it.code >= 32 }.take(500)
    private fun stateFile(context: Context) = File(context.filesDir, "setup/runtime-setup-state.json").apply { parentFile?.mkdirs() }
    private fun logFile(context: Context) = File(context.filesDir, "setup/runtime-setup.log")

    private const val MAX_LOG_LINES = 400
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MB = 1_048_576.0
    private const val DOWNLOAD_PREFIX = "Downloading:"
    private const val PERSIST_THROTTLE_MS = 1_000L
}

class RuntimeSetupService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var installJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        RuntimeSetupController.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            installJob?.cancel(CancellationException("Stopped by user"))
            RuntimeSetupController.cancelled(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
        acquireWakeLock()
        if (installJob?.isActive != true) {
            val stacks = intent?.getStringExtra(EXTRA_STACKS).orEmpty().split(',')
                .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
                .toSet()
            val agent = runCatching {
                com.jarves.mh.model.AgentKind.valueOf(intent?.getStringExtra(EXTRA_AGENT).orEmpty())
            }.getOrDefault(com.jarves.mh.model.AgentKind.CLAUDE_CODE)
            RuntimeSetupController.begin(this)
            installJob = scope.launch {
                try {
                    RuntimeInstaller(this@RuntimeSetupService).ensureInstalled(stacks, agent) { progress ->
                        RuntimeSetupController.update(this@RuntimeSetupService, progress)
                        updateNotification(progress.event == RuntimeInstallEvent.COMMAND_COMPLETED)
                    }
                    AppPreferences(this@RuntimeSetupService).runtimeSetupComplete = true
                    RuntimeSetupController.complete(this@RuntimeSetupService)
                    showFinishedNotification(success = true)
                } catch (_: CancellationException) {
                    RuntimeSetupController.cancelled(this@RuntimeSetupService)
                } catch (error: Throwable) {
                    RuntimeSetupController.fail(this@RuntimeSetupService, error)
                    showFinishedNotification(success = false)
                } finally {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun updateNotification(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
    }

    private fun setupNotification(state: RuntimeSetupSnapshot): android.app.Notification {
        val latest = state.logs.lastOrNull().orEmpty().take(180)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppStrings.get(this, R.string.rt_setup_notification_title))
            .setContentText(latest.ifBlank { state.message })
            .setStyle(NotificationCompat.BigTextStyle().bigText(latest.ifBlank { state.message }))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.indeterminate) builder.setProgress(0, 0, true)
        else builder.setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), false)
        builder.addAction(
            0,
            AppStrings.get(this, R.string.rt_setup_stop),
            PendingIntent.getService(
                this,
                102,
                Intent(this, RuntimeSetupService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun showFinishedNotification(success: Boolean) {
        val state = RuntimeSetupController.snapshot.value
        val title = AppStrings.get(this, if (success) R.string.rt_setup_ready else R.string.rt_setup_needs_attention)
        val detail = if (success) AppStrings.get(this, R.string.rt_setup_ready_detail) else state.errorMessage.orEmpty()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        101,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.jarves.mh:runtime-setup")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarves.mh.START_SETUP"
        const val ACTION_STOP = "com.jarves.mh.STOP_SETUP"
        const val EXTRA_STACKS = "selected_stacks"
        const val EXTRA_AGENT = "selected_agent"
        private const val CHANNEL_ID = "runtime-setup"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val MAX_WAKE_LOCK_MS = 45 * 60 * 1_000L

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppStrings.get(context, R.string.rt_setup_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = AppStrings.get(context, R.string.rt_setup_channel_description)
                },
            )
        }
    }
}
