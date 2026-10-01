package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.contents.Downloader
import com.winlator.cmod.contents.RemoteDriverCatalog
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.ui.settings.DownloadableContentsSourceCard

private val bundledRuntimeId = "bundled:${ProtonPackageManager.DEFAULT_IDENTIFIER}"
private val bundledRuntimeName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER)?.title
    ?: "Proton 10.0-5 arm64ec"

private val componentCategories = listOf(
    "Recommended", "Wine & Proton", "DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64", "AdrenoTools"
)

private val latestRecommendedTypes = setOf("DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64")

private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
    val count = maxOf(left.size, right.size)
    for (index in 0 until count) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun componentVersionParts(type: String, name: String): List<Int> {
    var clean = name.lowercase()
        .replace("arm64ec", "")
        .replace("x86_64", "")

    if (type == "FEXCore") {
        val token = Regex("\\d{6}|\\d{4}(?:\\.\\d+)?").find(clean)?.value.orEmpty()
        val base = token.substringBefore('.')
        val suffix = token.substringAfter('.', "").toIntOrNull()
        return when (base.length) {
            6 -> listOf(
                base.substring(0, 2).toIntOrNull() ?: 0,
                base.substring(2, 4).toIntOrNull() ?: 0,
                base.substring(4, 6).toIntOrNull() ?: 0
            )
            4 -> buildList {
                add(base.substring(0, 2).toIntOrNull() ?: 0)
                add(base.substring(2, 4).toIntOrNull() ?: 0)
                if (suffix != null) add(suffix)
            }
            else -> emptyList()
        }
    }

    if ((type == "Box64" || type == "WOWBox64") && Regex("^0?\\d{3}(?:\\D|$)").containsMatchIn(clean)) {
        val digits = Regex("\\d{3}").find(clean)?.value.orEmpty()
        if (digits.length == 3) {
            return listOf(
                digits.substring(0, 1).toIntOrNull() ?: 0,
                digits.substring(1, 2).toIntOrNull() ?: 0,
                digits.substring(2, 3).toIntOrNull() ?: 0
            )
        }
    }

    if (type == "DXVK" && clean.startsWith("11.1")) {
        clean = "1.1.1" + clean.removePrefix("11.1")
    }

    val token = Regex("\\d+(?:\\.\\d+){0,3}").find(clean)?.value ?: return emptyList()
    return token.split('.').map { it.toIntOrNull() ?: 0 }
}

private fun recommendedComponentIds(all: List<OnboardingComponent>): Set<String> {
    val result = all.filter { it.recommended && it.type !in latestRecommendedTypes }
        .mapTo(linkedSetOf()) { it.id }

    latestRecommendedTypes.forEach { type ->
        all.withIndex()
            .filter { it.value.type == type }
            .maxWithOrNull { left, right ->
                val byVersion = compareVersionParts(
                    componentVersionParts(type, left.value.name),
                    componentVersionParts(type, right.value.name)
                )
                if (byVersion != 0) byVersion else left.index.compareTo(right.index)
            }
            ?.value
            ?.let { result.add(it.id) }
    }
    return result
}

