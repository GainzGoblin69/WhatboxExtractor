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
    var confirm by mutableStateOf<RemoteFile?>(null)
    var job by mutableStateOf<JobState?>(null)
        private set
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
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
            Archives.kind(f.name) != null -> confirm = f
            Archives.isLaterPart(f.name) ->
                message = "That's one piece of a multi-part archive. Tap the .part1.rar file to extract all parts."
            else -> message = "Only .zip, .rar and .7z files can be extracted."
        }
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

    fun startExtract(f: RemoteFile) {
        confirm = null
        if (job?.finished == false) {
            message = "Wait for the current extraction to finish."
            return
        }
        val kind = Archives.kind(f.name) ?: return
        prefs.intoFolder = intoFolder
        prefs.deleteAfter = deleteAfter
        val cfg = config
        val dir = path
        val into = intoFolder
        val delete = deleteAfter
        job = JobState(f.name, dir, "")
        viewModelScope.launch {
            try {
                // Connect first so we know the home folder.
                ssh.list(cfg, dir)
                val jobDir = ssh.home.trimEnd('/') + "/.whatbox-extractor/jobs/" + System.currentTimeMillis()
                val script = Archives.buildScript(dir, f.name, kind, into, delete, jobDir)
                ssh.exec(cfg, "mkdir -p ${shQuote(jobDir)}")
                ssh.writeFile(cfg, "$jobDir/run.sh", script)
                val start = ssh.exec(cfg, "cd ${shQuote(jobDir)} && nohup sh run.sh > log 2>&1 < /dev/null &")
                if (start.code != 0) throw IllegalStateException(start.err.ifBlank { "Couldn't start extraction" })
                prefs.activeJob = listOf(jobDir, dir, f.name).joinToString("\n")
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
