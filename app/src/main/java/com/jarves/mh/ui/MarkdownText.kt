package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.ui.theme.PocketOrange
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface MarkdownSegment {
    data class Prose(val content: String) : MarkdownSegment
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
) {
    val segments = remember(markdown) { splitCodeFences(markdown) }
    val resolvedTextColor = color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        lineHeight = 22.sp,
        color = resolvedTextColor,
    )
    val colors = markdownColor(
        text = resolvedTextColor,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        h2 = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        h3 = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        text = bodyStyle,
        paragraph = bodyStyle,
        bullet = bodyStyle,
        ordered = bodyStyle,
        list = bodyStyle,
        inlineCode = bodyStyle.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = PocketOrange,
        ),
        quote = bodyStyle.copy(
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Prose -> Markdown(
                    content = segment.content,
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.fillMaxWidth(),
                )
                is MarkdownSegment.CodeBlock -> CodeSnippetBlock(segment, onRunCode)
            }
        }
    }
}

@Composable
private fun CodeSnippetBlock(block: MarkdownSegment.CodeBlock, onRunCode: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF14171E),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2A2E39), RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C202B))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { stringResource(R.string.ws_md_code_label) },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9AA0A6),
                    fontFamily = FontFamily.Monospace,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shellLanguage = block.language.lowercase() in setOf("", "bash", "sh", "shell", "zsh", "console", "terminal")
                    if (onRunCode != null && shellLanguage) {
                        IconButton(
                            onClick = { onRunCode(block.code.trim()) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.ws_md_run_in_terminal),
                                tint = PocketOrange,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                            scope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.ws_md_copy_code),
                            tint = if (copied) PocketOrange else Color(0xFF9AA0A6),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFFE2E8F0),
                )
            }
        }
    }
}

private fun splitCodeFences(raw: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    var inCodeBlock = false
    var codeLang = ""
    val codeLines = mutableListOf<String>()
    val proseLines = mutableListOf<String>()

    fun flushProse() {
        val text = proseLines.joinToString("\n").trim()
        if (text.isNotEmpty()) segments += MarkdownSegment.Prose(text)
        proseLines.clear()
    }

    for (line in raw.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                segments += MarkdownSegment.CodeBlock(codeLang, codeLines.joinToString("\n"))
                codeLines.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                flushProse()
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim()
            }
        } else if (inCodeBlock) {
            codeLines += line
        } else {
            proseLines += line
        }
    }
    if (inCodeBlock) {
        segments += MarkdownSegment.CodeBlock(codeLang, codeLines.joinToString("\n"))
    }
    flushProse()
    return segments
}
