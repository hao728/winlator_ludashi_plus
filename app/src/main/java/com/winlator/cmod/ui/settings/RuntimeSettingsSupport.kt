package com.winlator.cmod.ui.settings

import com.winlator.cmod.core.DXWrapper

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.contents.RemoteDriverCatalog
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRuntimeGuard
import com.winlator.cmod.core.WineThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class VersionCatalog(val all: List<String>, val installed: Set<String>)
internal data class DriverOption(
    val id: String,
    val label: String,
    val installed: Boolean,
    val remoteUrl: String? = null,
    val repository: String = "",
    val publishedAt: Long = 0,
    val tag: String = ""
)

internal fun graphicsDriverLabel(entries: List<String>, id: String): String {
    return entries.firstOrNull { StringUtils.parseIdentifier(it).equals(id, true) } ?: id
}

/**
 * 缩短过长的驱动asset文件名，保留版本号关键信息。
 * 例如 "Turnip-v26.3.0-20260930-r5-710-720" → "Turnip v26.3.0 r5-710-720"
 */
internal fun shortenDriverName(name: String): String {
    if (name.length <= 28) return name
    // 提取版本号模式：v数字.数字.数字 或 数字.数字.数字
    val versionMatch = Regex("""v?\d+\.\d+\.\d+(-\d+)?""").find(name)
    val version = versionMatch?.value ?: ""
    // 提取r数字 或 末尾标识
    val suffixMatch = Regex("""r\d+(-\d+)*(-\d+)?$""").find(name)
    val suffix = suffixMatch?.value ?: ""
    // 提取前缀（Turnip/Mesa等）
    val prefix = name.substringBefore("-").take(12)
    return when {
        version.isNotEmpty() && suffix.isNotEmpty() -> "$prefix $version $suffix"
        version.isNotEmpty() -> "$prefix $version"
        else -> name.take(28) + "…"
    }
}

internal data class WineRuntimeOption(
    val id: String,
    val label: String,
    val type: String,
    val version: String,
    val installed: Boolean
)
internal data class SettingsCatalog(
    val dxvk: VersionCatalog,
    val vkd3d: VersionCatalog,
    val fex: VersionCatalog,
    val box: VersionCatalog,
    val wow: VersionCatalog,
    val drivers: List<DriverOption>,
    val rendererDrivers: Map<String, String>
)

private suspend fun syncRemoteContents(context: Context, manager: ContentsManager) {
    withContext(Dispatchers.IO) {
        runCatching {
            manager.syncContents()
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val url = prefs.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES)
                ?: ContentsManager.REMOTE_PROFILES
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.let(manager::setRemoteProfiles)
            }
            manager.syncContents()
        }
    }
}

internal suspend fun loadWineRuntimeOptions(context: Context): List<WineRuntimeOption> = withContext(Dispatchers.IO) {
    val manager = ContentsManager(context)
    syncRemoteContents(context, manager)
    val out = LinkedHashMap<String, WineRuntimeOption>()

    listOf(
        ContentProfile.ContentType.CONTENT_TYPE_WINE,
        ContentProfile.ContentType.CONTENT_TYPE_PROTON
    ).forEach { type ->
        manager.getProfiles(type).orEmpty().forEach { profile ->
            val id = ContentsManager.getEntryName(profile)
            out[id] = WineRuntimeOption(
                id = id,
                label = profile.verName,
                type = type.toString(),
                version = profile.verName,
                installed = profile.remoteUrl == null
            )
        }
    }

    ProtonPackageManager.getPackages().forEach { packageInfo ->
        val installed = ProtonPackageManager.isInstalled(context, packageInfo.identifier)
        val existing = out[packageInfo.identifier]
        if (existing?.installed != true) {
            out[packageInfo.identifier] = WineRuntimeOption(
                id = packageInfo.identifier,
                label = packageInfo.title,
                type = ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString(),
                version = packageInfo.title,
                installed = installed
            )
        }
    }

    val mainId = WineInfo.MAIN_WINE_VERSION.identifier()
    if (WineRuntimeGuard.isBundledMainInstalled(context) && out[mainId] == null) {
        out[mainId] = WineRuntimeOption(
            id = mainId,
            label = ProtonPackageManager.getPackage(mainId)?.title ?: mainId,
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString(),
            version = WineInfo.MAIN_WINE_VERSION.fullVersion(),
            installed = true
        )
    }

    out.values.sortedWith(
        compareByDescending<WineRuntimeOption> { it.id == WineInfo.MAIN_WINE_VERSION.identifier() }
            .thenByDescending { it.installed }
            .thenBy { it.type.lowercase() }
            .thenBy { it.label.lowercase() }
    )
}

