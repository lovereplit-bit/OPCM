package dev.opencode.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Keeps the local OpenCode server alive when the app is not on screen: it owns
 * the Linux server process and the foreground notification. The runtime
 * install (download + unpack) also lives here so a first launch survives the
 * app going to background.
 */
class ServerService : Service() {
  private val worker = Executors.newSingleThreadExecutor { Thread(it, "opencode-server") }
  private val main = Handler(Looper.getMainLooper())

  private lateinit var paths: Paths
  private lateinit var prefs: Prefs
  private lateinit var runtime: RuntimeInstaller
  private lateinit var server: ServerProcess

  private var wake: PowerManager.WakeLock? = null
  private var wifi: WifiManager.WifiLock? = null

  /** Bumped on every start and stop so a watchdog from an earlier run can tell it is stale. */
  @Volatile
  private var epoch = 0

  override fun onCreate() {
    super.onCreate()

    paths = Paths(this)
    paths.ensure()
    ServerLog.attach(paths.serverLog)

    prefs = Prefs(this)
    runtime = RuntimeInstaller(paths)
    server = ServerProcess(paths)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      val notification = buildNotification("OpenCode", "Starting...")

      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      // Android refuses to start a foreground service from the background; the next visit to the app starts it.
      ServerLog.w("service", "could not enter the foreground: ${e.message}")
      stopSelf()

      return START_NOT_STICKY
    }

    when (intent?.action) {
      ACTION_STOP -> worker.execute {
        shutdown()
        main.post {
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
        }
      }
      ACTION_RESTART -> worker.execute { begin(intent.getStringExtra(EXTRA_MANIFEST)) }
      else -> worker.execute { ensureRunning(intent?.getStringExtra(EXTRA_MANIFEST)) }
    }

