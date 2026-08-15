package com.xa.sharebox.data

import android.content.Context
import android.content.SharedPreferences
import com.xa.sharebox.model.FtpServerConfig
import com.xa.sharebox.model.ServerConfig
import com.xa.sharebox.model.ServerType
import com.xa.sharebox.util.CryptoUtils
import org.json.JSONArray
import org.json.JSONObject

/** Persists server configs and FTP server settings in SharedPreferences. */
class ServerStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sharebox", Context.MODE_PRIVATE)

    fun getServers(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<ServerConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ServerConfig(
                    name = o.getString("name"),
                    type = if (o.getString("type") == "FTP") ServerType.FTP else ServerType.SMB,
                    host = o.getString("host"),
                    port = o.optInt("port", if (o.getString("type") == "FTP") 21 else 445),
                    username = o.optString("username", ""),
                    password = CryptoUtils.decrypt(o.optString("password", "")),
                    share = o.optString("share", "")
                )
            )
        }
        return list
    }

    fun saveServers(list: List<ServerConfig>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("type", s.type.name)
                put("host", s.host)
                put("port", s.port)
                put("username", s.username)
                put("password", CryptoUtils.encrypt(s.password))
                put("share", s.share)
            })
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun addServer(config: ServerConfig) {
        val list = getServers().toMutableList()
        list.add(config)
        saveServers(list)
    }

    fun removeServer(index: Int) {
        val list = getServers().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveServers(list)
        }
    }

    /** Remove a server by matching its config fields (not by index). */
    fun removeServer(config: ServerConfig) {
        val list = getServers().toMutableList()
        val removed = list.removeAll {
            it.name == config.name && it.type == config.type &&
            it.host == config.host && it.port == config.port &&
            it.share == config.share
        }
        if (removed) saveServers(list)
    }

    fun getFtpServerConfig(): FtpServerConfig {
        return FtpServerConfig(
            port = prefs.getInt(KEY_FTP_PORT, 2211),
            username = prefs.getString(KEY_FTP_USER, "share") ?: "share",
            password = CryptoUtils.decrypt(prefs.getString(KEY_FTP_PASS, "") ?: ""),
            sharedPath = prefs.getString(KEY_FTP_PATH, "/storage/emulated/0") ?: "/storage/emulated/0"
        )
    }

    fun saveFtpServerConfig(config: FtpServerConfig) {
        prefs.edit()
            .putInt(KEY_FTP_PORT, config.port)
            .putString(KEY_FTP_USER, config.username)
            .putString(KEY_FTP_PASS, CryptoUtils.encrypt(config.password))
            .putString(KEY_FTP_PATH, config.sharedPath)
            .apply()
    }

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_FTP_PORT = "ftp_port"
        private const val KEY_FTP_USER = "ftp_user"
        private const val KEY_FTP_PASS = "ftp_pass"
        private const val KEY_FTP_PATH = "ftp_path"
    }
}