internal suspend fun installWineRuntimeComponent(context: Context, option: WineRuntimeOption): String? {
    if (option.installed) return option.id

    ProtonPackageManager.getPackage(option.id)?.let { packageInfo ->
        val archive = File(context.cacheDir, "winz-${System.nanoTime()}-${packageInfo.fileName}")
        val installed = withContext(Dispatchers.IO) {
            try {
                ProtonPackageManager.downloadPackage(packageInfo, archive) { _ -> } &&
                    ProtonPackageManager.installPackage(context, packageInfo.identifier, archive)
            } finally {
                archive.delete()
            }
        }
        return packageInfo.identifier.takeIf {
            installed && ProtonPackageManager.isInstalled(context, packageInfo.identifier)
        }
    }

    val installedName = installRuntimeComponent(context, option.type, option.version) ?: return null
    val manager = ContentsManager(context)
    manager.syncContents()
    val type = ContentProfile.ContentType.getTypeByName(option.type) ?: return null
    return manager.getInstalledProfiles(type)
        .filter { it.verName == installedName }
        .maxByOrNull { it.verCode }
        ?.let { ContentsManager.getEntryName(it) }
}

internal suspend fun loadSettingsCatalog(
    context: Context,
    arm64: Boolean,
    selectedDxvk: String = "",
    selectedVkd3d: String = "",
    selectedFex: String = "",
    selectedBox: String = "",
    selectedDriver: String = ""
): SettingsCatalog = withContext(Dispatchers.IO) {
    val manager = ContentsManager(context)
    syncRemoteContents(context, manager)

    fun versions(
        type: ContentProfile.ContentType,
        bundled: Iterable<String>,
        selected: String,
        filterArm: Boolean = true
    ): VersionCatalog {
        val allowed: (String) -> Boolean = { value ->
            !filterArm || arm64 || !value.contains("arm64ec", ignoreCase = true)
        }
        val all = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(all::add)
        runCatching { manager.getProfiles(type) }.getOrNull().orEmpty()
            .map { it.verName }.filter(allowed).forEach(all::add)
        if (selected.isNotBlank()) all.add(selected)

        val installed = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(installed::add)
        runCatching { manager.getInstalledProfiles(type) }.getOrNull().orEmpty()
            .map { it.verName }.filter(allowed).forEach(installed::add)
        return VersionCatalog(all.toList(), installed)
    }

    val adreno = AdrenotoolsManager(context)
    val rendererDrivers = linkedMapOf("system" to "System")
    val driverOptions = linkedMapOf<String, DriverOption>()

    context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).forEach { version ->
        if (version.equals("System", ignoreCase = true) || GPUInformation.isDriverSupported(version, context)) {
            driverOptions[version.lowercase()] = DriverOption(version, version, true)
        }
    }
    runCatching { adreno.enumerateRendererDrivers() }.getOrNull().orEmpty().forEach { id ->
        val driverName = adreno.getDriverName(id)
        val driverVersion = adreno.getDriverVersion(id)
        // 优先显示 名称+版本；版本为空时用目录名（含版本信息）
        val label = when {
            driverName.isNotBlank() && driverVersion.isNotBlank() -> "$driverName $driverVersion"
            driverName.isNotBlank() -> driverName
            else -> id
        }
        rendererDrivers[id] = label
        driverOptions[id.lowercase()] = DriverOption(id, label, true)
    }
    runCatching { RemoteDriverCatalog.load(context) }.getOrNull().orEmpty().forEach { remote ->
        val alreadyInstalled = driverOptions.values.any {
            it.id.equals(remote.name, ignoreCase = true) || it.label.equals(remote.name, ignoreCase = true)
        }
        if (!alreadyInstalled) {
            // 缩短过长的asset文件名：保留版本号关键部分
            val shortLabel = shortenDriverName(remote.name)
            driverOptions["remote:${remote.name}:${remote.url}"] =
                DriverOption(remote.name, shortLabel, false, remote.url, remote.repository, remote.publishedAt, remote.tagName)
        }
    }
    if (selectedDriver.isNotBlank() && driverOptions.values.none { it.id.equals(selectedDriver, ignoreCase = true) }) {
        driverOptions["selected:${selectedDriver.lowercase()}"] = DriverOption(selectedDriver, selectedDriver, true)
    }

    SettingsCatalog(
        dxvk = versions(
            ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            context.resources.getStringArray(R.array.dxvk_version_entries).toList(),
            selectedDxvk
        ).let { catalog ->
            VersionCatalog(catalog.all.filterNot { it == DXWrapper.VEGAS_VERSION },
                catalog.installed.filterNot { it == DXWrapper.VEGAS_VERSION }.toSet())
        },
        vkd3d = versions(
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            context.resources.getStringArray(R.array.vkd3d_version_entries).toList(),
            selectedVkd3d
        ),
        fex = versions(
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            listOf(DefaultVersion.FEXCORE), selectedFex, false
        ),
        box = versions(
            ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            listOf(DefaultVersion.BOX64), selectedBox, false
        ),
        wow = versions(
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
            listOf(DefaultVersion.WOWBOX64), selectedBox, false
        ),
        drivers = driverOptions.values.toList(),
        rendererDrivers = rendererDrivers
    )
}

