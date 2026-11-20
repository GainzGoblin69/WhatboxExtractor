package com.gainzgoblin.whatboxextractor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { SETTINGS, BROWSER }

data class JobState(
    val name: String,
    val dir: String,
    val jobDir: String,
    val log: String = "",
    val percent: Int? = null,
    val status: String = "Starting…",
    val finished: Boolean = false,
    val success: Boolean = false,
    val hidden: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val ssh = SshClient(app)

    var config by mutableStateOf(prefs.loadConfig())
        private set
    var screen by mutableStateOf(if (config.isComplete) Screen.BROWSER else Screen.SETTINGS)
    var path by mutableStateOf(prefs.lastPath)
        private set
    var homePath by mutableStateOf<String?>(null)
        private set
    var files by mutableStateOf<List<RemoteFile>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var confirm by mutableStateOf<List<RemoteFile>?>(null)
    var pendingDelete by mutableStateOf<List<RemoteFile>?>(null)
        private set
    var torrentMatches by mutableStateOf<List<TorrentMatch>>(emptyList())
        private set
    var torrentChecking by mutableStateOf(false)
        private set
    var torrentError by mutableStateOf<String?>(null)
        private set
    var removeTorrents by mutableStateOf(true)
    private var lookupJob: Job? = null
    var deleting by mutableStateOf(false)
        private set
    /** Selected items by path. Kept while browsing other folders so they can be moved. */
    var selected by mutableStateOf<Map<String, RemoteFile>>(emptyMap())
        private set
    var moving by mutableStateOf(false)
        private set
    var job by mutableStateOf<JobState?>(null)
        private set
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var favorites by mutableStateOf(prefs.favorites)
        private set
    var showExtractOptions by mutableStateOf(false)
    var showNewFolder by mutableStateOf(false)
    var intoFolder by mutableStateOf(prefs.intoFolder)
    var deleteAfter by mutableStateOf(prefs.deleteAfter)

    private var loadJob: Job? = null
    private var pollJob: Job? = null

    init {
        if (config.isComplete) {
            refresh()
            resumeJob()
        }
        checkForUpdate(manual = false)
    }

    // ---------- Browsing ----------

    fun refresh() = load(path)

    fun load(target: String) {
        val cfg = config
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                val (real, list) = ssh.list(cfg, target)
                path = real
                files = list
                homePath = ssh.home
                prefs.lastPath = real
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                loading = false
            }
        }
    }

    fun goUp() {
        val p = path
        if (p.isEmpty() || p == "/") return
        load(p.substringBeforeLast('/').ifEmpty { "/" })
    }

    fun open(f: RemoteFile) {
        when {
            f.isDir -> load(f.path)
            selected.isNotEmpty() -> toggleSelect(f)
            Archives.kind(f.name) != null -> confirm = listOf(f)
            else -> requestDelete(listOf(f))
        }
    }

    // ---------- Selection & delete ----------

    val selectedFiles: List<RemoteFile>
        get() = selected.values.toList()

    val allHereSelected: Boolean
        get() = files.isNotEmpty() && files.all { it.path in selected }

    /** Selected items that can be moved into the folder being viewed. */
    val movableHere: List<RemoteFile>
        get() = selected.values.filter { canMoveInto(it, path) }

    private fun canMoveInto(f: RemoteFile, dest: String): Boolean =
        dest.isNotEmpty() && parentOf(f.path) != dest && dest != f.path && !dest.startsWith(f.path + "/")

    fun toggleSelect(f: RemoteFile) {
        selected = if (f.path in selected) selected - f.path else selected + (f.path to f)
    }

    fun selectAll() {
        selected = if (allHereSelected) selected - files.map { it.path }.toSet()
        else selected + files.associateBy { it.path }
    }

    fun clearSelection() {
        selected = emptyMap()
    }

    fun extractSelected() {
        val archives = selectedFiles.filter { !it.isDir && Archives.kind(it.name) != null && parentOf(it.path) == path }
        if (archives.isEmpty()) {
            message = "Open the folder with the selected archives to extract them."
            return
        }
        confirm = archives
    }

    fun deleteSelected() {
        val items = selectedFiles
        if (items.isNotEmpty()) requestDelete(items)
    }

    /** Opens the delete box and checks in the background whether rTorrent is seeding any of it. */
    fun requestDelete(items: List<RemoteFile>) {
        pendingDelete = items
        torrentMatches = emptyList()
        torrentError = null
        removeTorrents = true
        val cfg = config
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            torrentChecking = true
            try {
                val home = ssh.home
                ssh.exec(cfg, "mkdir -p ${shQuote(home.trimEnd('/') + "/.whatbox-extractor")}")
                ssh.writeFile(cfg, Torrents.helperPath(home), Torrents.HELPER_SCRIPT)
                val r = ssh.exec(cfg, Torrents.findCommand(home, items.map { it.path }))
                torrentMatches = Torrents.parse(r.out, r.err)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                torrentError = e.message ?: "Couldn't check rTorrent"
            } finally {
                torrentChecking = false
            }
        }
    }

    fun cancelDelete() {
        lookupJob?.cancel()
        torrentChecking = false
        pendingDelete = null
    }

    fun confirmDelete() {
        val items = pendingDelete ?: return
        val cfg = config
        val dir = path
        viewModelScope.launch {
            deleting = true
            try {
                // Never delete the root or home folder itself.
                val home = ssh.home.trimEnd('/')
                val targets = items.filter { it.path.count { c -> c == '/' } >= 2 && it.path.trimEnd('/') != home }
                if (targets.isEmpty()) return@launch
                var torrentNote = ""
                val torrents = torrentMatches
                if (removeTorrents && torrents.isNotEmpty()) {
                    // Remove from rTorrent first so it doesn't complain about missing files.
                    try {
                        val r = ssh.exec(cfg, Torrents.removeCommand(ssh.home, torrents.map { it.hash }))
                        Torrents.parse(r.out, r.err)
                        torrentNote = if (torrents.size == 1) " and removed the torrent" else " and removed ${torrents.size} torrents"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        torrentNote = " (couldn't remove torrent: ${e.message})"
                    }
                }
                val cmd = "rm -rf -- " + targets.joinToString(" ") { shQuote(it.path) }
                val r = ssh.exec(cfg, cmd)
                if (r.code != 0) throw IllegalStateException(r.err.trim().ifBlank { "rm failed (code ${r.code})" })
                message = (if (targets.size == 1) "Deleted ${targets[0].name}" else "Deleted ${targets.size} items") + torrentNote
                prefs.favorites.let { favs ->
                    val gone = favs.filter { fav -> targets.any { t -> fav == t.path || fav.startsWith(t.path + "/") } }
                    if (gone.isNotEmpty()) {
                        favorites = favs - gone.toSet()
                        prefs.favorites = favorites
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Delete failed: ${friendlyError(e)}"
            } finally {
                deleting = false
                pendingDelete = null
                selected = selected - items.map { it.path }.toSet()
                if (path == dir) load(dir)
            }
        }
    }

    fun moveHere() {
        val dest = path
        val all = movableHere
        if (all.isEmpty() || moving) return
        val existing = files.map { it.name }.toSet()
        val conflicts = all.filter { it.name in existing }
        val items = all.filter { it.name !in existing }
        if (items.isEmpty()) {
            message = "Something with the same name is already in this folder."
            return
        }
        val cfg = config
        viewModelScope.launch {
            moving = true
            try {
                // Find torrents seeding these files so rTorrent can be pointed at the new location.
                val home = ssh.home
                var torrents: List<TorrentMatch> = emptyList()
                try {
                    ssh.exec(cfg, "mkdir -p ${shQuote(home.trimEnd('/') + "/.whatbox-extractor")}")
                    ssh.writeFile(cfg, Torrents.helperPath(home), Torrents.HELPER_SCRIPT)
                    val r = ssh.exec(cfg, Torrents.findCommand(home, items.map { it.path }))
                    torrents = Torrents.parse(r.out, r.err)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }

                val cmd = "mv -n -- " + items.joinToString(" ") { shQuote(it.path) } + " " + shQuote(dest.trimEnd('/') + "/")
                val r = ssh.exec(cfg, cmd)
                if (r.code != 0) throw IllegalStateException(r.err.trim().ifBlank { "mv failed (code ${r.code})" })

                var torrentNote = ""
                val moves = torrents.mapNotNull { t ->
                    val item = items.firstOrNull { t.path == it.path || t.path.startsWith(it.path + "/") }
                        ?: return@mapNotNull null
                    t.hash to (dest.trimEnd('/') + "/" + item.name + t.path.removePrefix(item.path))
                }
                if (moves.isNotEmpty()) {
                    try {
                        val rr = ssh.exec(cfg, Torrents.relocateCommand(home, moves))
                        Torrents.parse(rr.out, rr.err)
                        torrentNote = if (moves.size == 1) " (rTorrent updated to keep seeding)" else " (${moves.size} torrents updated to keep seeding)"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        torrentNote = " (couldn't update rTorrent: ${e.message})"
                    }
                }

                // Keep favorites pointing at moved folders.
                val favs = favorites
                val updatedFavs = favs.map { fav ->
                    val item = items.firstOrNull { fav == it.path || fav.startsWith(it.path + "/") }
                    if (item == null) fav else dest.trimEnd('/') + "/" + item.name + fav.removePrefix(item.path)
                }
                if (updatedFavs != favs) {
                    favorites = updatedFavs
                    prefs.favorites = updatedFavs
                }

                selected = selected - items.map { it.path }.toSet()
                val moved = if (items.size == 1) "Moved ${items[0].name}" else "Moved ${items.size} items"
                val skipped = if (conflicts.isEmpty()) "" else ", skipped ${conflicts.size} (name already here)"
                message = moved + skipped + torrentNote
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Move failed: ${friendlyError(e)}"
            } finally {
                moving = false
                if (path == dest) load(dest)
            }
        }
    }

    fun createFolder(name: String) {
        val n = name.trim()
        when {
            n.isEmpty() -> { message = "Enter a folder name."; return }
            n.contains('/') || n == "." || n == ".." -> { message = "Folder names can't contain \"/\"."; return }
            files.any { it.name == n } -> { message = "\"$n\" already exists here."; return }
        }
        showNewFolder = false
        val cfg = config
        val dir = path
        viewModelScope.launch {
            try {
                ssh.use(cfg) { ch -> ch.mkdir(SshClient.joinPath(dir, n)) }
                message = "Created folder \"$n\""
                if (path == dir) load(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Couldn't create folder: ${friendlyError(e)}"
            }
        }
    }

    // ---------- Favorites ----------

    fun toggleFavorite() {
        val p = path
        if (p.isEmpty()) return
        if (p in favorites) {
            favorites = favorites - p
            message = "Removed from favorites"
        } else {
            favorites = favorites + p
            message = "Added to favorites"
        }
        prefs.favorites = favorites
    }

    fun removeFavorite(p: String) {
        favorites = favorites - p
        prefs.favorites = favorites
    }

    fun openFavorite(p: String) {
        screen = Screen.BROWSER
        load(p)
    }

    // ---------- Settings ----------

    fun saveConfig(newCfg: ServerConfig) {
        viewModelScope.launch {
            connecting = true
            try {
                ssh.close()
                val (real, list) = ssh.list(newCfg, "")
                prefs.saveConfig(newCfg)
                config = newCfg
                path = real
                files = list
                homePath = ssh.home
                error = null
                prefs.lastPath = real
                screen = Screen.BROWSER
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = friendlyError(e)
            } finally {
                connecting = false
            }
        }
    }

    fun setIntoFolderDefault(v: Boolean) {
        intoFolder = v
        prefs.intoFolder = v
    }

    fun setDeleteAfterDefault(v: Boolean) {
        deleteAfter = v
        prefs.deleteAfter = v
    }

    fun resetHostKey() {
        viewModelScope.launch {
            ssh.close()
            ssh.forgetHostKeys()
            message = "Server fingerprint cleared. It will be saved again on the next connection."
        }
    }

    // ---------- Extraction ----------

    fun startExtract(list: List<RemoteFile>) {
        confirm = null
        clearSelection()
        if (job?.finished == false) {
            message = "Wait for the current extraction to finish."
            return
        }
        val names = list.map { it.name }.filter { Archives.kind(it) != null }
        if (names.isEmpty()) return
        val label = if (names.size == 1) names[0] else "${names.size} archives"
        prefs.intoFolder = intoFolder
        prefs.deleteAfter = deleteAfter
        val cfg = config
        val dir = path
        val into = intoFolder
        val delete = deleteAfter
        job = JobState(label, dir, "")
        viewModelScope.launch {
            try {
                // Connect first so we know the home folder.
                ssh.list(cfg, dir)
                val jobDir = ssh.home.trimEnd('/') + "/.whatbox-extractor/jobs/" + System.currentTimeMillis()
                val script = Archives.buildScript(dir, names, into, delete, jobDir)
                ssh.exec(cfg, "mkdir -p ${shQuote(jobDir)}")
                ssh.writeFile(cfg, "$jobDir/run.sh", script)
                val start = ssh.exec(cfg, "cd ${shQuote(jobDir)} && nohup sh run.sh > log 2>&1 < /dev/null &")
                if (start.code != 0) throw IllegalStateException(start.err.ifBlank { "Couldn't start extraction" })
                prefs.activeJob = listOf(jobDir, dir, label).joinToString("\n")
                job = job?.copy(jobDir = jobDir, status = "Extracting…")
                poll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job = job?.copy(finished = true, success = false, status = "Couldn't start", log = friendlyError(e), hidden = false)
            }
        }
    }

    private fun resumeJob() {
        val parts = prefs.activeJob?.split("\n") ?: return
        if (parts.size != 3) {
            prefs.activeJob = null
            return
        }
        job = JobState(name = parts[2], dir = parts[1], jobDir = parts[0], status = "Checking…")
        poll()
    }

    private fun poll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                val j = job ?: break
                if (j.finished) break
                try {
                    val r = ssh.exec(config, Archives.pollCommand(j.jobDir))
                    failures = 0
                    if (r.out.contains("@@GONE@@")) {
                        finishJob(null, "Lost track of this extraction. Refresh the folder to see if it worked.")
                        break
                    }
                    val raw = r.out.substringBefore("@@EXIT@@")
                    val exit = r.out.substringAfter("@@EXIT@@", "").trim().toIntOrNull()
                    job = job?.copy(log = Archives.cleanLog(raw), percent = Archives.lastPercent(raw), status = "Extracting…")
                    if (exit != null) {
                        finishJob(exit, null)
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    job = job?.copy(status = "Connection lost, retrying… (the server keeps extracting)")
                }
                delay(if (failures > 0) 3000L else 1000L)
            }
        }
    }

    private suspend fun finishJob(exit: Int?, note: String?) {
        val j = job ?: return
        val ok = exit == 0
        job = j.copy(
            finished = true,
            success = ok,
            percent = if (ok) 100 else j.percent,
            status = if (ok) "Done!" else "Extraction failed",
            log = if (note != null) note else j.log,
            hidden = false,
        )
        prefs.activeJob = null
        if (j.jobDir.contains("/.whatbox-extractor/jobs/")) {
            try { ssh.exec(config, "rm -rf ${shQuote(j.jobDir)}") } catch (_: Exception) {}
        }
        if (j.dir == path) load(path)
    }

    fun dismissJob() {
        val j = job ?: return
        job = if (j.finished) null else j.copy(hidden = true)
    }

    fun showJob() {
        job = job?.copy(hidden = false)
    }

    // ---------- Updates ----------

    fun checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            try {
                val u = Updater.check()
                update = u
                if (manual && u == null) message = "You're on the latest version (${BuildConfig.VERSION_NAME})."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) message = "Couldn't check for updates: ${e.message}"
            }
        }
    }

    fun installUpdate() {
        val u = update ?: return
        if (updateProgress != null) return
        viewModelScope.launch {
            updateProgress = 0f
            try {
                val file = Updater.download(getApplication(), u) { p -> updateProgress = p }
                if (!Updater.install(getApplication(), file)) {
                    message = "Allow installs from Whatbox Extractor, then tap Install again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Update download failed: ${e.message}"
            } finally {
                updateProgress = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't block; the connection is dropped with the process anyway.
    }
}

fun parentOf(p: String): String = p.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
