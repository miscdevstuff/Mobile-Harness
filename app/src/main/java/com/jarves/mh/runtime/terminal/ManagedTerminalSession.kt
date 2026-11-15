package com.jarves.mh.runtime.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.runtime.installer.TerminalLaunchSpec
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModifierKeyState {
    OFF,
    LATCHED,
    LOCKED,
}

@Stable
class ManagedTerminalSession(
    private val context: Context,
    private val preferences: AppPreferences = AppPreferences(context),
    private val createLaunchSpec: () -> Result<TerminalLaunchSpec>,
) : TerminalSessionClient, TerminalViewClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var launchJob: Job? = null

    var session by mutableStateOf<TerminalSession?>(null)
        private set

    var isStarting by mutableStateOf(false)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var ctrlState by mutableStateOf(ModifierKeyState.OFF)
        private set

    var altState by mutableStateOf(ModifierKeyState.OFF)
        private set

    var fontSizeSp by mutableIntStateOf(preferences.terminalFontSizeSp)
        private set

    var cursorStyle by mutableIntStateOf(preferences.terminalCursorStyle)
        private set

    var cursorBlink by mutableStateOf(preferences.terminalCursorBlink)
        private set

    var showExtraKeys by mutableStateOf(preferences.terminalShowExtraKeys)
        private set

    private var attachedViewRef: WeakReference<TerminalView>? = null
    private var lastAppliedTextSizePx: Int = -1
    private var pendingDraftText: String? = null
    private var hasReceivedInitialOutput: Boolean = false
    private var activeOwnerKey: String? = null

    init {
        applyTerminalColorPalette()
    }

    fun syncPreferences() {
        val savedSize = preferences.terminalFontSizeSp
        val savedCursor = preferences.terminalCursorStyle
        val savedBlink = preferences.terminalCursorBlink
        val savedExtraKeys = preferences.terminalShowExtraKeys
        if (fontSizeSp != savedSize) updateFontSize(savedSize)
        if (cursorStyle != savedCursor) updateCursorStyle(savedCursor)
        if (cursorBlink != savedBlink) updateCursorBlink(savedBlink)
        if (showExtraKeys != savedExtraKeys) showExtraKeys = savedExtraKeys
    }

    fun updateFontSize(newSizeSp: Int) {
        val clamped = newSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        fontSizeSp = clamped
        preferences.terminalFontSizeSp = clamped
        val px = spToPx(clamped)
        lastAppliedTextSizePx = px
        attachedViewRef?.get()?.setTextSize(px)
    }

    fun updateCursorStyle(style: Int) {
        val valid = if (style in VALID_CURSOR_STYLES) style else TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        cursorStyle = valid
        preferences.terminalCursorStyle = valid
        session?.emulator?.setCursorStyle()
        attachedViewRef?.get()?.onScreenUpdated()
    }

    fun updateCursorBlink(enabled: Boolean) {
        cursorBlink = enabled
        preferences.terminalCursorBlink = enabled
        attachedViewRef?.get()?.let { view ->
            applyCursorBlink(view, enabled)
            view.onScreenUpdated()
        }
    }

    private fun applyCursorBlink(view: TerminalView, enabled: Boolean) {
        view.setTerminalCursorBlinkerRate(if (enabled) CURSOR_BLINK_RATE_MS else 0)
        view.setTerminalCursorBlinkerState(enabled, false)
    }

    fun updateShowExtraKeys(enabled: Boolean) {
        showExtraKeys = enabled
        preferences.terminalShowExtraKeys = enabled
    }

    fun ensureStarted(ownerKey: String = "default") {
        syncPreferences()
        val current = session
        if (activeOwnerKey == ownerKey && (isStarting || (current != null && current.isRunning))) {
            return
        }
        startNewSession(ownerKey)
    }

    fun restart(ownerKey: String = activeOwnerKey ?: "default") {
        startNewSession(ownerKey)
    }

    private fun startNewSession(ownerKey: String) {
        terminate(clearPendingDraft = false)
        activeOwnerKey = ownerKey
        errorMessage = null
        isStarting = true
        hasReceivedInitialOutput = false
        launchJob = scope.launch {
            val specResult = withContext(Dispatchers.IO) {
                createLaunchSpec()
            }
            if (activeOwnerKey != ownerKey) return@launch
            val spec = specResult.getOrElse { error ->
                errorMessage = error.message ?: "Linux runtime is not ready yet."
                isStarting = false
                isRunning = false
                return@launch
            }
            val newSession = TerminalSession(
                spec.executablePath,
                spec.cwd,
                spec.args,
                spec.env,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                this@ManagedTerminalSession,
            )
            session = newSession
            isStarting = false
            isRunning = true
            attachedViewRef?.get()?.let { view ->
                if (view.mTermSession !== newSession) {
                    view.attachSession(newSession)
                }
                applyCursorBlink(view, cursorBlink)
                view.onScreenUpdated()
            }
        }
    }

    fun terminate(clearPendingDraft: Boolean = true) {
        launchJob?.cancel()
        launchJob = null
        val current = session
        session = null
        activeOwnerKey = null
        isStarting = false
        isRunning = false
        hasReceivedInitialOutput = false
        if (clearPendingDraft) pendingDraftText = null
        ctrlState = ModifierKeyState.OFF
        altState = ModifierKeyState.OFF
        if (current != null) {
            Thread({
                runCatching { current.finishIfRunning() }
            }, "mh-term-cleanup").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun attachView(view: TerminalView) {
        val isNewView = attachedViewRef?.get() !== view
        if (isNewView) {
            attachedViewRef = WeakReference(view)
            view.setTerminalViewClient(this)
        }
        val targetPx = spToPx(fontSizeSp)
        if (isNewView || lastAppliedTextSizePx != targetPx) {
            lastAppliedTextSizePx = targetPx
            view.setTextSize(targetPx)
        }
        session?.let { active ->
            if (view.mTermSession !== active) {
                view.attachSession(active)
                active.emulator?.mColors?.reset()
                applyCursorBlink(view, cursorBlink)
                view.onScreenUpdated()
            }
        }
    }

    fun detachView(view: TerminalView) {
        if (attachedViewRef?.get() === view) {
            view.setTerminalCursorBlinkerState(false, false)
            attachedViewRef = null
            lastAppliedTextSizePx = -1
        }
    }

    fun pasteTextWithoutNewline(rawText: String) {
        val clean = rawText.trim().replace("\r\n", "\n").replace('\n', ' ')
        if (clean.isEmpty()) return
        val current = session
        if (current != null && current.emulator != null && current.isRunning && hasReceivedInitialOutput) {
            current.write(clean)
        } else {
            pendingDraftText = clean
        }
    }

    fun clearScreen() {
        val current = session ?: return
        current.write("\u000C")
        attachedViewRef?.get()?.onScreenUpdated()
    }

    fun toggleCtrl(lock: Boolean = false) {
        ctrlState = when {
            lock -> if (ctrlState == ModifierKeyState.LOCKED) ModifierKeyState.OFF else ModifierKeyState.LOCKED
            ctrlState == ModifierKeyState.OFF -> ModifierKeyState.LATCHED
            else -> ModifierKeyState.OFF
        }
    }

    fun toggleAlt(lock: Boolean = false) {
        altState = when {
            lock -> if (altState == ModifierKeyState.LOCKED) ModifierKeyState.OFF else ModifierKeyState.LOCKED
            altState == ModifierKeyState.OFF -> ModifierKeyState.LATCHED
            else -> ModifierKeyState.OFF
        }
    }

    fun sendExtraKey(key: ExtraTerminalKey) {
        val current = session ?: return
        val appCursor = current.emulator?.isCursorKeysApplicationMode() == true
        val ctrl = ctrlState != ModifierKeyState.OFF
        val alt = altState != ModifierKeyState.OFF

        when (key) {
            ExtraTerminalKey.CTRL -> {
                toggleCtrl(lock = false)
                return
            }
            ExtraTerminalKey.ALT -> {
                toggleAlt(lock = false)
                return
            }
            ExtraTerminalKey.ESC -> current.write("\u001B")
            ExtraTerminalKey.TAB -> current.write("\t")
            ExtraTerminalKey.PIPE -> {
                val view = attachedViewRef?.get()
                if (view != null) {
                    view.inputCodePoint('|'.code, ctrl, alt)
                } else {
                    current.write(if (ctrl) "\u001C" else if (alt) "\u001B|" else "|")
                }
            }
            ExtraTerminalKey.SLASH -> {
                val view = attachedViewRef?.get()
                if (view != null) {
                    view.inputCodePoint('/'.code, ctrl, alt)
                } else {
                    current.write(if (ctrl) "\u001F" else if (alt) "\u001B/" else "/")
                }
            }
            ExtraTerminalKey.PGUP -> current.write("\u001B[5~")
            ExtraTerminalKey.PGDN -> current.write("\u001B[6~")
            ExtraTerminalKey.HOME -> current.write(if (appCursor) "\u001BOH" else "\u001B[H")
            ExtraTerminalKey.END -> current.write(if (appCursor) "\u001BOF" else "\u001B[F")
            ExtraTerminalKey.UP -> current.write(arrowSequence('A', appCursor, ctrl, alt))
            ExtraTerminalKey.DOWN -> current.write(arrowSequence('B', appCursor, ctrl, alt))
            ExtraTerminalKey.RIGHT -> current.write(arrowSequence('C', appCursor, ctrl, alt))
            ExtraTerminalKey.LEFT -> current.write(arrowSequence('D', appCursor, ctrl, alt))
        }
        resetLatchedModifiers()
    }

    private fun arrowSequence(dir: Char, appCursor: Boolean, ctrl: Boolean, alt: Boolean): String {
        val mod = when {
            ctrl && alt -> 7
            ctrl -> 5
            alt -> 3
            else -> 0
        }
        if (mod > 0) return "\u001B[1;$mod$dir"
        return if (appCursor) "\u001BO$dir" else "\u001B[$dir"
    }

    private fun resetLatchedModifiers() {
        if (ctrlState == ModifierKeyState.LATCHED) ctrlState = ModifierKeyState.OFF
        if (altState == ModifierKeyState.LATCHED) altState = ModifierKeyState.OFF
    }

    private fun spToPx(sp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(16)

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) {
        if (session === changedSession) {
            hasReceivedInitialOutput = true
            val pending = pendingDraftText
            if (!pending.isNullOrEmpty() && changedSession.isRunning) {
                pendingDraftText = null
                changedSession.write(pending)
            }
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (session === finishedSession) {
            isRunning = false
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal output", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val target = session ?: this.session ?: return
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) {
                target.write(text)
            }
        }
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        if (this.session === session) {
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        attachedViewRef?.get()?.onScreenUpdated()
    }

    override fun getTerminalCursorStyle(): Int = cursorStyle

    // ---- TerminalViewClient ----

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val delta = if (scale > 1f) 1 else -1
            val next = (fontSizeSp + delta).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
            if (next != fontSizeSp) {
                updateFontSize(next)
            }
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val view = attachedViewRef?.get() ?: return
        view.requestFocus()
        val window = view.context.findActivity()?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
        } else {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(view, 0)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean {
        val active = ctrlState != ModifierKeyState.OFF
        if (ctrlState == ModifierKeyState.LATCHED) {
            ctrlState = ModifierKeyState.OFF
        }
        return active
    }

    override fun readAltKey(): Boolean {
        val active = altState != ModifierKeyState.OFF
        if (altState == ModifierKeyState.LATCHED) {
            altState = ModifierKeyState.OFF
        }
        return active
    }

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        val current = session ?: return
        current.emulator?.mColors?.reset()
        attachedViewRef?.get()?.let { view ->
            applyCursorBlink(view, cursorBlink)
            view.onScreenUpdated()
        }
    }

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    companion object {
        const val TERMINAL_BG_ARGB: Int = 0xFF000000.toInt()
        const val TERMINAL_FG_ARGB: Int = 0xFF00E676.toInt()
        const val TERMINAL_CURSOR_ARGB: Int = 0xFF00E676.toInt()
        const val MIN_FONT_SIZE_SP = 9
        const val MAX_FONT_SIZE_SP = 24
        private const val CURSOR_BLINK_RATE_MS = 500

        val VALID_CURSOR_STYLES = setOf(
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
            TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE,
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR,
        )

        private val DEFAULT_PALETTE_16 = intArrayOf(
            0xFF000000.toInt(), // 0: Black
            0xFFFF5252.toInt(), // 1: Red
            0xFF00E676.toInt(), // 2: Green
            0xFFFFD740.toInt(), // 3: Yellow
            0xFF448AFF.toInt(), // 4: Blue
            0xFFE040FB.toInt(), // 5: Magenta
            0xFF18FFFF.toInt(), // 6: Cyan
            0xFFE0E0E0.toInt(), // 7: White
            0xFF616161.toInt(), // 8: Bright Black
            0xFFFF8A80.toInt(), // 9: Bright Red
            0xFF69F0AE.toInt(), // 10: Bright Green
            0xFFFFE57F.toInt(), // 11: Bright Yellow
            0xFF82B1FF.toInt(), // 12: Bright Blue
            0xFFEA80FC.toInt(), // 13: Bright Magenta
            0xFF84FFFF.toInt(), // 14: Bright Cyan
            0xFFFFFFFF.toInt(), // 15: Bright White
        )

        private var paletteInitialized = false

        fun applyTerminalColorPalette() {
            if (paletteInitialized) return
            val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
            for (i in DEFAULT_PALETTE_16.indices) {
                if (i < colors.size) colors[i] = DEFAULT_PALETTE_16[i]
            }
            if (TextStyle.COLOR_INDEX_FOREGROUND < colors.size) {
                colors[TextStyle.COLOR_INDEX_FOREGROUND] = TERMINAL_FG_ARGB
            }
            if (TextStyle.COLOR_INDEX_BACKGROUND < colors.size) {
                colors[TextStyle.COLOR_INDEX_BACKGROUND] = TERMINAL_BG_ARGB
            }
            if (TextStyle.COLOR_INDEX_CURSOR < colors.size) {
                colors[TextStyle.COLOR_INDEX_CURSOR] = TERMINAL_CURSOR_ARGB
            }
            paletteInitialized = true
        }

        private tailrec fun Context.findActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}

enum class ExtraTerminalKey(val label: String, val isModifier: Boolean = false) {
    ESC("ESC"),
    PIPE("|"),
    SLASH("/"),
    PGUP("PGUP"),
    HOME("HOME"),
    UP("↑"),
    END("END"),
    TAB("TAB"),
    CTRL("CTRL", isModifier = true),
    ALT("ALT", isModifier = true),
    PGDN("PGDN"),
    LEFT("←"),
    DOWN("↓"),
    RIGHT("→"),
}