internal suspend fun installRuntimeComponent(
    context: Context,
    typeName: String,
    version: String
): String? {
    val manager = ContentsManager(context)
    val profile = withContext(Dispatchers.IO) {
        try {
            syncRemoteContents(context, manager)
            val type = ContentProfile.ContentType.getTypeByName(typeName) ?: return@withContext null
            manager.getProfiles(type).orEmpty().firstOrNull {
                it.verName == version && it.remoteUrl != null
            }
        } catch (_: Exception) {
            null
        }
    } ?: return null

    val archive = File(context.cacheDir, "winz-${System.nanoTime()}")
    val downloaded = withContext(Dispatchers.IO) {
        try {
            OkHttpClient().newCall(Request.Builder().url(profile.remoteUrl).build()).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    false
                } else {
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(archive).use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }
    if (!downloaded) return null

    val result = suspendCoroutine<String?> { continuation ->
        manager.extraContentFile(Uri.fromFile(archive), object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, error: Exception?) {
                continuation.resume(null)
            }

            override fun onSucceed(extracted: ContentProfile) {
                manager.finishInstallContent(extracted, object : ContentsManager.OnInstallFinishedCallback {
                    override fun onFailed(reason: ContentsManager.InstallFailedReason, error: Exception?) {
                        continuation.resume(
                            if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) profile.verName else null
                        )
                    }

                    override fun onSucceed(installed: ContentProfile) {
                        continuation.resume(installed.verName)
                    }
                })
            }
        })
    }
    archive.delete()
    ContentsManager.cleanTmpDir(context)
    manager.syncContents()
    return result
}

internal suspend fun installAdrenoDriver(context: Context, option: DriverOption): String? =
    withContext(Dispatchers.IO) {
        if (option.remoteUrl != null) {
            RemoteDriverCatalog.install(context, option.remoteUrl).takeIf { it.isNotBlank() }
        } else {
            option.id.takeIf { option.installed }
        }
    }

internal fun readConfig(config: String?, key: String, separator: Char): String {
    config.orEmpty().split(separator).forEach { token ->
        val index = token.indexOf('=')
        if (index > 0 && token.substring(0, index).trim() == key) {
            return token.substring(index + 1).trim()
        }
    }
    return ""
}

internal fun writeConfig(config: String?, key: String, value: String, separator: Char): String {
    val out = ArrayList<String>()
    var found = false
    config.orEmpty().split(separator).filter { it.isNotBlank() }.forEach { token ->
        val index = token.indexOf('=')
        if (index > 0 && token.substring(0, index).trim() == key) {
            out += "$key=$value"
            found = true
        } else {
            out += token.trim()
        }
    }
    if (!found) out += "$key=$value"
    return out.joinToString(separator.toString())
}

internal fun normalizeResolution(value: String): String =
    value.replace(Regex("\\s*\\(.*\\)$"), "").trim()

private fun settingDisplayLabel(value: String): String =
    if (value == "Lanczos 2 (16-tap)") "Lanczos 2" else value

private fun settingFieldLabel(label: String): String = when (label) {
    "Graphics Driver" -> "OpenGL Driver"
    "Driver Version" -> "Vulkan Driver"
    else -> label
}

private fun settingChoiceEntries(label: String, entries: List<String>): List<String> =
    if (label == "Graphics Driver") listOf("Zink", "Freedreno") else entries
private fun settingChoiceSelected(label: String, selected: String): String =
    if (label == "Graphics Driver" && selected.equals("wrapper", ignoreCase = true)) "Zink" else selected

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f))
    ) {
        Column { content() }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .52f)
    )
}

