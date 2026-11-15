package com.jarves.mh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.R
import com.jarves.mh.runtime.terminal.ExtraTerminalKey
import com.jarves.mh.runtime.terminal.ManagedTerminalSession
import com.jarves.mh.runtime.terminal.ModifierKeyState
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView

private val TerminalPureBlack = Color(0xFF000000)
private val TerminalBarBlack = Color(0xFF070A08)
private val TerminalSurfaceDark = Color(0xFF0D130F)
private val TerminalGreen = Color(0xFF00E676)
private val TerminalMutedGreen = Color(0xFF69F0AE)
private val TerminalBorder = Color(0xFF16241B)

@Composable
fun TerminalScreen(
    terminalSession: ManagedTerminalSession,
    compactHeader: Boolean = false,
    applyImePadding: Boolean = true,
) {
    val activeSession = terminalSession.session
    val isRunning = terminalSession.isRunning
    val error = terminalSession.errorMessage
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        TerminalSettingsDialog(
            terminalSession = terminalSession,
            onDismiss = { showSettings = false },
        )
    }

    Scaffold(
        modifier = if (compactHeader) Modifier else Modifier.statusBarsPadding(),
        containerColor = TerminalPureBlack,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.background(TerminalBarBlack)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(TerminalGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .border(1.dp, TerminalGreen.copy(alpha = 0.30f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TerminalGreen,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.ws_terminal_linux_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFFE8F5E9),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (isRunning || terminalSession.isStarting) TerminalGreen else Color(0xFFFF5252),
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = TerminalMutedGreen.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = TerminalBorder)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalPureBlack)
                .padding(padding)
                .consumeWindowInsets(padding)
                .then(if (applyImePadding) Modifier.imePadding() else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalPureBlack)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                if (error != null && activeSession == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = error,
                            color = Color(0xFFFF8A80),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { terminalSession.restart() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TerminalGreen,
                                contentColor = TerminalPureBlack,
                            ),
                        ) {
                            Text(stringResource(R.string.ws_try_again), fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    val viewHolder = remember { arrayOfNulls<TerminalView>(1) }
                    DisposableEffect(terminalSession) {
                        onDispose {
                            viewHolder[0]?.let { terminalSession.detachView(it) }
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setBackgroundColor(ManagedTerminalSession.TERMINAL_BG_ARGB)
                                isFocusable = true
                                isFocusableInTouchMode = true
                                keepScreenOn = true
                                viewHolder[0] = this
                                terminalSession.attachView(this)
                                requestFocus()
                            }
                        },
                        update = { view ->
                            viewHolder[0] = view
                            terminalSession.attachView(view)
                        },
                    )
                }
            }

            if (terminalSession.showExtraKeys) {
                TermuxExtraKeysBar(
                    ctrlState = terminalSession.ctrlState,
                    altState = terminalSession.altState,
                    onKeyPress = { key -> terminalSession.sendExtraKey(key) },
                    onModifierLongPress = { key ->
                        when (key) {
                            ExtraTerminalKey.CTRL -> terminalSession.toggleCtrl(lock = true)
                            ExtraTerminalKey.ALT -> terminalSession.toggleAlt(lock = true)
                            else -> Unit
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TerminalSettingsDialog(
    terminalSession: ManagedTerminalSession,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalSurfaceDark,
        titleContentColor = Color(0xFFE8F5E9),
        textContentColor = Color(0xFFE8F5E9),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = TerminalGreen,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Terminal settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Font size", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text(
                            "Pinch-to-zoom also adjusts size",
                            fontSize = 11.sp,
                            color = Color(0xFF9EADA3),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = TerminalPureBlack,
                        border = BorderStroke(1.dp, TerminalBorder),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            IconButton(
                                onClick = { terminalSession.updateFontSize(terminalSession.fontSizeSp - 1) },
                                enabled = terminalSession.fontSizeSp > ManagedTerminalSession.MIN_FONT_SIZE_SP,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease font size", tint = TerminalGreen, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "${terminalSession.fontSizeSp} sp",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = Color(0xFFE8F5E9),
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(
                                onClick = { terminalSession.updateFontSize(terminalSession.fontSizeSp + 1) },
                                enabled = terminalSession.fontSizeSp < ManagedTerminalSession.MAX_FONT_SIZE_SP,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase font size", tint = TerminalGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                HorizontalDivider(color = TerminalBorder)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cursor style", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val styles = listOf(
                            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK to "Block ▊",
                            TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE to "Underline _",
                            TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR to "Bar |",
                        )
                        styles.forEach { (styleCode, label) ->
                            val selected = terminalSession.cursorStyle == styleCode
                            FilterChip(
                                selected = selected,
                                onClick = { terminalSession.updateCursorStyle(styleCode) },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TerminalGreen.copy(alpha = 0.20f),
                                    selectedLabelColor = TerminalGreen,
                                    containerColor = TerminalPureBlack,
                                    labelColor = Color(0xFFE8F5E9),
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = TerminalBorder,
                                    selectedBorderColor = TerminalGreen,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { terminalSession.updateCursorBlink(terminalSession.cursorBlink != true) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Blinking cursor", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text("Blink cursor when terminal is focused", fontSize = 11.sp, color = Color(0xFF9EADA3))
                    }
                    Switch(
                        checked = terminalSession.cursorBlink,
                        onCheckedChange = { terminalSession.updateCursorBlink(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TerminalPureBlack,
                            checkedTrackColor = TerminalGreen,
                        ),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { terminalSession.updateShowExtraKeys(terminalSession.showExtraKeys != true) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Extra keys bar", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text("Show ESC, TAB, CTRL, ALT, and arrow keys", fontSize = 11.sp, color = Color(0xFF9EADA3))
                    }
                    Switch(
                        checked = terminalSession.showExtraKeys,
                        onCheckedChange = { terminalSession.updateShowExtraKeys(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TerminalPureBlack,
                            checkedTrackColor = TerminalGreen,
                        ),
                    )
                }

                HorizontalDivider(color = TerminalBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            terminalSession.clearScreen()
                            onDismiss()
                        },
                        enabled = terminalSession.isRunning,
                        border = BorderStroke(1.dp, TerminalBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = TerminalMutedGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear", color = Color(0xFFE8F5E9), fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            terminalSession.restart()
                            onDismiss()
                        },
                        border = BorderStroke(1.dp, TerminalGreen.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset session", color = TerminalGreen, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = TerminalGreen, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun TermuxExtraKeysBar(
    ctrlState: ModifierKeyState,
    altState: ModifierKeyState,
    onKeyPress: (ExtraTerminalKey) -> Unit,
    onModifierLongPress: (ExtraTerminalKey) -> Unit,
) {
    val row1 = remember {
        listOf(
            ExtraTerminalKey.ESC,
            ExtraTerminalKey.PIPE,
            ExtraTerminalKey.SLASH,
            ExtraTerminalKey.PGUP,
            ExtraTerminalKey.HOME,
            ExtraTerminalKey.UP,
            ExtraTerminalKey.END,
        )
    }
    val row2 = remember {
        listOf(
            ExtraTerminalKey.TAB,
            ExtraTerminalKey.CTRL,
            ExtraTerminalKey.ALT,
            ExtraTerminalKey.PGDN,
            ExtraTerminalKey.LEFT,
            ExtraTerminalKey.DOWN,
            ExtraTerminalKey.RIGHT,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalPureBlack)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = TerminalBorder)
        listOf(row1, row2).forEach { keys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                keys.forEach { key ->
                    val modState = when (key) {
                        ExtraTerminalKey.CTRL -> ctrlState
                        ExtraTerminalKey.ALT -> altState
                        else -> ModifierKeyState.OFF
                    }
                    ExtraKeyCell(
                        key = key,
                        modifierState = modState,
                        modifier = Modifier.weight(1f),
                        onClick = { onKeyPress(key) },
                        onLongClick = if (key.isModifier) ({ onModifierLongPress(key) }) else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtraKeyCell(
    key: ExtraTerminalKey,
    modifierState: ModifierKeyState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val active = modifierState != ModifierKeyState.OFF
    val locked = modifierState == ModifierKeyState.LOCKED
    val textColor = when {
        locked -> TerminalPureBlack
        active -> TerminalGreen
        else -> Color(0xFFE8F5E9)
    }
    val bgColor = when {
        locked -> TerminalGreen
        active -> TerminalGreen.copy(alpha = 0.18f)
        else -> TerminalPureBlack
    }

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
