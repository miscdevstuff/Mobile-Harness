package com.jarves.mh.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.AgentSkill
import com.jarves.mh.model.McpLocalityMode
import com.jarves.mh.model.McpServerConfig
import com.jarves.mh.model.McpSettings
import com.jarves.mh.model.McpTransportType
import com.jarves.mh.model.SkillActivationMode
import com.jarves.mh.model.SkillSettings
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagerSheet(
    settings: SkillSettings,
    skills: List<AgentSkill>,
    onDismiss: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
    onUpdateSettings: ((SkillSettings) -> SkillSettings) -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onSaveSkill: (AgentSkill) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onResetBuiltInSkill: (String) -> Unit,
    onImportSkillUri: (Uri) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var boostersExpanded by rememberSaveable { mutableStateOf(false) }
    var editingSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingNewSkill by rememberSaveable { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) onImportSkillUri(uri) },
    )

    val activeCount = if (settings.masterEnabled) skills.count { it.enabled } else 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .imePadding()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sheet Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(PocketOrange.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Agent Skills",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Context rules & guardrails",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "/pocket-bridge/opencode/skills",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        active = settings.masterEnabled && activeCount > 0,
                        label = if (!settings.masterEnabled) "OFF" else "$activeCount ACTIVE",
                    )
                }
            }

            // Master Toggle Card
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMaster(!settings.masterEnabled) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable Agent Skills", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                if (settings.masterEnabled) "Enabled skills are injected into prompts and staged for OpenCode"
                                else "Disabled by default — no skills are injected until turned on",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.masterEnabled,
                            onCheckedChange = onToggleMaster,
                        )
                    }
                }
            }

            // Free-Model Booster & Power Settings Accordion
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { boostersExpanded = !boostersExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Free-Model Booster & Injection Settings", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                Text(
                                    "Preload mode, edit guardrails & budget (${settings.maxSkillContextChars / 1000}K chars)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                if (boostersExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AnimatedVisibility(boostersExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                BoosterToggleRow(
                                    title = "Force Preload in Prompt",
                                    subtitle = "Injects active skill rules directly into <active_skills> so free models follow them on Turn 1 without needing to call the skill tool.",
                                    checked = settings.forcePreloadInPrompt,
                                    onCheckedChange = { v -> onUpdateSettings { it.copy(forcePreloadInPrompt = v) } },
                                )
                                BoosterToggleRow(
                                    title = "Stage Native OpenCode Skills",
                                    subtitle = "Stages SKILL.md files in /pocket-bridge/opencode/skills via OPENCODE_CONFIG_DIR.",
                                    checked = settings.stageNativeOpencodeSkills,
                                    onCheckedChange = { v -> onUpdateSettings { it.copy(stageNativeOpencodeSkills = v) } },
                                )
                                BoosterToggleRow(
                                    title = "Read-Before-Edit & Verify Guardrail",
                                    subtitle = "Requires reading target files before editing and forbids placeholder comments.",
                                    checked = settings.readBeforeEditGuardrail,
                                    onCheckedChange = { v -> onUpdateSettings { it.copy(readBeforeEditGuardrail = v) } },
                                )
                                BoosterToggleRow(
                                    title = "Code-First Concise Output",
                                    subtitle = "Saves output tokens on free models by prioritizing tool execution and code over long explanations.",
                                    checked = settings.conciseCodeFirstOutput,
                                    onCheckedChange = { v -> onUpdateSettings { it.copy(conciseCodeFirstOutput = v) } },
                                )
                                Text(
                                    "Max Skill Context Budget",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(4_000 to "4K chars", 8_000 to "8K chars", 12_000 to "12K (Default)", 20_000 to "20K chars").forEach { (budget, label) ->
                                        FilterChip(
                                            selected = settings.maxSkillContextChars == budget,
                                            onClick = { onUpdateSettings { it.copy(maxSkillContextChars = budget) } },
                                            label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Actions Row (+ New Skill / Import .md) — matched 42.dp height & single-line labels
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            creatingNewSkill = true
                            editingSkillId = null
                        },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Skill", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("text/*", "*/*")) },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import .md", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            }

            if (creatingNewSkill) {
                item {
                    SkillEditorCard(
                        initial = AgentSkill(
                            id = "",
                            name = "",
                            description = "",
                            enabled = true,
                            builtIn = false,
                            activationMode = SkillActivationMode.ALWAYS_WHEN_ENABLED,
                            filePatterns = emptyList(),
                            triggerKeywords = emptyList(),
                            content = "# Custom Skill Rules\n\n- Add instructions for the coding agent here.",
                        ),
                        isNew = true,
                        onSave = { created ->
                            onSaveSkill(created)
                            creatingNewSkill = false
                        },
                        onCancel = { creatingNewSkill = false },
                        onDelete = null,
                        onReset = null,
                    )
                }
            }

            items(skills, key = { it.id }) { skill ->
                val isEditing = editingSkillId == skill.id
                if (isEditing) {
                    SkillEditorCard(
                        initial = skill,
                        isNew = false,
                        onSave = { updated ->
                            onSaveSkill(updated)
                            editingSkillId = null
                        },
                        onCancel = { editingSkillId = null },
                        onDelete = if (!skill.builtIn) ({
                            onDeleteSkill(skill.id)
                            editingSkillId = null
                        }) else null,
                        onReset = if (skill.builtIn) ({
                            onResetBuiltInSkill(skill.id)
                            editingSkillId = null
                        }) else null,
                    )
                } else {
                    SkillSummaryCard(
                        skill = skill,
                        masterEnabled = settings.masterEnabled,
                        onToggle = { enabled -> onToggleSkill(skill.id, enabled) },
                        onEdit = {
                            creatingNewSkill = false
                            editingSkillId = skill.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillSummaryCard(
    skill: AgentSkill,
    masterEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val active = masterEnabled && skill.enabled
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) PocketOrange.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Row 1: Title + Edit Icon + Switch (no badge competing for width)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skill.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Configure skill", modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(2.dp))
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                )
            }

            // Row 2: Non-wrapping Badge + Monospace ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniBadge(
                    text = if (skill.builtIn) "BUILT-IN" else "CUSTOM",
                    color = if (skill.builtIn) PocketOrange else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = skill.id,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // Row 3: Description
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Row 4: Mode & File Triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val modeLabel = when (skill.activationMode) {
                    SkillActivationMode.ALWAYS_WHEN_ENABLED -> "Mode: Always Preload"
                    SkillActivationMode.AUTO_DETECT -> "Mode: Auto-Detect"
                    SkillActivationMode.NATIVE_ONLY -> "Mode: Native Only"
                }
                Text(
                    text = modeLabel,
                    fontSize = 10.5.sp,
                    color = PocketOrange,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
                if (skill.filePatterns.isNotEmpty()) {
                    Text(
                        text = "· Files: ${skill.filePatterns.take(3).joinToString(", ")}",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillEditorCard(
    initial: AgentSkill,
    isNew: Boolean,
    onSave: (AgentSkill) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onReset: (() -> Unit)?,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var id by rememberSaveable(initial.id) { mutableStateOf(initial.id) }
    var description by rememberSaveable(initial.id) { mutableStateOf(initial.description) }
    var mode by rememberSaveable(initial.id) { mutableStateOf(initial.activationMode) }
    var filePatternsText by rememberSaveable(initial.id) { mutableStateOf(initial.filePatterns.joinToString(", ")) }
    var keywordsText by rememberSaveable(initial.id) { mutableStateOf(initial.triggerKeywords.joinToString(", ")) }
    var content by rememberSaveable(initial.id) { mutableStateOf(initial.content) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) "Create Custom Skill" else "Configure Skill: ${initial.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Skill Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isNew) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Skill ID / Slug (optional)") },
                    placeholder = { Text("e.g. godot-4-shaders") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Short Description") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Activation & Injection Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = mode == SkillActivationMode.ALWAYS_WHEN_ENABLED,
                    onClick = { mode = SkillActivationMode.ALWAYS_WHEN_ENABLED },
                    label = { Text("Always Preload (Best for Free Models)", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = mode == SkillActivationMode.AUTO_DETECT,
                    onClick = { mode = SkillActivationMode.AUTO_DETECT },
                    label = { Text("Auto-Detect by Files/Keywords", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = mode == SkillActivationMode.NATIVE_ONLY,
                    onClick = { mode = SkillActivationMode.NATIVE_ONLY },
                    label = { Text("OpenCode Native Only", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                )
            }
            OutlinedTextField(
                value = filePatternsText,
                onValueChange = { filePatternsText = it },
                label = { Text("Workspace File Triggers (comma-separated)") },
                placeholder = { Text("project.godot, *.gd, *.tscn") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keywordsText,
                onValueChange = { keywordsText = it },
                label = { Text("Prompt Keyword Triggers (comma-separated)") },
                placeholder = { Text("godot, gdscript, shader, compose") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Skill Instructions (Markdown)") },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                ),
                minLines = 6,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onReset != null) {
                    TextButton(onClick = onReset) {
                        Text("Reset Default", fontSize = 12.sp)
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Button(
                    enabled = name.isNotBlank() && content.isNotBlank(),
                    onClick = {
                        onSave(
                            initial.copy(
                                id = id.trim().ifBlank { name.trim() },
                                name = name.trim(),
                                description = description.trim(),
                                activationMode = mode,
                                filePatterns = filePatternsText.split(',').map(String::trim).filter(String::isNotBlank),
                                triggerKeywords = keywordsText.split(',').map(String::trim).filter(String::isNotBlank),
                                content = content.trim(),
                            ),
                        )
                    },
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Save")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersSheet(
    settings: McpSettings,
    servers: List<McpServerConfig>,
    testingServerIds: Set<String>,
    testStatuses: Map<String, String>,
    godotStackInstalled: Boolean,
    onDismiss: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
    onUpdateSettings: ((McpSettings) -> McpSettings) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    onSaveServer: (McpServerConfig) -> Unit,
    onDeleteServer: (String) -> Unit,
    onResetBuiltInServer: (String) -> Unit,
    onTestServer: (McpServerConfig) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingNewServer by rememberSaveable { mutableStateOf(false) }

    val enabledCount = if (settings.masterEnabled) servers.count { it.enabled } else 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .imePadding()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sheet Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(PocketOrange.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "MCP Servers",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Local PRoot stdio & remote HTTP tools",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "/pocket-bridge/opencode/opencode.json",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        active = settings.masterEnabled && enabledCount > 0,
                        label = if (!settings.masterEnabled) "OFF" else "$enabledCount ENABLED",
                    )
                }
            }

            // Master Switch & Locality Prompt Toggle
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleMaster(!settings.masterEnabled) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Enable MCP Servers", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    if (settings.masterEnabled) "Active servers are passed via OPENCODE_CONFIG without modifying project files"
                                    else "Disabled by default — turn on to connect Local or Remote MCP servers",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = settings.masterEnabled,
                                onCheckedChange = onToggleMaster,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        BoosterToggleRow(
                            title = "AI Locality Awareness (<mcp_environment>)",
                            subtitle = "Informs the AI whether each MCP server is running locally inside the PRoot container (with local binaries in PATH) or on a remote URL.",
                            checked = settings.injectLocalityPrompt,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(injectLocalityPrompt = v) } },
                        )
                    }
                }
            }

            // + Add Custom MCP Server Button
            item {
                OutlinedButton(
                    onClick = {
                        creatingNewServer = true
                        editingServerId = null
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Custom MCP Server", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }

            if (creatingNewServer) {
                item {
                    McpServerEditorCard(
                        initial = McpServerConfig(
                            id = "",
                            name = "",
                            description = "Custom MCP server",
                            enabled = true,
                            builtInPreset = false,
                            transport = McpTransportType.LOCAL_STDIO,
                            localityMode = McpLocalityMode.AUTO_BY_TRANSPORT,
                            url = "http://127.0.0.1:8080/mcp",
                            disableOAuth = true,
                            directToolExposure = true,
                            timeoutMs = 30_000,
                        ),
                        isNew = true,
                        godotStackInstalled = godotStackInstalled,
                        isTesting = false,
                        testStatus = null,
                        onTest = onTestServer,
                        onSave = { created ->
                            onSaveServer(created)
                            creatingNewServer = false
                        },
                        onCancel = { creatingNewServer = false },
                        onDelete = null,
                        onReset = null,
                    )
                }
            }

            items(servers, key = { it.id }) { server ->
                val isEditing = editingServerId == server.id
                if (isEditing) {
                    McpServerEditorCard(
                        initial = server,
                        isNew = false,
                        godotStackInstalled = godotStackInstalled,
                        isTesting = server.id in testingServerIds,
                        testStatus = testStatuses[server.id],
                        onTest = onTestServer,
                        onSave = { updated ->
                            onSaveServer(updated)
                            editingServerId = null
                        },
                        onCancel = { editingServerId = null },
                        onDelete = if (!server.builtInPreset) ({
                            onDeleteServer(server.id)
                            editingServerId = null
                        }) else null,
                        onReset = if (server.builtInPreset) ({
                            onResetBuiltInServer(server.id)
                            editingServerId = null
                        }) else null,
                    )
                } else {
                    McpServerSummaryCard(
                        server = server,
                        masterEnabled = settings.masterEnabled,
                        isTesting = server.id in testingServerIds,
                        testStatus = testStatuses[server.id],
                        onToggle = { enabled -> onToggleServer(server.id, enabled) },
                        onTest = { onTestServer(server) },
                        onEdit = {
                            creatingNewServer = false
                            editingServerId = server.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerSummaryCard(
    server: McpServerConfig,
    masterEnabled: Boolean,
    isTesting: Boolean,
    testStatus: String?,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
) {
    val active = masterEnabled && server.enabled
    val isRemote = server.isEffectivelyRemote
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) PocketOrange.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Row 1: Title + Test Icon + Edit Icon + Switch (no badge competing for width)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = server.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onTest, enabled = !isTesting, modifier = Modifier.size(32.dp)) {
                    if (isTesting) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Test MCP server", modifier = Modifier.size(17.dp))
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Configure MCP server", modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(2.dp))
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                )
            }

            // Row 2: Non-wrapping Locality Badge + Monospace ID / Target
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniBadge(
                    text = if (isRemote) "REMOTE · TOOLS ONLY" else "LOCAL · IN CONTAINER",
                    color = if (isRemote) PocketOrange else PocketGreen,
                )
                Text(
                    text = if (server.transport == McpTransportType.REMOTE) "${server.id} · ${server.url}"
                    else "${server.id} · ${server.command.joinToString(" ")}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // Row 3: Description
            if (server.description.isNotBlank()) {
                Text(
                    text = server.description,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Row 4: Live Test Status
            if (!testStatus.isNullOrBlank()) {
                val ok = testStatus.startsWith("✓")
                Text(
                    text = testStatus,
                    fontSize = 11.sp,
                    color = if (ok) PocketGreen else if (testStatus.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun McpServerEditorCard(
    initial: McpServerConfig,
    isNew: Boolean,
    godotStackInstalled: Boolean,
    isTesting: Boolean,
    testStatus: String?,
    onTest: (McpServerConfig) -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onReset: (() -> Unit)?,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var id by rememberSaveable(initial.id) { mutableStateOf(initial.id) }
    var description by rememberSaveable(initial.id) { mutableStateOf(initial.description) }
    var transport by rememberSaveable(initial.id) { mutableStateOf(initial.transport) }
    var localityMode by rememberSaveable(initial.id) { mutableStateOf(initial.localityMode) }
    var url by rememberSaveable(initial.id) { mutableStateOf(initial.url) }
    var headersText by rememberSaveable(initial.id) {
        mutableStateOf(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var disableOAuth by rememberSaveable(initial.id) { mutableStateOf(initial.disableOAuth) }
    var commandText by rememberSaveable(initial.id) {
        mutableStateOf(initial.command.joinToString(" "))
    }
    var envText by rememberSaveable(initial.id) {
        mutableStateOf(initial.environment.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var directToolExposure by rememberSaveable(initial.id) { mutableStateOf(initial.directToolExposure) }
    var timeoutText by rememberSaveable(initial.id) { mutableStateOf(initial.timeoutMs.toString()) }
    var usageHint by rememberSaveable(initial.id) { mutableStateOf(initial.usageHint) }

    fun buildCurrentConfig(): McpServerConfig {
        val parsedHeaders = headersText.lineSequence()
            .map(String::trim)
            .filter { it.contains(':') }
            .associate { line ->
                val idx = line.indexOf(':')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
        val parsedEnv = envText.lineSequence()
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
        val parsedCmd = commandText.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return initial.copy(
            id = id.trim().ifBlank { name.trim() },
            name = name.trim(),
            description = description.trim(),
            transport = transport,
            localityMode = localityMode,
            url = url.trim(),
            headers = parsedHeaders,
            disableOAuth = disableOAuth,
            command = parsedCmd,
            environment = parsedEnv,
            directToolExposure = directToolExposure,
            timeoutMs = timeoutText.toIntOrNull()?.coerceIn(1_000, 600_000) ?: 30_000,
            usageHint = usageHint.trim(),
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) "Add Custom MCP Server" else "Configure MCP: ${initial.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Server Display Name") },
                placeholder = { Text("e.g. Godot 4 MCP / Context7 / Custom API") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isNew) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Server ID / Key in opencode.json") },
                    placeholder = { Text("e.g. godot-mcp or custom-mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Transport & Execution Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = transport == McpTransportType.LOCAL_STDIO,
                    onClick = {
                        transport = McpTransportType.LOCAL_STDIO
                        if (localityMode == McpLocalityMode.REMOTE_TOOLS_ONLY) {
                            localityMode = McpLocalityMode.LOCAL_IN_CONTAINER
                        }
                    },
                    label = { Text("Local PRoot (Stdio)", fontSize = 11.5.sp, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = transport == McpTransportType.REMOTE,
                    onClick = {
                        transport = McpTransportType.REMOTE
                        if (localityMode == McpLocalityMode.LOCAL_IN_CONTAINER) {
                            localityMode = McpLocalityMode.REMOTE_TOOLS_ONLY
                        }
                    },
                    label = { Text("Remote URL (HTTP / SSE)", fontSize = 11.5.sp, maxLines = 1, softWrap = false) },
                )
            }

            if (transport == McpTransportType.LOCAL_STDIO) {
                if (initial.id == "godot-mcp" && !godotStackInstalled) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Local Godot 4.7 toolchain is not installed in PRoot yet. Install 'Godot 4 (GDScript)' in Settings → Developer tools to use local Godot MCP.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    label = { Text("Local Container Command & Args") },
                    placeholder = { Text("/usr/local/bin/node /usr/local/lib/godot-mcp/build/index.js") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = envText,
                    onValueChange = { envText = it },
                    label = { Text("Environment Variables (KEY=VALUE per line)") },
                    placeholder = { Text("GODOT_PATH=/usr/local/bin/godot") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Remote MCP Endpoint URL (http:// or https://)") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("Custom HTTP Headers (optional, Key: Value per line)") },
                    placeholder = { Text("Authorization: Bearer sk-...\nX-Custom-Header: value") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                BoosterToggleRow(
                    title = "Disable OAuth Handshake (oauth: false)",
                    subtitle = "Recommended for self-hosted and header-authenticated MCP servers.",
                    checked = disableOAuth,
                    onCheckedChange = { disableOAuth = it },
                )
            }

            Text("AI Locality Identification", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = localityMode == McpLocalityMode.AUTO_BY_TRANSPORT,
                    onClick = { localityMode = McpLocalityMode.AUTO_BY_TRANSPORT },
                    label = {
                        Text(
                            "Auto (${if (transport == McpTransportType.REMOTE) "Remote Tools Only" else "Local in Container"})",
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
                FilterChip(
                    selected = localityMode == McpLocalityMode.LOCAL_IN_CONTAINER,
                    onClick = { localityMode = McpLocalityMode.LOCAL_IN_CONTAINER },
                    label = { Text("Force Local (Binary in PATH)", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = localityMode == McpLocalityMode.REMOTE_TOOLS_ONLY,
                    onClick = { localityMode = McpLocalityMode.REMOTE_TOOLS_ONLY },
                    label = { Text("Force Remote (No Local Binary)", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                )
            }

            BoosterToggleRow(
                title = "Direct Tool Exposure for Free Models",
                subtitle = "Instructs the agent to invoke this server's MCP tool names directly with JSON arguments.",
                checked = directToolExposure,
                onCheckedChange = { directToolExposure = it },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit) },
                    label = { Text("Timeout (ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(130.dp),
                )
                OutlinedTextField(
                    value = usageHint,
                    onValueChange = { usageHint = it },
                    label = { Text("AI Usage Hint (optional)") },
                    placeholder = { Text("When to prefer these MCP tools") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!testStatus.isNullOrBlank()) {
                val ok = testStatus.startsWith("✓")
                Text(
                    testStatus,
                    fontSize = 11.5.sp,
                    color = if (ok) PocketGreen else if (testStatus.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onTest(buildCurrentConfig()) },
                    enabled = !isTesting,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Test", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                if (onReset != null) {
                    TextButton(onClick = onReset) { Text("Reset", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = name.isNotBlank() && (transport == McpTransportType.REMOTE && url.isNotBlank() || transport == McpTransportType.LOCAL_STDIO && commandText.isNotBlank()),
                    onClick = { onSave(buildCurrentConfig()) },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Save", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun BoosterToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusPill(active: Boolean, label: String) {
    val color = if (active) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun MiniBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