@Composable
private fun WallpaperPreview() {
    val context = LocalContext.current
    val file = WineThemeManager.getUserWallpaperFile(context)
    val stamp = if (file.isFile) file.lastModified() else 0L
    val bitmap = remember(stamp) {
        if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Wallpaper preview",
                modifier = Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.wallpaper),
                contentDescription = "Wallpaper preview",
                modifier = Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
internal fun SettingChoice(
    label: String,
    selected: String,
    entries: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = settingFieldLabel(label)
    val displaySelected = settingChoiceSelected(label, selected)
    val displayEntries = settingChoiceEntries(label, entries)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            settingDisplayLabel(displaySelected),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 220.dp, max = 420.dp).heightIn(max = 480.dp)
            ) {
                displayEntries.distinct().forEach { value ->
                    DropdownMenuItem(
                        text = { Text(settingDisplayLabel(value), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { if (value == displaySelected) Icon(Icons.Outlined.Check, null) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        }
                    )
                }
            }
        }
        if (label == "Desktop Background" && selected.equals("Image", ignoreCase = true)) {
            WallpaperPreview()
        }
    }
}

@Composable
internal fun SettingMappedChoice(
    label: String,
    selectedId: String,
    entries: Map<String, String>,
    onSelectedId: (String) -> Unit
) {
    val shown = entries[selectedId] ?: selectedId
    SettingChoice(label, shown, entries.values.toList()) { selectedLabel ->
        onSelectedId(entries.entries.firstOrNull { it.value == selectedLabel }?.key ?: selectedId)
    }
}

