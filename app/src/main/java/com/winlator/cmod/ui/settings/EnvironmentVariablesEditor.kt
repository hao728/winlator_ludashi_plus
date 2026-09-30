package com.winlator.cmod.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class EnvValueKind { CHECKBOX, SELECT, MULTI, TEXT, NUMBER }

data class EnvVariableSpec(
    val name: String,
    val kind: EnvValueKind,
    val options: List<String> = emptyList()
)

data class EditableEnvVariable(val name: String, val value: String)

val knownEnvironmentVariables = listOf(
    EnvVariableSpec("ZINK_DESCRIPTORS", EnvValueKind.SELECT, listOf("auto", "lazy", "cached", "notemplates")),
    EnvVariableSpec("ZINK_DEBUG", EnvValueKind.MULTI, listOf("nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder")),
    EnvVariableSpec("MESA_SHADER_CACHE_DISABLE", EnvValueKind.CHECKBOX, listOf("false", "true")),
    EnvVariableSpec("mesa_glthread", EnvValueKind.CHECKBOX, listOf("false", "true")),
    EnvVariableSpec("WINEESYNC", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("TU_DEBUG", EnvValueKind.MULTI, listOf("forcecb", "nocb", "deck_emu", "startup", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd")),
    EnvVariableSpec("DXVK_HUD", EnvValueKind.MULTI, listOf("scale=0.5", "scale=0.7", "opacity=0.5", "opacity=0.7", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs", "compiler", "samplers")),
    EnvVariableSpec("MESA_EXTENSION_MAX_YEAR", EnvValueKind.SELECT, listOf("2001", "2003", "2008", "2015", "2026")),
    EnvVariableSpec("VKD3D_SHADER_MODEL", EnvValueKind.SELECT, listOf("6_0", "6_1", "6_2", "6_3", "6_4", "6_5", "6_6", "6_7")),
    EnvVariableSpec("WRAPPER_BLIT", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("FD_DEV_FEATURES", EnvValueKind.MULTI, listOf("enable_tp_ubwc_flag_hint=1", "storage_8bit=1")),
    EnvVariableSpec("IR3_SHADER_DEBUG", EnvValueKind.MULTI, listOf("nouboopt", "nopreamble", "noearlypreamble", "nofp16", "nocache", "spillall", "fullsync", "fullnop", "nodescprefetch", "expandrpt", "noaliastex", "noaliasrt")),
    EnvVariableSpec("WRAPPER_MAX_IMAGE_COUNT", EnvValueKind.SELECT, listOf("0", "1", "5", "10", "20", "30", "50", "99")),
    EnvVariableSpec("MESA_GL_VERSION_OVERRIDE", EnvValueKind.SELECT, listOf("auto", "2.1", "3.1", "3.3", "3.3COMPAT", "4.6", "4.6COMPAT")),
    EnvVariableSpec("PULSE_LATENCY_MSEC", EnvValueKind.SELECT, listOf("12", "15", "20", "30", "40", "60", "80", "120")),
    EnvVariableSpec("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINE_NEW_MEDIASOURCE", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("GALLIUM_HUD", EnvValueKind.MULTI, listOf("simple", "fps", "frametime")),
    EnvVariableSpec("WINE_LARGE_ADDRESS_AWARE", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINEDLLOVERRIDES", EnvValueKind.MULTI, listOf("d3d9=n,b", "d3d11=n,b", "dxgi=n,b", "xinput1_3=n,b", "dinput8=n,b", "dsound=n,b", "mscoree=", "mshtml=")),
    EnvVariableSpec("WINE_FAST_YIELD", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("ZINK_CONTEXT_MODE", EnvValueKind.SELECT, listOf("base", "auto", "threaded")),
    EnvVariableSpec("MESA_NO_ERROR", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINEFSYNC", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("DXVK_LOG_LEVEL", EnvValueKind.SELECT, listOf("none", "error", "warn", "info", "debug")),
    EnvVariableSpec("MESA_SHADER_CACHE_MAX_SIZE", EnvValueKind.SELECT, listOf("512MB", "1024MB", "2048MB", "4096MB", "8192MB", "16384MB")),
    EnvVariableSpec("DXVK_STATE_CACHE", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINE_DISABLE_WRITE_WATCH", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("STAGING_SHARED_MEMORY", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WRAPPER_DMAHEAP_CACHED", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINEVMEMMAXSIZE", EnvValueKind.NUMBER),
    EnvVariableSpec("vblank_mode", EnvValueKind.SELECT, listOf("0", "1", "2", "3", "4")),
    EnvVariableSpec("FD_MESA_DEBUG", EnvValueKind.SELECT, listOf("hiprio", "nobatch", "nosync", "nir", "perf", ""))
)

fun parseEnvironmentVariables(raw: String): List<EditableEnvVariable> {
    if (raw.isBlank()) return emptyList()
    return raw.split(' ').mapNotNull { token ->
        val split = token.indexOf('=')
        if (split <= 0) null else EditableEnvVariable(token.substring(0, split), token.substring(split + 1))
    }
}

fun serializeEnvironmentVariables(rows: List<EditableEnvVariable>): String = rows
    .filter { it.name.isNotBlank() }
    .joinToString(" ") { "${it.name.trim().replace(" ", "")}=${it.value.trim().replace(" ", "")}" }

@Composable
fun EnvironmentVariablesEditor(
    value: String,
    onChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(value) {
        mutableStateListOf<EditableEnvVariable>().apply { addAll(parseEnvironmentVariables(value)) }
    }
    var addOpen by remember { mutableStateOf(false) }

    fun commit() = onChanged(serializeEnvironmentVariables(rows))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rows.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    "No environment variables added.",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SettingsCard {
                rows.forEachIndexed { index, row ->
                    EnvironmentVariableRow(
                        row = row,
                        onValue = { newValue ->
                            if (index in rows.indices) {
                                rows[index] = row.copy(value = newValue)
                                commit()
                            }
                        },
                        onRemove = {
                            if (index in rows.indices) {
                                rows.removeAt(index)
                                commit()
                            }
                        }
                    )
                    if (index != rows.lastIndex) SettingsDivider()
                }
            }
        }
        OutlinedButton(onClick = { addOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(7.dp))
            Text("Add variable")
        }
    }

    if (addOpen) {
        AddEnvironmentVariableDialog(
            existing = rows.map { it.name }.toSet(),
            onDismiss = { addOpen = false },
            onAdd = { name, initial ->
                rows.add(EditableEnvVariable(name, initial))
                commit()
                addOpen = false
            }
        )
    }
}

@Composable
private fun EnvironmentVariableRow(
    row: EditableEnvVariable,
    onValue: (String) -> Unit,
    onRemove: () -> Unit
) {
    val spec = knownEnvironmentVariables.firstOrNull { it.name == row.name }
        ?: EnvVariableSpec(row.name, EnvValueKind.TEXT)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, "Remove") }
        }
        when (spec.kind) {
            EnvValueKind.CHECKBOX -> {
                val off = spec.options.getOrElse(0) { "0" }
                val on = spec.options.getOrElse(1) { "1" }
                SettingToggle(
                    label = if (row.value == on) on else off,
                    checked = row.value == on,
                    onChanged = { onValue(if (it) on else off) }
                )
            }
            EnvValueKind.SELECT -> SettingChoice(
                label = "Value",
                selected = row.value.ifBlank { spec.options.firstOrNull().orEmpty() },
                entries = spec.options
            ) { onValue(it) }
            EnvValueKind.MULTI -> MultiEnvironmentChoice(spec.options, row.value, onValue)
            EnvValueKind.NUMBER -> SettingText("Value", row.value) { onValue(it.filter(Char::isDigit)) }
            EnvValueKind.TEXT -> SettingText("Value", row.value, onChanged = onValue)
        }
    }
}

@Composable
private fun MultiEnvironmentChoice(options: List<String>, value: String, onChanged: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selectedText = value.ifBlank { "None" }
    Surface(onClick = { open = true }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text("Value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selectedText, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (open) {
        val selected = remember(value, open) {
            mutableStateListOf<String>().apply {
                addAll(value.split(',').map(String::trim).filter(String::isNotEmpty))
            }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChanged(selected.joinToString(","))
                    open = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            title = { Text("Select values") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { option ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = option in selected,
                                onCheckedChange = { enabled ->
                                    if (enabled) { if (option !in selected) selected.add(option) }
                                    else selected.remove(option)
                                }
                            )
                            Text(option, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun AddEnvironmentVariableDialog(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    val available = knownEnvironmentVariables.map { it.name }.filter { it !in existing }
    var name by remember { mutableStateOf(available.firstOrNull().orEmpty()) }
    var customName by remember { mutableStateOf("") }
    val options = available + "Custom…"
    val selected = if (name in available) name else "Custom…"

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val finalName = if (selected == "Custom…") customName.trim().replace(" ", "") else name
                if (finalName.isNotBlank() && finalName !in existing) {
                    val spec = knownEnvironmentVariables.firstOrNull { it.name == finalName }
                    val initial = when (spec?.kind) {
                        EnvValueKind.CHECKBOX -> spec.options.firstOrNull().orEmpty()
                        EnvValueKind.SELECT -> spec.options.firstOrNull().orEmpty()
                        else -> ""
                    }
                    onAdd(finalName, initial)
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add environment variable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChoice("Variable", selected, options) { picked ->
                    name = if (picked == "Custom…") "" else picked
                }
                if (selected == "Custom…") {
                    SettingText("Name", customName) { customName = it }
                }
            }
        }
    )
}
