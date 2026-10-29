package dev.opencode.server

import android.os.Process as AndroidProcess
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Starts, finds and stops the OpenCode server that runs inside the Linux environment. */
class ServerProcess(private val paths: Paths) {
  @Volatile
  var process: Process? = null
    private set

  /** Where the server log stood when the running attempt started, so only that attempt is inspected on failure. */
  private var logMark: Long = 0

  fun config(prefs: Prefs, port: Int): ProotConfig =
    ProotConfig(
      prootBin = paths.prootBin,
      loader = paths.prootLoader,
      nativeLibDir = paths.nativeLibDir,
      rootfs = paths.rootfs.path,
      home = paths.home.path,
      fakeProc = paths.fakeProc.path,
      prootTmp = paths.prootTmp.path,
      port = port,
      password = prefs.password,
      compat = prefs.compat,
      timeZone = java.util.TimeZone.getDefault().id,
      apiKey = prefs.apiKey,
    )

  fun spawn(config: ProotConfig): Process {
    val launch = ProotCommand.server(config)

    File(config.home).mkdirs()
    File(config.prootTmp).mkdirs()
    rotateLog()
    logMark = paths.serverLog.length()
    paths.serverLog.appendText("--- start ${java.util.Date()} port=${config.port} compat=${config.compat}\n")

    val builder = ProcessBuilder(launch.command)

    builder.environment().putAll(launch.env)
    builder.directory(paths.files)
    builder.redirectErrorStream(true)
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.serverLog))

    val started = builder.start()

    process = started

    return started
  }

  private fun rotateLog() {
    if (paths.serverLog.exists() && paths.serverLog.length() > 1024 * 1024) {
      val old = File(paths.logs, "server.log.1")

      old.delete()
      paths.serverLog.renameTo(old)
    }
  }

  /** HTTP status of `GET /`, or -1 when nothing answers. */
  fun health(baseUrl: String, password: String): Int = try {
    val connection = URL("$baseUrl/").openConnection() as HttpURLConnection

    try {
      connection.connectTimeout = 1500
      connection.readTimeout = 4000
      connection.instanceFollowRedirects = false
      connection.setRequestProperty("Authorization", "Basic " + basicCredential(password))
      connection.responseCode
    } finally {
      connection.disconnect()
    }
  } catch (_: Exception) {
    -1
  }

  /** Waits until the server answers 200. Returns false when it dies, or the time runs out. */
  fun waitHealthy(baseUrl: String, password: String, timeoutMs: Long, alive: () -> Boolean): Boolean {
    val start = System.currentTimeMillis()

    while (System.currentTimeMillis() - start < timeoutMs) {
      if (health(baseUrl, password) == 200) return true
      if (!alive()) return false

      Thread.sleep(500)
    }

    return false
  }

  /** Process ids of every proot this app started, found through /proc so it also works after the app restarted. */
  fun prootPids(): List<Int> {
    val out = ArrayList<Int>()
    val me = AndroidProcess.myPid()

    for (entry in File("/proc").listFiles().orEmpty()) {
      val pid = entry.name.toIntOrNull() ?: continue

      if (pid == me) continue

      val first = try {
        File(entry, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
      } catch (_: Exception) {
        continue
      }

      if (first == paths.prootBin) out += pid
    }

    return out
  }

  /** Kills every proot of ours. The tracees go with it, which stops the server and anything it started. */
  fun killOrphans() {
    for (pid in prootPids()) {
      try {
        AndroidProcess.killProcess(pid)
      } catch (_: Exception) {
        // Already gone.
      }
    }
  }

  fun stop() {
    val p = process

    process = null
    p?.destroy()
    killOrphans()
    p?.let { runCatching { it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } }
    p?.let { if (it.isAlive) it.destroyForcibly() }
  }

  /** Reads why the server died, to tell the person something more useful than "failed". */
  fun failureHint(): String {
    val tail = attemptLog()
    val lower = tail.lowercase()

    return when {
      "bad system call" in lower || "sigsys" in lower || "seccomp" in lower ->
        "Android stopped the process over a system call. Turn on compatibility mode in settings and try again."
      "address already in use" in lower || "eaddrinuse" in lower ->
        "The server port is already in use. Restarting the server picks the next free one."
      else -> tail.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n").ifEmpty { "The server did not answer in time" }
    }
  }

  /** True when the current attempt died because its port was already in use by something else on the phone. */
  fun portWasBusy(): Boolean {
    val lower = attemptLog().lowercase()

    return "address already in use" in lower || "eaddrinuse" in lower
  }

  /** What the current attempt printed, so an earlier attempt in the same log cannot be mistaken for it. */
  private fun attemptLog(): String = ServerLog.since(paths.serverLog, logMark, 6 * 1024)

  companion object {
    fun basicCredential(password: String): String =
      android.util.Base64.encodeToString(":$password".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
  }
}
