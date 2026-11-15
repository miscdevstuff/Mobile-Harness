package com.jarves.mh.runtime.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jarves.mh.data.AppPreferences
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.ref.WeakReference

/**
 * Lightweight, read-only Termux terminal controller backed by a real [TerminalSession]
 * running an idle `/system/bin/sleep` process so [TerminalView] renders native VT100/ANSI
 * output via [TerminalEmulator.append] with zero shell/PRoot startup overhead.
 * Shares font size and palette preferences with [ManagedTerminalSession].
 */
@Stable
class ReadOnlyTerminalSession(
    private val context: Context,
    private val preferences: AppPreferences = AppPreferences(context),
) : TerminalSessionClient, TerminalViewClient {

    var fontSizeSp by mutableIntStateOf(preferences.terminalFontSizeSp)
        private set

    private var session: TerminalSession? = null
    private var attachedViewRef: WeakReference<TerminalView>? = null
    private var lastAppliedTextSizePx: Int = -1
    private var latestText: String = ""
    private var renderedText: String = ""

    init {
        ManagedTerminalSession.applyTerminalColorPalette()
        session = TerminalSession(
            "/system/bin/sleep",
            "/",
            arrayOf("/system/bin/sleep", "86400"),
            emptyArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            this,
        )
    }

    fun syncPreferences() {
        val savedSize = preferences.terminalFontSizeSp
        if (fontSizeSp != savedSize) {
            fontSizeSp = savedSize
            val px = spToPx(savedSize)
            lastAppliedTextSizePx = px
            attachedViewRef?.get()?.setTextSize(px)
        }
    }

    /**
     * Updates the rendered terminal output from a full log string.
     * Appends only the incremental suffix when [fullText] extends the previous snapshot,
     * or resets the screen when a new operation begins.
     */
    fun setText(fullText: String) {
        syncPreferences()
        latestText = fullText
        flushToEmulator()
    }

    private fun flushToEmulator() {
        val emu = session?.emulator ?: return
        val target = latestText
        if (target == renderedText) return
        if (target.isEmpty()) {
            renderedText = ""
            val resetBytes = RESET_AND_HIDE_CURSOR.toByteArray(Charsets.UTF_8)
            emu.append(resetBytes, resetBytes.size)
            attachedViewRef?.get()?.onScreenUpdated()
            return
        }
        val payload = if (renderedText.isNotEmpty() && target.startsWith(renderedText)) {
            val delta = target.substring(renderedText.length)
            renderedText = target
            normalizeNewlines(delta)
        } else {
            renderedText = target
            RESET_AND_HIDE_CURSOR + normalizeNewlines(target)
        }
        if (payload.isNotEmpty()) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            emu.append(bytes, bytes.size)
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    private fun normalizeNewlines(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var prev = '\u0000'
        for (ch in text) {
            if (ch == '\n' && prev != '\r') {
                sb.append("\r\n")
            } else {
                sb.append(ch)
            }
            prev = ch
        }
        return sb.toString()
    }

    fun attachView(view: TerminalView) {
        syncPreferences()
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
        val active = session ?: return
        if (view.mTermSession !== active) {
            view.attachSession(active)
            active.emulator?.mColors?.reset()
            view.setTerminalCursorBlinkerRate(0)
            view.setTerminalCursorBlinkerState(false, false)
            flushToEmulator()
            view.onScreenUpdated()
        }
    }

    fun detachView(view: TerminalView) {
        if (attachedViewRef?.get() === view) {
            attachedViewRef = null
            lastAppliedTextSizePx = -1
        }
    }

    fun dispose() {
        attachedViewRef = null
        lastAppliedTextSizePx = -1
        val current = session
        session = null
        if (current != null) {
            Thread({
                runCatching { current.finishIfRunning() }
            }, "mh-ro-term-cleanup").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun spToPx(sp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(16)

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) {
        if (session === changedSession) {
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal output", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        if (this.session === session) {
            attachedViewRef?.get()?.onScreenUpdated()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int = preferences.terminalCursorStyle

    // ---- TerminalViewClient (Read-Only) ----

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val delta = if (scale > 1f) 1 else -1
            val next = (fontSizeSp + delta).coerceIn(
                ManagedTerminalSession.MIN_FONT_SIZE_SP,
                ManagedTerminalSession.MAX_FONT_SIZE_SP,
            )
            if (next != fontSizeSp) {
                fontSizeSp = next
                preferences.terminalFontSizeSp = next
                val px = spToPx(next)
                lastAppliedTextSizePx = px
                attachedViewRef?.get()?.setTextSize(px)
            }
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // Read-only terminal: do not pop up the soft keyboard on tap.
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = false

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        val current = session ?: return
        current.emulator?.mColors?.reset()
        renderedText = ""
        flushToEmulator()
        attachedViewRef?.get()?.onScreenUpdated()
    }

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    companion object {
        private const val RESET_AND_HIDE_CURSOR = "\u001Bc\u001B[?25l"
    }
}
