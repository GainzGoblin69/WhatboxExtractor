package com.gainzgoblin.whatboxextractor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ServerConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val password: String = "",
    val useKey: Boolean = false,
    val privateKey: String = "",
    val passphrase: String = "",
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && user.isNotBlank() &&
            (if (useKey) privateKey.isNotBlank() else password.isNotEmpty())
}

/** Settings live in encrypted storage on the phone only. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = open(context)

    private fun open(context: Context): SharedPreferences {
        fun create(): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try {
            create()
        } catch (e: Exception) {
            context.deleteSharedPreferences("secure_settings")
            try {
                create()
            } catch (e2: Exception) {
                context.getSharedPreferences("settings_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    fun loadConfig() = ServerConfig(
        host = sp.getString("host", "") ?: "",
        port = sp.getInt("port", 22),
        user = sp.getString("user", "") ?: "",
        password = sp.getString("password", "") ?: "",
        useKey = sp.getBoolean("use_key", false),
        privateKey = sp.getString("private_key", "") ?: "",
        passphrase = sp.getString("passphrase", "") ?: "",
    )

    fun saveConfig(c: ServerConfig) {
        sp.edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putString("user", c.user)
            .putString("password", c.password)
            .putBoolean("use_key", c.useKey)
            .putString("private_key", c.privateKey)
            .putString("passphrase", c.passphrase)
            .apply()
    }

    var lastPath: String
        get() = sp.getString("last_path", "") ?: ""
        set(v) = sp.edit().putString("last_path", v).apply()

    var intoFolder: Boolean
        get() = sp.getBoolean("into_folder", false)
        set(v) = sp.edit().putBoolean("into_folder", v).apply()

    var deleteAfter: Boolean
        get() = sp.getBoolean("delete_after", false)
        set(v) = sp.edit().putBoolean("delete_after", v).apply()

    /** Favorite folders, in the order they were added. */
    var favorites: List<String>
        get() = (sp.getString("favorites", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("favorites", v.joinToString("\n")).apply()

    /** Extraction that was running when the app was closed: "jobDir\nfolder\nfileName". */
    var activeJob: String?
        get() = sp.getString("active_job", null)
        set(v) = sp.edit().putString("active_job", v).apply()
}
