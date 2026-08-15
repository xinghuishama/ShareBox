package com.xa.sharebox.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.xa.sharebox.MainActivity
import com.xa.sharebox.model.FtpServerConfig
import com.xa.sharebox.util.FileUtils
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.Ftplet
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FtpServerService : Service() {
    private var ftpServer: FtpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = FtpServerConfig(
                    port = intent.getIntExtra(EXTRA_PORT, 2211),
                    username = intent.getStringExtra(EXTRA_USER) ?: "share",
                    password = intent.getStringExtra(EXTRA_PASS) ?: "1234",
                    sharedPath = intent.getStringExtra(EXTRA_PATH) ?: "/storage/emulated/0"
                )
                val notification = createNotification(config, "启动中...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                Thread { startFtpServer(config) }.start()
            }
            ACTION_STOP -> {
                stopFtpServer()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startFtpServer(config: FtpServerConfig) {
        try {
            logToFile("======== START port=${config.port} user=${config.username} path=${config.sharedPath} ========")

            val sharedDir = File(config.sharedPath)
            if (!sharedDir.exists()) {
                sharedDir.mkdirs()
                logToFile("Created shared dir: ${sharedDir.absolutePath}")
            }

            logToFile("step1: FtpServerFactory")
            val serverFactory = FtpServerFactory()

            logToFile("step2: ListenerFactory port=${config.port}")
            val listenerFactory = ListenerFactory()
            listenerFactory.port = config.port
            listenerFactory.idleTimeout = 300
            serverFactory.addListener("default", listenerFactory.createListener())
            logToFile("step2: listener OK")

            logToFile("step3: InMemoryUserManager")
            val userManager = InMemoryUserManager()
            // Use anonymous subclass to override getMaxConcurrentLogins() (default 0 = no logins allowed)
            val user = object : BaseUser() {}
            user.name = config.username
            user.password = config.password
            user.homeDirectory = config.sharedPath
            user.enabled = true
            user.authorities = listOf(
                WritePermission(),
                org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission(100, 100)
            )
            user.maxIdleTime = 300
            userManager.save(user)
            logToFile("step3: user saved: ${user.name} home=${user.homeDirectory}")

            if (config.password.isEmpty()) {
                val anon = object : BaseUser() {}
                anon.name = "anonymous"
                anon.password = ""
                anon.homeDirectory = config.sharedPath
                anon.enabled = true
                anon.authorities = listOf(
                    WritePermission(),
                    org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission(100, 100)
                )
                userManager.save(anon)
                logToFile("step3: anonymous user saved")
            }

            serverFactory.userManager = userManager

            logToFile("step4: DebugFtplet")
            val ftplets = HashMap<String, Ftplet>()
            ftplets["debug"] = DebugFtplet()
            serverFactory.ftplets = ftplets
            logToFile("step4: ftplet OK")

            logToFile("step5: createServer")
            ftpServer = serverFactory.createServer()

            logToFile("step6: start()")
            ftpServer?.start()
            logToFile("step6: start() returned, no exception")

            // Self-test: check 220 banner
            Thread {
                try {
                    Thread.sleep(1000)
                    logToFile("SELFCHECK: connecting to 127.0.0.1:${config.port}...")
                    val socket = Socket()
                    socket.connect(InetSocketAddress("127.0.0.1", config.port), 3000)
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(512)
                    val read = input.read(buffer)
                    if (read > 0) {
                        val banner = String(buffer, 0, read).trim()
                        logToFile("SELFCHECK: banner='$banner'")
                    } else {
                        logToFile("SELFCHECK: NO DATA (0 bytes)")
                    }
                    socket.close()
                } catch (e: Exception) {
                    logToFile("SELFCHECK FAILED: ${e.javaClass.simpleName}: ${e.message}")
                }
            }.start()

            logToFile("======== FTP SERVER STARTED port=${config.port} ========")
            updateNotification(config, "运行中")
            isRunning = true
        } catch (e: Throwable) {
            logToFile("!!! START FAILED !!!")
            logToFile("${e.javaClass.name}: ${e.message}")
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                logToFile("STACKTRACE:\n$sw")
            } catch (_: Exception) {}
            updateNotification(config, "启动失败: ${e.message}")
            stopSelf()
        }
    }

    private fun stopFtpServer() {
        logToFile("======== STOP ========")
        isRunning = false
        try {
            ftpServer?.stop()
            logToFile("server.stop() OK")
        } catch (e: Throwable) {
            logToFile("stop error: ${e.message}")
        }
        ftpServer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotification(config: FtpServerConfig, status: String): Notification {
        val channelName = "FTP Server"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }

        val ips = FileUtils.getLocalIpAddresses()
        val ipText = if (ips.isNotEmpty()) ips.joinToString(", ") else "unknown"
        val urlText = "ftp://$ipText:${config.port} - $status"

        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("tab", 3)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, FtpServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("ShareBox FTP")
            .setContentText(urlText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)

        return builder.build()
    }

    private fun updateNotification(config: FtpServerConfig, status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, createNotification(config, status))
    }

    override fun onDestroy() {
        stopFtpServer()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FtpServerService"

        /** Reflects whether the FTP server is actually running (not just started). */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "ftp_server"

        const val ACTION_START = "com.xa.sharebox.START_FTP"
        const val ACTION_STOP = "com.xa.sharebox.STOP_FTP"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_PATH = "path"

        private val logFile: File by lazy {
            File(Environment.getExternalStorageDirectory(), "Download/ftp_debug.log")
        }

        @Volatile
        private var stderrRedirected = false

        init {
            // Configure slf4j-simple to write to our log file
            System.setProperty("org.slf4j.simpleLogger.logFile", "/storage/emulated/0/Download/ftp_slf4j.log")
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
        }

        private const val MAX_LOG_SIZE = 1_048_576L // 1 MB

        fun logToFile(msg: String) {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$ts] $msg"
            Log.i(TAG, line)
            try {
                logFile.parentFile?.mkdirs()
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                    logFile.writeText("")  // Truncate oversized log
                }
                logFile.appendText("$line\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log file", e)
            }
        }

        fun start(context: Context, config: FtpServerConfig): Boolean {
            // Redirect stderr once (for slf4j-simple log capture)
            if (!stderrRedirected) {
                try {
                    val slf4jFile = File(Environment.getExternalStorageDirectory(), "Download/ftp_slf4j.log")
                    slf4jFile.parentFile?.mkdirs()
                    if (slf4jFile.exists() && slf4jFile.length() > MAX_LOG_SIZE) {
                        slf4jFile.writeText("")
                    }
                    System.setErr(java.io.PrintStream(java.io.FileOutputStream(slf4jFile, true)))
                    stderrRedirected = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to redirect stderr", e)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Notification permission not granted")
                    return false
                }
            }
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_USER, config.username)
                putExtra(EXTRA_PASS, config.password)
                putExtra(EXTRA_PATH, config.sharedPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private class InMemoryUserManager : UserManager {
        private val users = mutableMapOf<String, User>()

        @Throws(FtpException::class)
        override fun getUserByName(username: String): User? {
            val user = users[username]
            FtpServerService.logToFile("getUserByName: '$username' -> ${if (user != null) "found" else "null"}")
            return user
        }

        @Throws(FtpException::class)
        override fun getAllUserNames(): Array<String> = users.keys.toTypedArray()

        @Throws(FtpException::class)
        override fun delete(username: String) {
            users.remove(username)
        }

        @Throws(FtpException::class)
        override fun save(user: User) {
            users[user.name] = user
        }

        @Throws(FtpException::class)
        override fun doesExist(username: String): Boolean = users.containsKey(username)

        @Throws(FtpException::class)
        override fun authenticate(authentication: Authentication): User {
            FtpServerService.logToFile("AUTH: called, type=${authentication.javaClass.name}")
            if (authentication is UsernamePasswordAuthentication) {
                val username = authentication.username
                val password = authentication.password
                FtpServerService.logToFile("AUTH: user='$username' passLen=${password?.length ?: -1}")
                val user = users[username]
                if (user != null) {
                    val baseUser = user as BaseUser
                    FtpServerService.logToFile("AUTH: found user, stored passLen=${baseUser.password?.length ?: -1}")
                    if (baseUser.password == password) {
                        FtpServerService.logToFile("AUTH: SUCCESS")
                        return user
                    }
                    FtpServerService.logToFile("AUTH: password mismatch")
                } else {
                    FtpServerService.logToFile("AUTH: user not found, users=${users.keys}")
                }
            } else {
                FtpServerService.logToFile("AUTH: not UsernamePasswordAuthentication")
            }
            FtpServerService.logToFile("AUTH: FAILED, throwing FtpException")
            throw FtpException("Authentication failed")
        }

        @Throws(FtpException::class)
        override fun getAdminName(): String = "admin"

        @Throws(FtpException::class)
        override fun isAdmin(username: String): Boolean = false
    }
}

class DebugFtplet : DefaultFtplet() {
    override fun init(context: org.apache.ftpserver.ftplet.FtpletContext?) {
        FtpServerService.logToFile("FTPLET init")
    }

    override fun destroy() {
        FtpServerService.logToFile("FTPLET destroy")
    }

    override fun onConnect(session: FtpSession): FtpletResult {
        FtpServerService.logToFile("FTPLET onConnect")
        return FtpletResult.DEFAULT
    }

    override fun onDisconnect(session: FtpSession): FtpletResult {
        FtpServerService.logToFile("FTPLET onDisconnect")
        return FtpletResult.DEFAULT
    }

    override fun beforeCommand(session: FtpSession, request: FtpRequest): FtpletResult {
        FtpServerService.logToFile("FTPLET CMD: ${request.command} ${request.argument}")
        return FtpletResult.DEFAULT
    }

    override fun afterCommand(session: FtpSession, request: FtpRequest, reply: FtpReply): FtpletResult {
        FtpServerService.logToFile("FTPLET RPL: ${request.command} -> ${reply.code}")
        return FtpletResult.DEFAULT
    }
}
