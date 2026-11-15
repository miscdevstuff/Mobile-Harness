package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.runtime.terminal.ManagedTerminalSession
import com.jarves.mh.runtime.terminal.ReadOnlyTerminalSession
import com.termux.view.TerminalView

private val MiniTermPureBlack = Color(0xFF000000)
private val MiniTermBarBlack = Color(0xFF070A08)
private val MiniTermGreen = Color(0xFF00E676)
private val MiniTermBorder = Color(0xFF16241B)

/**
 * Standardized, read-only Termux-backed mini terminal view used across Onboarding/Setup,
 * Host Runtime updates, Agent Runtime updates, and Development Stack installations/removals.
 * Shares font size and palette preferences with the main [TerminalScreen].
 */
@Composable
fun ReadOnlyMiniTerminal(
    output: String,
    modifier: Modifier = Modifier,
    title: String = "Terminal",
    isRunning: Boolean = true,
    height: Dp = 180.dp,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
) {
    val context = LocalContext.current
    val session = remember(context) { ReadOnlyTerminalSession(context.applicationContext) }
    var expanded by rememberSaveable(collapsible) { mutableStateOf(initiallyExpanded) }

    DisposableEffect(session) {
        onDispose { session.dispose() }
    }

    LaunchedEffect(output) {
        session.setText(output)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MiniTermPureBlack,
        border = BorderStroke(1.dp, MiniTermBorder),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiniTermBarBlack)
                    .then(
                        if (collapsible) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(MiniTermGreen.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .border(1.dp, MiniTermGreen.copy(alpha = 0.30f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MiniTermGreen,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                    color = Color(0xFFE8F5E9),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isRunning) MiniTermGreen else MiniTermGreen.copy(alpha = 0.35f),
                            CircleShape,
                        ),
                )
                Spacer(Modifier.weight(1f))
                if (collapsible) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color(0xFF9EADA3),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            AnimatedVisibility(visible = !collapsible || expanded) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(thickness = 0.5.dp, color = MiniTermBorder)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height)
                            .background(MiniTermPureBlack)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        val viewHolder = remember { arrayOfNulls<TerminalView>(1) }
                        DisposableEffect(session) {
                            onDispose {
                                viewHolder[0]?.let { session.detachView(it) }
                            }
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                TerminalView(ctx, null).apply {
                                    setBackgroundColor(ManagedTerminalSession.TERMINAL_BG_ARGB)
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    viewHolder[0] = this
                                    session.attachView(this)
                                }
                            },
                            update = { view ->
                                viewHolder[0] = view
                                session.attachView(view)
                            },
                        )
                    }
                }
            }
        }
    }
}
