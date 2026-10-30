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

      try {
        Thread.sleep(500)
      } catch (_: InterruptedException) {
        // The service is being torn down; the caller notices the stale epoch and stops.
        return false
      }
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
  fun failureHint(exitCode: Int? = null, compatOn: Boolean = false): String {
    val tail = attemptLog()
    val lower = tail.lowercase()

    return when {
      portWasBusy() ->
        "The server port is already in use. The next free port is picked automatically."
      stoppedBySyscall(exitCode, lower) && compatOn ->
        "Android stopped the server over a system call${suffix(exitCode)}, even in compatibility mode. Share the logs from this screen so it can be fixed."
      stoppedBySyscall(exitCode, lower) ->
        "Android stopped the server over a system call${suffix(exitCode)}. Compatibility mode in Settings can work around it."
      exitCode == 128 + SIGKILL ->
        "The phone stopped the server to free memory (SIGKILL). Close other apps and try again."
      exitCode != null && diedBySignal(exitCode) ->
        if (compatOn) {
          "The server process died from signal ${exitCode - 128}${suffix(exitCode)}, even in compatibility mode. Share the logs from this screen so it can be fixed."
        } else {
          "The server process died from signal ${exitCode - 128}${suffix(exitCode)}. Compatibility mode in Settings can work around it."
        }
      else ->
        tail.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n").ifEmpty { "The server did not answer in time" } + suffix(exitCode)
    }
  }

  /** The kernel's SIGSYS text, or the signal itself, when Android killed the server over a syscall. */
  private fun stoppedBySyscall(exitCode: Int?, lowerLog: String): Boolean =
    "bad system call" in lowerLog || "sigsys" in lowerLog || "seccomp" in lowerLog || exitCode == 128 + SIGSYS

  private fun suffix(exitCode: Int?): String = exitCode?.let { " (${describeExit(it)})" } ?: ""

  /** True when the current attempt died because its port was already in use by something else on the phone. */
  fun portWasBusy(): Boolean {
    val lower = attemptLog().lowercase()

    return BUSY_PORT_MARKERS.any { it in lower }
  }

  /** What the current attempt printed, so an earlier attempt in the same log cannot be mistaken for it. */
  private fun attemptLog(): String = ServerLog.since(paths.serverLog, logMark, 6 * 1024)

  /** The exit code once the attempt has ended, or null while it still runs. */
  fun exitCodeOf(process: Process): Int? = try {
    process.exitValue()
  } catch (_: IllegalThreadStateException) {
    null
  }

  /**
   * True when this attempt printed nothing beyond its own start banner. A
   * process the kernel kills with a signal says nothing on its way down, and
   * without a shell in front of it nobody reports the signal - so a bare
   * banner means "died in silence", not "all is well".
   */
  fun attemptWasSilent(): Boolean =
    attemptLog().lines().filter { it.isNotBlank() }.all { it.trimStart().startsWith("--- start") }

  companion object {
    // How "this port is taken" looks in the logs of the Go, Node and Bun
    // servers alike, so a bind race is never mistaken for a real failure.
    private val BUSY_PORT_MARKERS = listOf(
      "address already in use", // Go: "listen tcp :4096: bind: address already in use"; POSIX text
      "eaddrinuse",             // Node: "EADDRINUSE: listen EADDRINUSE 0.0.0.0:4096"
      "os error 98",            // Bun/Rust: "Address already in use (os error 98)"
      "errno: -98",             // Node's numeric form of EADDRINUSE
      "errno=98",               // strace-style rendering
    )

    // Linux signals proot can be killed with on Android, by waitpid number.
    private const val SIGKILL = 9
    private const val SIGSYS = 31

    private val SIGNALS = mapOf(
      4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT", 7 to "SIGBUS",
      9 to "SIGKILL", 11 to "SIGSEGV", 13 to "SIGPIPE", 15 to "SIGTERM", 31 to "SIGSYS",
    )

    /** True when the waitpid code means the process was killed by a signal. */
    fun diedBySignal(code: Int): Boolean = code - 128 in 1..31

    /** "exit code 159 (killed by signal 31: SIGSYS)" for the log and the failure hints. */
    fun describeExit(code: Int): String {
      val signal = code - 128

      if (signal in 1..31) return "exit code $code (killed by signal $signal: ${SIGNALS[signal] ?: "unknown"})"

      return "exit code $code"
    }

    fun basicCredential(password: String): String =
      android.util.Base64.encodeToString(":$password".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
  }
}