@Composable
internal fun OnboardingComponentsScreen(
    ready: State<Boolean>,
    progress: State<Int>,
    bundledInstalled: State<Boolean>,
    bundledInUse: State<Boolean>,
    all: List<OnboardingComponent>,
    catalogLoading: Boolean,
    contentsUrl: String,
    onContentsUrlChanged: (String) -> Unit,
    installing: String?,
    installingLabel: String?,
    installingProgress: Int,
    managerMode: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    cb: OnboardingCallbacks
) {
    var category by rememberSaveable { mutableStateOf("Recommended") }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val recommendedIds = remember(all) { recommendedComponentIds(all) }
    val visible = remember(all, category, recommendedIds) {
        all.filter {
            when (category) {
                "Recommended" -> it.id in recommendedIds
                "Wine & Proton" -> it.type == "Wine" || it.type == "Proton"
                else -> it.type == category
            }
        }
    }
    val hasInstalledRuntime = bundledInstalled.value || all.any {
        it.installed && (it.type == "Wine" || it.type == "Proton") && !it.runtimeIdentifier.isNullOrBlank()
    }
    val showBundled = category == "Recommended" || category == "Wine & Proton"
    val showLocalInstallProgress = installing == "local" || installing == "driver-local"

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) {
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(Modifier.weight(.9f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Text("Choose components", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (managerMode) "Install and manage runtime versions."
                        else "Install a Wine or Proton layer before continuing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    SourceSelector { cb.onBrowseLocal() }
                    Spacer(Modifier.height(10.dp))
                    DownloadableContentsSourceCard(contentsUrl, onContentsUrlChanged)
                    if (showLocalInstallProgress) {
                        Spacer(Modifier.height(10.dp))
                        InstallProgressCard(installingLabel, installingProgress)
                    }
                    Spacer(Modifier.height(10.dp))
                    CategorySelector(category) { category = it }
                    if (category == "AdrenoTools") {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { cb.onBrowseDriver() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Install local driver")
                        }
                    }
                    if (showBundled) {
                        Spacer(Modifier.height(12.dp))
                        CoreComponentCard(
                            ready = ready,
                            progress = progress,
                            installed = bundledInstalled.value,
                            inUse = bundledInUse.value,
                            busy = installing == bundledRuntimeId,
                            locked = installing != null,
                            onInstall = cb::onInstallBundledRuntime,
                            onRemove = cb::onRemoveBundledRuntime
                        )
                    }
                    if (!managerMode && !hasInstalledRuntime) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (!ready.value) "Wait for $bundledRuntimeName to finish installing, or install another Wine/Proton version."
                            else "Install at least one Wine or Proton version to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (category == "AdrenoTools") {
                    AdrenoToolsDriverList(cb, Modifier.weight(1.2f).fillMaxHeight().padding(top = 10.dp))
                } else {
                    ComponentList(
                        visible,
                        catalogLoading,
                        installing,
                        installingLabel,
                        installingProgress,
                        cb,
                        Modifier.weight(1.2f).fillMaxHeight()
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Choose components", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (managerMode) "Install and manage runtime versions."
                        else "Install as many versions as you want. At least one Wine or Proton is required.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    SourceSelector { cb.onBrowseLocal() }
                    Spacer(Modifier.height(10.dp))
                    DownloadableContentsSourceCard(contentsUrl, onContentsUrlChanged)
                    if (showLocalInstallProgress) {
                        Spacer(Modifier.height(10.dp))
                        InstallProgressCard(installingLabel, installingProgress)
                    }
                    Spacer(Modifier.height(12.dp))
                    CategorySelector(category) { category = it }
                    if (category == "AdrenoTools") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { cb.onBrowseDriver() }) { Text("Install local driver") }
                    }
                    if (showBundled) {
                        Spacer(Modifier.height(10.dp))
                        CoreComponentCard(
                            ready = ready,
                            progress = progress,
                            installed = bundledInstalled.value,
                            inUse = bundledInUse.value,
                            busy = installing == bundledRuntimeId,
                            locked = installing != null,
                            onInstall = cb::onInstallBundledRuntime,
                            onRemove = cb::onRemoveBundledRuntime
                        )
                    }
                }
                if (catalogLoading) item { LoadingCard() }
                else if (visible.isEmpty()) item {
                    Text("No components available in this category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else if (category == "AdrenoTools") item {
                    AdrenoToolsDriverList(cb, Modifier.fillMaxWidth())
                }
                else items(visible, key = { it.id }) {
                    ComponentCard(
                        it,
                        installing == it.id,
                        installing != null,
                        installingLabel,
                        installingProgress,
                        cb
                    )
                }
                if (!managerMode && !hasInstalledRuntime) {
                    item {
                        Text(
                            if (!ready.value) "Continue unlocks when $bundledRuntimeName finishes installing or another Wine/Proton layer is installed."
                            else "Install at least one Wine or Proton version to continue.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        ComponentsFooter(
            back = onBack,
            next = onContinue,
            landscape = landscape,
            nextEnabled = managerMode || hasInstalledRuntime,
            nextLabel = if (managerMode) "Done" else "Continue"
        )
    }
}

@Composable
private fun ComponentList(
    list: List<OnboardingComponent>,
    loading: Boolean,
    installing: String?,
    installingLabel: String?,
    installingProgress: Int,
    cb: OnboardingCallbacks,
    modifier: Modifier
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) item { LoadingCard() }
        else if (list.isEmpty()) item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Text(
                    "No components available in this category.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else items(list, key = { it.id }) {
            ComponentCard(
                it,
                installing == it.id,
                installing != null,
                installingLabel,
                installingProgress,
                cb
            )
        }
    }
}

@Composable
private fun SourceSelector(local: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.height(56.dp)) {
            SourcePart(Icons.Outlined.Dns, "Winlator servers", true, {}, Modifier.weight(1f))
            SourcePart(Icons.Outlined.Folder, "Local package", false, local, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourcePart(icon: ImageVector, label: String, selected: Boolean, click: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = click,
        modifier = modifier.fillMaxHeight(),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CategorySelector(selected: String, select: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        componentCategories.forEach {
            Surface(
                onClick = { select(it) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected == it) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(it, Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CoreComponentCard(
    ready: State<Boolean>,
    progress: State<Int>,
    installed: Boolean,
    inUse: Boolean,
    busy: Boolean,
    locked: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(bundledRuntimeName, fontWeight = FontWeight.SemiBold)
                val status = when {
                    busy -> "Working…"
                    installed && inUse -> "Bundled • Installed • In use"
                    installed -> "Bundled • Installed"
                    !ready.value -> "Bundled • Installing ${progress.value}%"
                    else -> "Bundled • Not installed"
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            when {
                busy || (!ready.value && !installed) -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                installed -> OutlinedButton(onClick = onRemove, enabled = !locked && !inUse) {
                    Icon(Icons.Outlined.DeleteOutline, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (inUse) "In use" else "Delete")
                }
                else -> OutlinedButton(onClick = onInstall, enabled = !locked) {
                    Icon(Icons.Outlined.Download, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun ComponentCard(
    item: OnboardingComponent,
    busy: Boolean,
    locked: Boolean,
    installingLabel: String?,
    installingProgress: Int,
    cb: OnboardingCallbacks
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        busy && installingProgress >= 0 ->
                            "${installingLabel ?: "Installing"} • ${installingProgress}%"
                        busy -> installingLabel ?: "Working…"
                        item.inUse -> "${item.type} • In use"
                        else -> item.type
                    }
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                else if (item.installed && item.removable) {
                    OutlinedButton(onClick = { cb.onRemove(item.id) }, modifier = Modifier.width(112.dp), enabled = !locked && !item.inUse) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (item.inUse) "In use" else "Delete")
                    }
                } else if (!item.installed) {
                    OutlinedButton(onClick = { cb.onInstall(item.id) }, modifier = Modifier.width(112.dp), enabled = !locked) { Text("Download") }
                } else Icon(Icons.Outlined.Check, null)
            }
            if (busy) {
                Spacer(Modifier.height(9.dp))
                if (installingProgress >= 0) {
                    LinearProgressIndicator(
                        progress = { installingProgress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun InstallProgressCard(label: String?, progress: Int) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Component installation", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (progress >= 0) "${label ?: "Installing"} • ${progress}%"
                        else label ?: "Installing component…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(10.dp))
            if (progress >= 0) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text("Loading component catalog…")
        }
    }
}

@Composable
private fun ComponentsFooter(
    back: () -> Unit,
    next: () -> Unit,
    landscape: Boolean,
    nextEnabled: Boolean,
    nextLabel: String
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (landscape) 22.dp else 20.dp,
                vertical = if (landscape) 8.dp else 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = back,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Back") }
            Button(
                onClick = next,
                enabled = nextEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text(nextLabel) }
        }
    }
}


@Composable
private fun AdrenoToolsDriverList(
    cb: OnboardingCallbacks,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var drivers by remember { mutableStateOf<List<RemoteDriverCatalog.Entry>>(emptyList()) }
    var installedDrivers by remember { mutableStateOf<List<String>>(emptyList()) }
    var installedInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var selectedRepo by remember { mutableStateOf("全部") }
    var installingUrl by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        kotlin.concurrent.thread {
            try {
                val manager = AdrenotoolsManager(context)
                val installed = manager.enumarateInstalledDrivers()
                val info = mutableMapOf<String, String>()
                for (id in installed) {
                    val originalName = manager.getOriginalFileName(id)
                    val name = manager.getDriverName(id)
                    val version = manager.getDriverVersion(id)
                    val display = when {
                        // 优先显示安装时的zip原文件名（如 adrenotools-turnip-26.2.0-b9.tzst）
                        originalName.isNotEmpty() -> originalName.removeSuffix(".zip").removeSuffix(".tzst")
                        name.isNotEmpty() && version.isNotEmpty() -> "$name $version"
                        name.isNotEmpty() -> name
                        else -> id
                    }
                    info[id] = display
                }
                (context as? android.app.Activity)?.runOnUiThread {
                    installedDrivers = installed
                    installedInfo = info
                }
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        refreshInstalled()
        kotlin.concurrent.thread {
            try {
                val loaded = RemoteDriverCatalog.load(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    drivers = loaded
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    loading = false
                }
            }
        }
    }

    val repos = remember(drivers) {
        listOf("全部") + drivers.map { it.repository }.distinct().filter { it.isNotEmpty() }
    }

    val filtered = remember(drivers, selectedRepo) {
        val list = if (selectedRepo == "全部") drivers else drivers.filter { it.repository == selectedRepo }
        // 去重：按 name+tagName 去重，保留第一个（通常是最新的）
        val seen = mutableSetOf<String>()
        list.filter { seen.add("${it.name}|${it.tagName}") }
    }

    // 已安装驱动（不在在线列表中的也显示）
    val installedOnly = remember(installedDrivers, filtered) {
        installedDrivers.filter { id ->
            filtered.none { it.name.equals(id, ignoreCase = true) || it.name.contains(id, ignoreCase = true) }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 作者分类Tab
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repos.forEach { repo ->
                val selected = repo == selectedRepo
                Surface(
                    onClick = { selectedRepo = repo },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(32.dp)
                ) {
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            repo,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        } else {
            // 已安装驱动区域
            if (installedDrivers.isNotEmpty()) {
                Text("已安装 (${installedDrivers.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            installedDrivers.forEach { id ->
                val isDeleting = deletingId == id
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MarqueeText(installedInfo[id] ?: id, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("已安装", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                        if (isDeleting) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = {
                                androidx.appcompat.app.AlertDialog.Builder(context)
                                    .setTitle("删除驱动")
                                    .setMessage("确定要删除驱动「$id」吗？使用该驱动的容器将回退到系统驱动。")
                                    .setPositiveButton("删除") { _, _ ->
                                        deletingId = id
                                        kotlin.concurrent.thread {
                                            try {
                                                val manager = AdrenotoolsManager(context)
                                                manager.removeDriver(id)
                                                (context as? android.app.Activity)?.runOnUiThread {
                                                    deletingId = null
                                                    refreshInstalled()
                                                    android.widget.Toast.makeText(context, "已删除: $id", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                (context as? android.app.Activity)?.runOnUiThread {
                                                    deletingId = null
                                                    android.widget.Toast.makeText(context, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // 在线驱动区域
            if (filtered.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("在线驱动 (${filtered.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            filtered.forEach { driver ->
                val isInstalled = installedDrivers.any { it.equals(driver.name, ignoreCase = true) || driver.name.contains(it, ignoreCase = true) }
                val isInstalling = installingUrl == driver.url
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isInstalled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            MarqueeText(driver.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Row {
                                if (driver.tagName.isNotEmpty()) {
                                    Text(driver.tagName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (driver.publishedAt > 0) {
                                    val diff = System.currentTimeMillis() - driver.publishedAt
                                    val days = diff / (1000 * 60 * 60 * 24)
                                    val timeStr = when {
                                        days < 1 -> "今天"
                                        days < 2 -> "昨天"
                                        days < 30 -> "${days}天前"
                                        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(driver.publishedAt))
                                    }
                                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(driver.repository, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                        if (isInstalling) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else if (isInstalled) {
                            Text("已安装", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = {
                                installingUrl = driver.url
                                kotlin.concurrent.thread {
                                    try {
                                        // 从URL提取原始文件名（如 Turnip-v26.3.0-xxx.zip），用于已安装列表显示
                                        val originalName = driver.url.substringAfterLast('/').ifEmpty { "driver_${System.currentTimeMillis()}.zip" }
                                        val tmpFile = java.io.File(context.cacheDir, originalName)
                                        val success = Downloader.downloadFile(driver.url, tmpFile)
                                        if (success) {
                                            val manager = AdrenotoolsManager(context)
                                            val installed = manager.installDriver(android.net.Uri.fromFile(tmpFile))
                                            tmpFile.delete()
                                            (context as? android.app.Activity)?.runOnUiThread {
                                                installingUrl = null
                                                refreshInstalled()
                                                if (installed.isNotEmpty()) {
                                                    android.widget.Toast.makeText(context, "已安装: $installed", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "安装失败", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            (context as? android.app.Activity)?.runOnUiThread {
                                                installingUrl = null
                                                android.widget.Toast.makeText(context, "下载失败", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        (context as? android.app.Activity)?.runOnUiThread {
                                            installingUrl = null
                                        }
                                    }
                                }
                            }) {
                                Text("下载")
                            }
                        }
                    }
                }
            }

            if (installedDrivers.isEmpty() && filtered.isEmpty()) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Text("暂无可用驱动", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 缩短驱动名称，去除冗余日期和重复前缀，保留关键版本信息。
 * 例："Turnip-v26.3.0-20260930-r5-710-720" → "Turnip v26.3.0 r5 (710-720)"
 *     "Turnip Gen8 V37 / Turnip v26.3.0-R6" → 原样（已较短）
 */
private fun shortenDriverName(name: String): String {
    if (name.length <= 28) return name
    // 移除8位日期（如20260930）
    var result = name.replace(Regex("\\d{8}"), "").trim()
    // 将连续的-或_替换为空格
    result = result.replace(Regex("[-_]+"), " ").trim()
    // 压缩多余空格
    result = result.replace(Regex("\\s+"), " ")
    // 如果仍超过28字符，截断并加省略号
    if (result.length > 28) {
        result = result.take(26) + "…"
    }
    return result
}

/**
 * 自定义跑马灯Text组件：长文本自动横向滚动显示完整内容。
 * 不依赖basicMarquee的Experimental API，兼容性更好。
 */
@Composable
private fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    fontWeight: FontWeight? = null
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        // 先停留在开头，延迟后再开始滚动
        delay(1000)
        while (true) {
            if (scrollState.maxValue > 0) {
                scrollState.animateScrollTo(scrollState.maxValue, animationSpec = tween(durationMillis = 5000))
                delay(600)
                scrollState.scrollTo(0)
                delay(800)
            } else {
                delay(1000)
            }
        }
    }
    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.horizontalScroll(scrollState, enabled = false)
    )
}