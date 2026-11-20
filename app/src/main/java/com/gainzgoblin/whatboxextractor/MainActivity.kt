package com.gainzgoblin.whatboxextractor

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            vm.message = null
            snackbar.showSnackbar(msg)
        }
    }

    when (vm.screen) {
        Screen.SETTINGS -> SettingsScreen(vm, snackbar)
        Screen.BROWSER -> BrowserScreen(vm, snackbar)
    }

    vm.confirm?.let { ExtractDialog(vm, it) }
    vm.pendingDelete?.let { DeleteDialog(vm, it) }
    if (vm.showExtractOptions) ExtractOptionsDialog(vm)
    if (vm.showNewFolder) NewFolderDialog(vm)
    vm.job?.let { if (!it.hidden) JobDialog(vm, it) }
}

// ---------------- Browser ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val path = vm.path
    val atTop = path.isEmpty() || path == "/"
    val selecting = vm.selected.isNotEmpty()
    // Back goes up a folder (selection is kept); at the top it clears the selection.
    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !atTop && path != vm.homePath) { vm.goUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (!atTop) {
                        IconButton(onClick = { vm.goUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder")
                        }
                    }
                },
                title = {
                    Column {
                        val title = when {
                            path.isEmpty() -> "Whatbox"
                            path == vm.homePath -> "Home"
                            path == "/" -> "/"
                            else -> path.substringAfterLast('/')
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (path.isNotEmpty()) {
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    FavoritesMenu(vm)
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { vm.showExtractOptions = true }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (selecting) SelectionBottomBar(vm) },
        floatingActionButton = {
            if (vm.path.isNotEmpty() && vm.error == null && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = { vm.showNewFolder = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New folder") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            vm.update?.let { UpdateBanner(vm, it) }
            vm.job?.let { if (it.hidden) JobBanner(vm, it) }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val err = vm.error
            if (err != null) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.refresh() }) { Text("Retry") }
                        OutlinedButton(onClick = { vm.load("") }) { Text("Go to home folder") }
                    }
                }
            } else if (!vm.loading && vm.files.isEmpty() && path.isNotEmpty()) {
                Text("This folder is empty", Modifier.padding(24.dp))
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(vm.files, key = { it.path }) { f ->
                    FileRow(
                        f = f,
                        selecting = selecting,
                        selected = f.path in vm.selected,
                        onClick = { vm.open(f) },
                        onLongClick = { vm.toggleSelect(f) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    f: RemoteFile,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kind = if (f.isDir) null else Archives.kind(f.name)
    val icon = when {
        f.isDir -> "📁"
        kind != null -> "🗜️"
        else -> "📄"
    }
    val sub = when {
        f.isDir && selecting -> "Folder · tap to open"
        f.isDir -> "Folder"
        selecting -> formatSize(f.size)
        kind != null -> "${formatSize(f.size)} · tap to extract"
        else -> "${formatSize(f.size)} · tap to delete"
    }
    ListItem(
        headlineContent = {
            Text(
                f.name,
                color = if (kind != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontWeight = if (kind != null) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(sub) },
        leadingContent = {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onLongClick() })
            } else {
                Text(icon, fontSize = 26.sp)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
    HorizontalDivider()
}

/** Shown while items are selected. Selection stays while you browse, so you can move things. */
@Composable
fun SelectionBottomBar(vm: MainViewModel) {
    val sel = vm.selectedFiles
    val here = vm.movableHere
    val extractable = sel.any { !it.isDir && Archives.kind(it.name) != null && parentOf(it.path) == vm.path }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Clear selection") }
                Text(
                    "${sel.size} selected",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.selectAll() }, enabled = vm.files.isNotEmpty()) {
                    Text(if (vm.allHereSelected) "Unselect here" else "Select all here")
                }
            }
            if (sel.isNotEmpty() && here.isEmpty()) {
                Text(
                    "Open another folder, then tap Move here.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.deleteSelected() }, enabled = !vm.moving) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
                if (extractable) {
                    OutlinedButton(onClick = { vm.extractSelected() }, enabled = !vm.moving) { Text("Extract") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.moveHere() }, enabled = here.isNotEmpty() && !vm.moving) {
                    if (vm.moving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Moving…")
                    } else {
                        Text(if (here.size == sel.size || here.isEmpty()) "Move here" else "Move ${here.size} here")
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteDialog(vm: MainViewModel, items: List<RemoteFile>) {
    val folders = items.count { it.isDir }
    AlertDialog(
        onDismissRequest = { if (!vm.deleting) vm.cancelDelete() },
        title = {
            Text(
                when {
                    items.size > 1 -> "Delete ${items.size} items?"
                    folders == 1 -> "Delete folder?"
                    else -> "Delete file?"
                },
            )
        },
        text = {
            Column {
                items.take(6).forEach { f ->
                    Text(
                        (if (f.isDir) "📁 " else "📄 ") + f.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (items.size > 6) Text("…and ${items.size - 6} more")
                Spacer(Modifier.size(8.dp))
                if (folders > 0) {
                    Text(
                        "Folders are deleted with everything inside them.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("This can't be undone.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                val torrents = vm.torrentMatches
                val tErr = vm.torrentError
                when {
                    vm.torrentChecking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking rTorrent…", style = MaterialTheme.typography.bodySmall)
                    }
                    torrents.isNotEmpty() -> {
                        val label = if (torrents.size == 1) "Also remove the torrent from rTorrent"
                        else "Also remove ${torrents.size} torrents from rTorrent"
                        CheckRow(label, vm.removeTorrents) { vm.removeTorrents = it }
                        torrents.take(4).forEach {
                            Text(
                                "🧲 " + it.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        if (torrents.size > 4) {
                            Text("…and ${torrents.size - 4} more", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    tErr != null -> Text(
                        "Couldn't check rTorrent: $tErr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        "Not part of any torrent in rTorrent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vm.deleting) {
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.confirmDelete() },
                enabled = !vm.deleting && !vm.torrentChecking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelDelete() }, enabled = !vm.deleting) { Text("Cancel") }
        },
    )
}

@Composable
fun UpdateBanner(vm: MainViewModel, u: UpdateInfo) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Update available: v${u.versionName}",
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                val p = vm.updateProgress
                if (p == null) {
                    Button(onClick = { vm.installUpdate() }) { Text("Install") }
                } else {
                    Text("${(p * 100).toInt()}%")
                }
            }
            val p = vm.updateProgress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun JobBanner(vm: MainViewModel, j: JobState) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable { vm.showJob() },
    ) {
        val pct = j.percent?.let { " $it%" } ?: ""
        Text(
            "Extracting ${j.name}…$pct  (tap to view)",
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

val FavoriteGold = Color(0xFFFFC107)

/** Star button: add/remove the current folder, and jump to any favorite. */
@Composable
fun FavoritesMenu(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val isFav = vm.path in vm.favorites
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favorites",
                tint = if (isFav) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (vm.path.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (isFav) "Remove this folder from favorites" else "Add this folder to favorites") },
                    leadingIcon = {
                        Icon(Icons.Filled.Star, null, tint = if (isFav) MaterialTheme.colorScheme.onSurfaceVariant else FavoriteGold)
                    },
                    onClick = {
                        open = false
                        vm.toggleFavorite()
                    },
                )
                HorizontalDivider()
            }
            if (vm.favorites.isEmpty()) {
                DropdownMenuItem(text = { Text("No favorites yet") }, onClick = { open = false }, enabled = false)
            } else {
                Text(
                    "Favorites",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                vm.favorites.forEach { fav ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(favoriteName(fav, vm.homePath), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fav, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingIcon = { Text("📁") },
                        onClick = {
                            open = false
                            vm.openFavorite(fav)
                        },
                    )
                }
            }
        }
    }
}

fun favoriteName(path: String, home: String?): String = when {
    path == home -> "Home"
    path == "/" -> "/"
    else -> path.substringAfterLast('/')
}

/** Opened from the cog while browsing. */
@Composable
fun ExtractOptionsDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.showExtractOptions = false },
        title = { Text("Extraction options") },
        text = {
            Column {
                CheckRow("Put files in a new folder named after the archive", vm.intoFolder) { vm.setIntoFolderDefault(it) }
                CheckRow("Delete the archive after it extracts successfully", vm.deleteAfter) { vm.setDeleteAfterDefault(it) }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = {
                    vm.showExtractOptions = false
                    vm.screen = Screen.SETTINGS
                }) { Text("Connection settings & favorites…") }
            }
        },
        confirmButton = { Button(onClick = { vm.showExtractOptions = false }) { Text("Done") } },
    )
}

@Composable
fun NewFolderDialog(vm: MainViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.showNewFolder = false },
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.createFolder(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { vm.createFolder(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = { vm.showNewFolder = false }) { Text("Cancel") } },
    )
}

@Composable
fun ExtractDialog(vm: MainViewModel, list: List<RemoteFile>) {
    val single = list.singleOrNull()
    AlertDialog(
        onDismissRequest = { vm.confirm = null },
        title = { Text(if (single != null) "Extract archive?" else "Extract ${list.size} archives?") },
        text = {
            Column {
                list.take(6).forEach { Text(it.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (list.size > 6) Text("…and ${list.size - 6} more")
                Spacer(Modifier.size(4.dp))
                Text(
                    "Extraction runs on your Whatbox, so it keeps going even if you close the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                val folderLabel = if (single != null) "Put files in a new folder \"${Archives.stem(single.name)}\""
                else "Put each archive's files in its own new folder"
                CheckRow(folderLabel, vm.intoFolder) { vm.intoFolder = it }
                CheckRow("Delete the archive after it extracts successfully", vm.deleteAfter) { vm.deleteAfter = it }
            }
        },
        confirmButton = { Button(onClick = { vm.startExtract(list) }) { Text("Extract") } },
        dismissButton = {
            Row {
                if (single != null) {
                    TextButton(onClick = {
                        vm.confirm = null
                        vm.requestDelete(list)
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { vm.confirm = null }) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun JobDialog(vm: MainViewModel, j: JobState) {
    val title = when {
        !j.finished -> "Extracting…"
        j.success -> "Extracted ✓"
        else -> "Extraction failed"
    }
    AlertDialog(
        onDismissRequest = { if (j.finished) vm.dismissJob() },
        title = { Text(title) },
        text = {
            Column {
                Text(j.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(10.dp))
                if (!j.finished) {
                    val pct = j.percent
                    if (pct != null) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(j.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                if (j.log.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(
                            j.log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier
                                .padding(8.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.dismissJob() }) { Text(if (j.finished) "OK" else "Hide") }
        },
    )
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

// ---------------- Settings ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val c = vm.config
    var host by rememberSaveable { mutableStateOf(c.host) }
    var port by rememberSaveable { mutableStateOf(c.port.toString()) }
    var user by rememberSaveable { mutableStateOf(c.user) }
    var password by rememberSaveable { mutableStateOf(c.password) }
    var useKey by rememberSaveable { mutableStateOf(c.useKey) }
    var key by rememberSaveable { mutableStateOf(c.privateKey) }
    var passphrase by rememberSaveable { mutableStateOf(c.passphrase) }
    var showPw by remember { mutableStateOf(false) }
    val canGoBack = c.isComplete

    BackHandler(enabled = canGoBack) { vm.screen = Screen.BROWSER }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whatbox connection") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { vm.screen = Screen.BROWSER }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Server") },
                placeholder = { Text("yourserver.whatbox.ca") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                label = { Text("SSH port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it.trim() },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useKey, onCheckedChange = { useKey = it })
                Spacer(Modifier.width(12.dp))
                Text("Log in with an SSH key instead of a password")
            }
            if (!useKey) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Private key (paste the whole key)") },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Key passphrase (leave blank if none)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val newCfg = ServerConfig(host, port.toIntOrNull() ?: 22, user, password, useKey, key, passphrase)
            Button(
                onClick = { vm.saveConfig(newCfg) },
                enabled = newCfg.isComplete && !vm.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…")
                } else {
                    Text("Save & connect")
                }
            }
            Text(
                "Your login is stored encrypted on this phone only.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = FavoriteGold)
                Spacer(Modifier.width(8.dp))
                Text("Favorites", style = MaterialTheme.typography.titleSmall)
            }
            if (vm.favorites.isEmpty()) {
                Text(
                    "Tap the ★ in the toolbar while in a folder to add it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            vm.favorites.forEach { fav ->
                ListItem(
                    headlineContent = { Text(favoriteName(fav, vm.homePath), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(fav, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text("📁", fontSize = 22.sp) },
                    trailingContent = {
                        IconButton(onClick = { vm.removeFavorite(fav) }) { Icon(Icons.Filled.Close, "Remove favorite") }
                    },
                    modifier = Modifier.clickable(enabled = c.isComplete) { vm.openFavorite(fav) },
                )
            }

            HorizontalDivider()
            Text("Extraction defaults", style = MaterialTheme.typography.titleSmall)
            CheckRow("Put files in a new folder named after the archive", vm.intoFolder) { vm.setIntoFolderDefault(it) }
            CheckRow("Delete the archive after it extracts successfully", vm.deleteAfter) { vm.setDeleteAfterDefault(it) }

            HorizontalDivider()
            Text("App version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }) { Text("Check for updates") }
            vm.update?.let { UpdateBanner(vm, it) }
            TextButton(onClick = { vm.resetHostKey() }) { Text("Reset saved server fingerprint") }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}
