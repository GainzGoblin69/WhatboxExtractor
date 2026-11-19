package com.gainzgoblin.whatboxextractor

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

data class RemoteFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

data class ExecResult(val code: Int, val out: String, val err: String)

/** One SSH connection to the seedbox, used for both SFTP browsing and running commands. */
class SshClient(context: Context) {
    private val knownHosts = File(context.filesDir, "known_hosts")
    private val mutex = Mutex()
    private var session: Session? = null
    private var sftp: ChannelSftp? = null
    private var connectedConfig: ServerConfig? = null

    var home: String = "/"
        private set

    suspend fun <T> use(cfg: ServerConfig, block: (ChannelSftp) -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { withConnection(cfg, block) } }

    suspend fun list(cfg: ServerConfig, path: String): Pair<String, List<RemoteFile>> = use(cfg) { ch ->
        val real = ch.realpath(path.ifBlank { ch.home })
        val entries = ch.ls(real).filterIsInstance<ChannelSftp.LsEntry>()
            .filter { it.filename != "." && it.filename != ".." }
            .map { e ->
                val full = joinPath(real, e.filename)
                val a = e.attrs
                var isDir = a.isDir
                var size = a.size
                if (a.isLink) {
                    try {
                        val target = ch.stat(full)
                        isDir = target.isDir
                        size = target.size
                    } catch (_: Exception) {
                    }
                }
                RemoteFile(e.filename, full, isDir, size, a.mTime.toLong() * 1000L)
            }
            .sortedWith(compareBy<RemoteFile>({ !it.isDir }, { it.name.lowercase() }))
        real to entries
    }

    suspend fun exec(cfg: ServerConfig, command: String): ExecResult = use(cfg) { execInternal(command) }

    suspend fun writeFile(cfg: ServerConfig, path: String, content: String) = use(cfg) { ch ->
        ch.put(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), path)
    }

    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { disconnectInternal() } }

    fun forgetHostKeys() {
        knownHosts.delete()
    }

    private fun <T> withConnection(cfg: ServerConfig, block: (ChannelSftp) -> T): T {
        val hadConnection = session != null
        return try {
            block(connect(cfg))
        } catch (e: Exception) {
            val alive = session?.isConnected == true && sftp?.isConnected == true
            if (alive || !hadConnection) throw e
            // The connection dropped (phone slept, network changed...). Reconnect once and retry.
            disconnectInternal()
            block(connect(cfg))
        }
    }

    private fun connect(cfg: ServerConfig): ChannelSftp {
        val s = session
        val c = sftp
        if (s != null && s.isConnected && c != null && c.isConnected && !c.isClosed && cfg == connectedConfig) {
            return c
        }
        disconnectInternal()
        if (!knownHosts.exists()) knownHosts.createNewFile()

        val jsch = JSch()
        jsch.setKnownHosts(knownHosts.absolutePath)
        if (cfg.useKey) {
            val pass = cfg.passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
            jsch.addIdentity("whatbox-key", (cfg.privateKey.trim() + "\n").toByteArray(), null, pass)
        }
        val ns = jsch.getSession(cfg.user.trim(), cfg.host.trim(), cfg.port)
        if (!cfg.useKey) ns.setPassword(cfg.password)
        ns.userInfo = Prompter(cfg)
        // "ask" + Prompter = trust the server the first time, refuse if its key later changes.
        ns.setConfig("StrictHostKeyChecking", "ask")
        ns.setServerAliveInterval(15_000)
        ns.setServerAliveCountMax(4)
        ns.connect(20_000)

        val ch = ns.openChannel("sftp") as ChannelSftp
        ch.connect(20_000)
        session = ns
        sftp = ch
        connectedConfig = cfg
        home = ch.home
        return ch
    }

    private fun execInternal(command: String): ExecResult {
        val s = session ?: throw IllegalStateException("Not connected")
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val err = ByteArrayOutputStream()
        ch.setErrStream(err, true)
        val input = ch.inputStream
        ch.connect(20_000)
        val out = input.readBytes()
        var waited = 0
        while (!ch.isClosed && waited < 10_000) {
            Thread.sleep(50)
            waited += 50
        }
        val code = ch.exitStatus
        ch.disconnect()
        return ExecResult(code, String(out, Charsets.UTF_8), err.toString("UTF-8"))
    }

    private fun disconnectInternal() {
        try { sftp?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        sftp = null
        session = null
        connectedConfig = null
    }

    private class Prompter(private val cfg: ServerConfig) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = cfg.passphrase.ifEmpty { null }
        override fun getPassword(): String? = cfg.password
        override fun promptPassword(message: String?): Boolean = !cfg.useKey && cfg.password.isNotEmpty()
        override fun promptPassphrase(message: String?): Boolean = cfg.passphrase.isNotEmpty()
        override fun promptYesNo(message: String?): Boolean {
            val m = message ?: return false
            // Never silently accept a server whose fingerprint changed.
            if (m.contains("CHANGED", ignoreCase = true) || m.contains("NASTY", ignoreCase = true)) return false
            return true
        }
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (cfg.useKey || cfg.password.isEmpty()) return null
            return Array(prompt?.size ?: 0) { cfg.password }
        }
    }

    companion object {
        fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
    }
}

fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun friendlyError(e: Throwable): String {
    val msg = e.message ?: e.javaClass.simpleName
    return when {
        msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
            "Login failed. Check your username and password (or key)."
        msg.contains("UnknownHost", true) -> "Can't find that server. Check the server name."
        msg.contains("HostKey", true) || msg.contains("reject", true) ->
            "The server's fingerprint changed. If you expected that, reset it in Settings."
        msg.contains("timeout", true) || msg.contains("timed out", true) -> "Connection timed out."
        msg.contains("No such file", true) -> "That folder doesn't exist."
        msg.contains("Permission denied", true) -> "Permission denied."
        else -> msg
    }
}