@Composable
internal fun SettingWineRuntimeChoice(
    label: String,
    selectedId: String,
    options: List<WineRuntimeOption>,
    installing: Set<String>,
    onInstall: (WineRuntimeOption) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedOption?.label ?: selectedId.ifBlank { "Choose a version" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 460.dp).heightIn(max = 500.dp)
        ) {
            options.forEach { option ->
                val busy = "wine:${option.id}" in installing
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.installed) 1f else .52f))
                            Text(
                                if (option.installed) option.type else if (busy) "${option.type} • Downloading…" else "${option.type} • Download",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (option.installed && option.id == selectedId) Icon(Icons.Outlined.Check, null)
                    },
                    onClick = {
                        if (option.installed) {
                            expanded = false
                            onSelected(option.id)
                        } else if (!busy) {
                            onInstall(option)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingInstallChoice(
    label: String,
    selected: String,
    catalog: VersionCatalog,
    installing: Set<String>,
    prefix: String,
    onInstall: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected.ifBlank { "Choose a version" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 440.dp).heightIn(max = 480.dp)
        ) {
            catalog.all.forEach { value ->
                val available = catalog.installed.any { it.equals(value, ignoreCase = true) } || value == selected
                val busy = "$prefix:$value" in installing
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (available) 1f else .52f))
                            if (!available) Text(if (busy) "Downloading…" else "Download", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (value == selected) Icon(Icons.Outlined.Check, null)
                    },
                    onClick = {
                        if (available) {
                            expanded = false
                            onSelected(value)
                        } else if (!busy) {
                            onInstall(value)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingDriverChoice(
    label: String,
    selected: String,
    options: List<DriverOption>,
    installing: Set<String>,
    onInstall: (DriverOption) -> Unit,
    onSelected: (String) -> Unit
) {
    // 按作者分组
    val installedOptions = options.filter { it.installed }
    val remoteOptions = options.filter { !it.installed }
    val repositories = remoteOptions.map { it.repository }.distinct().filter { it.isNotEmpty() }
    
    // 分类列表：已安装 + 各作者
    val categories = listOf("已安装") + repositories
    val selectedOption = options.firstOrNull { it.id.equals(selected, ignoreCase = true) }
    
    // 默认选中包含当前驱动的分类
    val initialCategory = when {
        selectedOption?.installed == true -> "已安装"
        selectedOption?.repository?.isNotEmpty() == true -> selectedOption.repository
        else -> categories.firstOrNull() ?: "已安装"
    }
    var selectedCategory by remember(selected) { mutableStateOf(initialCategory) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var versionExpanded by remember { mutableStateOf(false) }
    
    // 当前分类下的版本列表
    val currentOptions = when (selectedCategory) {
        "已安装" -> installedOptions
        else -> remoteOptions.filter { it.repository == selectedCategory }
    }.sortedByDescending { it.publishedAt }
    
    // 格式化发布时间
    fun formatTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val diff = System.currentTimeMillis() - timestamp
        val days = diff / (1000 * 60 * 60 * 24)
        return when {
            days < 1 -> "今天"
            days < 2 -> "昨天"
            days < 30 -> "${days}天前"
            else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }
    }
    
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(settingFieldLabel(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        // 第一行：作者分类 + 当前选中版本
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 作者分类下拉
            Box(Modifier.weight(1f)) {
                Surface(onClick = { categoryExpanded = true }, color = Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(selectedCategory, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }, modifier = Modifier.widthIn(min = 180.dp, max = 300.dp)) {
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat, fontWeight = if (cat == selectedCategory) FontWeight.Bold else FontWeight.Normal) },
                            trailingIcon = { if (cat == selectedCategory) Icon(Icons.Outlined.Check, null) },
                            onClick = { selectedCategory = cat; categoryExpanded = false }
                        )
                    }
                }
            }
            
            // 版本下拉
            Box(Modifier.weight(1.5f)) {
                Surface(onClick = { versionExpanded = true }, color = Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            selectedOption?.label ?: selected,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = versionExpanded, onDismissRequest = { versionExpanded = false }, modifier = Modifier.widthIn(min = 260.dp, max = 460.dp).heightIn(max = 500.dp)) {
                    if (currentOptions.isEmpty()) {
                        DropdownMenuItem(text = { Text("暂无可用版本", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }, onClick = { versionExpanded = false })
                    }
                    currentOptions.forEach { option ->
                        val busy = "driver:${option.remoteUrl ?: option.id}" in installing
                        val timeStr = if (option.publishedAt > 0) formatTime(option.publishedAt) else ""
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.installed) 1f else .52f))
                                    Row {
                                        if (!option.installed) {
                                            val tagText = if (busy) "Downloading…" else if (option.tag.isNotEmpty()) option.tag else "Download"
                                            Text(tagText, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (timeStr.isNotEmpty()) Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            },
                            trailingIcon = {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else if (option.installed && option.id.equals(selected, ignoreCase = true)) Icon(Icons.Outlined.Check, null)
                            },
                            onClick = {
                                if (option.installed) {
                                    versionExpanded = false
                                    onSelected(option.id)
                                } else if (!busy) {
                                    onInstall(option)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChanged, enabled = enabled)
    }
}

@Composable
internal fun FrameGenerationSettings(
    backend: String,
    multiplier: Int,
    flowScale: Float,
    lsfgAvailable: Boolean,
    onBackendChanged: (String) -> Unit,
    onMultiplierChanged: (Int) -> Unit,
    onFlowScaleChanged: (Float) -> Unit
) {
    val winFg = backend == "win_fg_native"
    val selected = when {
        multiplier < 2 -> "Off"
        winFg -> "Win-FG Native"
        else -> "LSFG Native ${multiplier}x"
    }
    SettingChoice("Frame Generation", selected,
        listOf("Off", "Win-FG Native", "LSFG Native 2x", "LSFG Native 3x", "LSFG Native 4x")) {
        when {
            it == "Off" -> onMultiplierChanged(0)
            it == "Win-FG Native" -> {
                onBackendChanged("win_fg_native")
                onMultiplierChanged(2)
            }
            else -> {
                onBackendChanged("lsfg_native")
                onMultiplierChanged(if (lsfgAvailable) it.substringAfterLast(' ').removeSuffix("x").toInt() else 0)
            }
        }
    }
    SettingsDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Flow Scale", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(java.util.Locale.US, "%.2f", flowScale), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = flowScale.coerceIn(0.25f, 1f),
            onValueChange = onFlowScaleChanged,
            valueRange = 0.25f..1f,
            steps = 74
        )
    }
    Text(
        if (winFg) "Win-FG Native is built in and runs at 2x"
        else if (!lsfgAvailable) "Import Lossless.dll before enabling LSFG Native"
        else "LSFG Native requires a Vulkan 1.3 driver",
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SettingText(
    label: String,
    value: String,
    minLines: Int = 1,
    onChanged: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChanged,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        minLines = minLines,
        maxLines = if (minLines > 1) 5 else 1,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
internal fun CpuSelectorRow(
    title: String,
    selected: List<Boolean>,
    onToggle: (Int, Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(selected.indices.toList(), key = { it }) { index ->
                Surface(
                    onClick = { onToggle(index, !selected[index]) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected[index]) Color.White else Color.Transparent,
                    contentColor = if (selected[index]) Color.Black else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, if (selected[index]) Color.White else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        "CPU$index",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        color = if (selected[index]) Color.Black else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected[index]) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}