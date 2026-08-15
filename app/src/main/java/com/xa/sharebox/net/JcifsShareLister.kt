package com.xa.sharebox.net

import java.io.File

/**
 * SMB1 share enumeration using jcifs (RAP protocol).
 *
 * jcifs 1.3.x is SMB1-only and uses RAP (Remote Access Protocol) via
 * \PIPE\LANMAN for share enumeration. This works on cheap routers that
 * don't support DCERPC SRVSVC (BindNak) or SMB2 named pipe transact.
 *
 * Used as a fallback when smbj's DCERPC and RAP via SMB2 both fail.
 * smbj handles SMB2 file operations; jcifs handles SMB1 share discovery.
 */
object JcifsShareLister {

    data class ShareInfo(
        val name: String,
        val type: Int,
        val comment: String
    )

    private val logFile = File("/storage/emulated/0/Download/smb_debug.log")
    private const val MAX_LOG_SIZE = 1_048_576L // 1 MB

    private fun log(msg: String) {
        try {
            logFile.parentFile?.mkdirs()
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                logFile.writeText("")  // Truncate oversized log
            }
            val ts = System.currentTimeMillis()
            logFile.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
    }

    /**
     * Enumerate shares via SMB1 RAP using jcifs.
     * Returns null on failure, or list of shares on success.
     */
    fun listShares(
        host: String,
        port: Int = 445,
        username: String,
        password: String
    ): List<ShareInfo>? {
        log("[jcifs] ======== listShares host=$host port=$port user='$username' ========")
        try {
            // Configure jcifs for SMB1 anonymous access
            val props = System.getProperties()
            props.setProperty("jcifs.smb.client.useUnicode", "true")
            props.setProperty("jcifs.netbios.wins", "")
            props.setProperty("jcifs.smb.client.soTimeout", "15000")
            props.setProperty("jcifs.smb.client.responseTimeout", "15000")
            props.setProperty("jcifs.smb.lport", "0")
            // Disable jcifs NetBIOS name resolution — we use IP directly
            props.setProperty("jcifs.netbios.lport", "0")

            log("[jcifs] Creating auth context")

            // Create authentication context
            // Empty domain, empty user/password = anonymous
            val auth = if (username.isBlank() && password.isBlank()) {
                jcifs.smb.NtlmPasswordAuthentication(
                    "",  // domain
                    "GUEST",  // some servers need a non-empty username for anonymous
                    ""  // password
                )
            } else {
                jcifs.smb.NtlmPasswordAuthentication(
                    "",  // domain
                    username,
                    password
                )
            }
            log("[jcifs] Auth context created: user='${auth.username}'")

            // Create SmbFile pointing to the server root
            // smb://host/ → lists shares via RAP
            val url = "smb://$host/"
            log("[jcifs] Connecting to $url")
            val smbFile = jcifs.smb.SmbFile(url, auth)

            log("[jcifs] Calling listFiles() (triggers SMB1 negotiate + RAP)...")
            val files = smbFile.listFiles()
            log("[jcifs] Got ${files.size} entries")

            val shares = mutableListOf<ShareInfo>()
            for (f in files) {
                val name = f.name.trimEnd('/')
                log("[jcifs] Entry: name='$name' type=${f.getType()}")
                if (name.isNotEmpty() && name != "IPC$") {
                    shares.add(ShareInfo(name, 0, ""))
                }
            }

            log("[jcifs] Parsed ${shares.size} shares: ${shares.joinToString { it.name }}")
            return shares

        } catch (e: Exception) {
            log("[jcifs] FAILED: ${e.javaClass.simpleName}: ${e.message}")
            // Log stack trace for debugging
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                log("[jcifs] Stack: ${sw.toString().take(500)}")
            } catch (_: Exception) {}
            return null
        }
    }
}