    return START_STICKY
  }

  override fun onDestroy() {
    epoch++
    releaseLocks()
    worker.shutdownNow()

    // The server belongs to this service; leaving it running without a notification would be a hidden process.
    Thread {
      runCatching { server.stop() }.onFailure { ServerLog.w("service", "could not stop the server: ${it.message}") }
    }.start()

    super.onDestroy()
  }

  // Lifecycle

  private fun baseUrl(port: Int) = "http://127.0.0.1:$port"

  private fun ensureRunning(manifestUrl: String?) {
    if (ServerState.snapshot.phase == Phase.RUNNING) return
    if (ServerState.snapshot.phase == Phase.STARTING) return
    if (ServerState.snapshot.phase == Phase.DOWNLOADING) return
    if (ServerState.snapshot.phase == Phase.INSTALLING) return

    // The service can be recreated while the server is still up; take it over instead of starting a second one.
    if (server.health(baseUrl(prefs.port), prefs.password) == 200 && server.prootPids().isNotEmpty()) {
      ServerLog.i("service", "adopting the running server on port ${prefs.port}")
      acquireLocks()
      onRunning(prefs.port, runtime.installedStamp())
    } else {
      begin(manifestUrl)
    }
  }

  private fun begin(manifestUrl: String?) {
    val my = ++epoch
    val password = prefs.password

    acquireLocks()

    try {
      if (!runtime.ready()) {
        val url = manifestUrl ?: throw IOException("The Linux runtime is not installed and no runtime source is configured.")

        installRuntime(url)
      }
    } catch (e: Exception) {
      ServerLog.e("service", "runtime install failed", e)
      fail(e.message ?: e.javaClass.simpleName)

      return
    }

    if (my != epoch) return

    runtime.writeFakeProc()

    server.killOrphans()

    var port: Int = pickPort(prefs.port) ?: run {
      fail("No free port was left for the server (looked from ${prefs.port} upward).")
      return
    }

    if (port != prefs.port) {
      prefs.port = port
      ServerLog.i("service", "the server listens on port $port now")
    }

    var shifts = 0
    var compatTries = 0

    while (true) {
      val config = server.config(prefs, port)
      val process = try {
        server.spawn(config)
      } catch (e: Exception) {
        ServerLog.e("service", "could not start the server", e)
        fail("Could not start the server: ${e.message ?: e.javaClass.simpleName}")
        return
      }

      // The message stays empty on a plain start so the setup screen keeps its
      // localized text; the compatibility retry and the port moves are worth a line.
      ServerState.publish(
        Phase.STARTING,
        if (config.compat) "Starting the server in compatibility mode..." else "",
        null,
        port,
      )

      val healthy = server.waitHealthy(baseUrl(port), password, 180_000) { process.isAlive }

      if (my != epoch) return

      if (healthy) break

      // Read how it ended before stop() reaps the process: the code tells a
      // signal kill (128 + signal) from a clean exit, and nothing else will.
      val exit = server.exitCodeOf(process)

      server.stop()

      if (exit != null) {
        ServerLog.w("service", "the server ended with ${ServerProcess.describeExit(exit)} before answering")
      }

      // Someone took the port between the check and the bind; move to the
      // next free one. The shift count only guards a pathological run of
      // races: every candidate is probed for availability first, so the
      // server keeps walking upward until it actually connects.
      if (server.portWasBusy() && shifts < MAX_PORT_SHIFTS) {
        val next = pickPort(port)

        if (next != null) {
          val previous = port

          ServerLog.w("service", "port $previous was taken during startup, moving to $next")
          shifts++
          port = next
          prefs.port = port

          // Tell the UI right away: the address row on the setup screen follows
          // the published port, and a busy port would otherwise look like a hang
          // while the next one is being tried.
          ServerState.publish(Phase.STARTING, "Port $previous is busy, trying $next...", null, port)

          continue
        }
      }

      // A death with no output is the classic seccomp kill: the kernel
      // terminates proot with a signal before it prints a word, and without a
      // shell in front of it nobody reports the signal. One retry in
      // compatibility mode (PROOT_NO_SECCOMP=1) rescues those phones without
      // slowing every other one down with ptrace-only interception.
      if (exit != null && compatTries < MAX_COMPAT_RETRIES && !config.compat &&
          (ServerProcess.diedBySignal(exit) || server.attemptWasSilent())) {
        compatTries++
        prefs.compat = true

        ServerLog.w("service", "the server died without printing a word (${ServerProcess.describeExit(exit)}); retrying in compatibility mode")

        continue
      }

      fail(server.failureHint(exit, prefs.compat))
      return
    }

    onRunning(port, runtime.installedStamp())
  }

  private fun installRuntime(url: String) {
    ServerState.publish(Phase.DOWNLOADING, "Checking for the Linux runtime...")

    val manifest = runtime.fetchManifest(url)
    ServerLog.i("service", "runtime ${manifest.version}: ${manifest.size} bytes")

    if (!runtime.needsInstall(manifest.version)) {
      ServerState.publish(Phase.INSTALLING, "The runtime is already installed.", 100)
      return
    }

    runtime.install(manifest,
      { progress -> ServerState.progress("Downloading the Linux runtime (${manifest.size / (1024 * 1024)} MB)...", progress) },
      { progress -> ServerState.progress("Unpacking the Linux runtime. This happens once.", progress) },
    )

    ServerLog.i("service", "runtime ${manifest.version} installed")
  }

  private fun pickPort(after: Int): Int? = PortPicker.pick(after) { candidate -> !PortPicker.free(candidate) }

  private fun onRunning(port: Int, version: String?) {
    ServerState.publish(Phase.RUNNING, "", null, port, version)
    updateNotification("OpenCode", "Local server on 127.0.0.1:$port")

    // Watch the process; publish a failure if it dies under us.
    Thread {
      val p = server.process

      if (p == null) return@Thread

      val code = runCatching { p.waitFor() }.getOrNull()

      if (ServerState.snapshot.phase == Phase.RUNNING && server.process === p) {
        ServerLog.w("service", "the server exited with ${code?.let { ServerProcess.describeExit(it) } ?: "an unknown status"}")
        ServerState.publish(Phase.FAILED, server.failureHint(code, prefs.compat), null, ServerState.snapshot.port ?: prefs.port)
      }
    }.apply { isDaemon = true }.start()
  }

  private fun fail(message: String) {
    ServerLog.w("service", "failed: $message")

    // Keep the last port in the snapshot: the address row on the setup screen
    // follows it, and wiping it on failure made the row read "-" as if no
    // port had ever been picked.
    ServerState.publish(Phase.FAILED, message, null, ServerState.snapshot.port ?: prefs.port)
    updateNotification("OpenCode", "Server stopped")
    releaseLocks()
  }

  private fun shutdown() {
    epoch++
    ServerLog.i("service", "stopping the server")
    server.stop()
    releaseLocks()
    ServerState.publish(Phase.IDLE, "")
  }

  // Locks

  private fun acquireLocks() {
    try {
      if (prefs.keepAwake && wake == null) {
        wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:server")?.also {
          it.setReferenceCounted(false)
          it.acquire(4 * 60 * 60 * 1000L)
        }
      }
      if (prefs.keepWifi && wifi == null) {
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "opencode:server")?.also {
          it.setReferenceCounted(false)
          it.acquire()
        }
      }
    } catch (e: Exception) {
      ServerLog.w("service", "could not acquire the keep-awake locks: ${e.message}")
    }
  }

  private fun releaseLocks() {
    runCatching { wake?.release() }
    runCatching { wifi?.release() }
    wake = null
    wifi = null
  }

  // Notifications

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < 26) return

    val manager = getSystemService(NotificationManager::class.java) ?: return

    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "OpenCode server", NotificationManager.IMPORTANCE_LOW).apply {
        description = "The state of the local OpenCode server"
        setShowBadge(false)
      },
    )
  }

  private fun buildNotification(title: String, text: String): android.app.Notification {
    ensureChannel()

    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = launch?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    val builder = if (Build.VERSION.SDK_INT >= 26) {
      android.app.Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      android.app.Notification.Builder(this)
    }

    builder
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSmallIcon(R.drawable.ic_stat_opencode)
      .also { if (contentIntent != null) it.setContentIntent(contentIntent) }

    return builder.build()
  }

  private fun updateNotification(title: String, text: String) {
    try {
      val manager = getSystemService(NotificationManager::class.java) ?: return

      manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    } catch (_: Exception) {
      // A notification update must never break the service.
    }
  }

  companion object {
    const val CHANNEL_ID = "opencode-server"
    const val NOTIFICATION_ID = 0x0CDE

    /** How many times a startup may move to the next port after a bind race. */
    const val MAX_PORT_SHIFTS = 64

    /** How many times a silent death may switch on compatibility mode and retry. */
    const val MAX_COMPAT_RETRIES = 1

    const val ACTION_START = "dev.opencode.server.START"
    const val ACTION_STOP = "dev.opencode.server.STOP"
    const val ACTION_RESTART = "dev.opencode.server.RESTART"
    const val EXTRA_MANIFEST = "manifest_url"

    fun send(context: Context, action: String, manifestUrl: String? = null) {
      val intent = Intent(context, ServerService::class.java).apply {
        this.action = action
        manifestUrl?.let { putExtra(EXTRA_MANIFEST, it) }
      }

      if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
