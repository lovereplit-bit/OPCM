package dev.opencode.server

import java.io.File

/** Everything that decides how the Linux environment is started. */
data class ProotConfig(
  val prootBin: String,
  val loader: String,
  val nativeLibDir: String,
  val launcherLibs: String,
  val rootfs: String,
  val home: String,
  val fakeProc: String,
  val prootTmp: String,
  val port: Int,
  val password: String,
  val compat: Boolean,
  val timeZone: String,
  val locale: String = "C.UTF-8",
  val apiKey: String? = null,
  /** One rung of the start-up retry ladder; see [LaunchVariants]. */
  val variant: LaunchVariant = LaunchVariants.DEFAULT,
)

/**
 * How defensively proot is asked to run. The first attempt uses the fast
 * path; every rung after that trades a little speed for compatibility with
 * kernels that kill or crash the tracer, so a phone that rejects one
 * setting still gets a working server on the next try.
 */
data class LaunchVariant(
  val name: String,
  /** PROOT_NO_SECCOMP: pure ptrace interception instead of the seccomp fast path. */
  val noSeccomp: Boolean = false,
  /** PROOT_NO_MOUNTINFO: skip parsing Android's mount table, a known crash surface. */
  val noMountinfo: Boolean = false,
  /** Drop --link2symlink, whose hard-link emulation had device-specific crashes. */
  val noLink2Symlink: Boolean = false,
  /** PROOT_FORCE_KOMPAT: treat the kernel as the oldest one supported, enabling every
   *  syscall emulation proot carries - OEM kernels that report a modern version while
   *  behaving like an older one get served either way. */
  val forceKompat: Boolean = false,
  /** PROOT_VERBOSE level for this attempt, so the log pinpoints where it dies. */
  val verbose: Int = 0,
)

object LaunchVariants {
  /** The default rung: every acceleration enabled, no diagnostics noise. */
  val DEFAULT = LaunchVariant("default")

  val COMPAT = LaunchVariant("compatibility", noSeccomp = true, verbose = 1)

  val COMPAT_NO_MOUNTINFO = LaunchVariant(
    "compatibility without the mount table",
    noSeccomp = true,
    noMountinfo = true,
    verbose = 1,
  )

  val COMPAT_MINIMAL = LaunchVariant(
    "compatibility without the mount table and without link emulation",
    noSeccomp = true,
    noMountinfo = true,
    noLink2Symlink = true,
    verbose = 2,
  )

  val KOMPAT = LaunchVariant(
    "kernel compatibility mode",
    noSeccomp = true,
    noMountinfo = true,
    noLink2Symlink = true,
    forceKompat = true,
    verbose = 1,
  )

  /** The whole ladder, in the order the service climbs it. */
  val ALL = listOf(DEFAULT, COMPAT, COMPAT_NO_MOUNTINFO, COMPAT_MINIMAL, KOMPAT)
}

data class Launch(val command: List<String>, val env: Map<String, String>)

object ProotCommand {
  const val GUEST_BINARY = "/usr/local/bin/opencode"
  const val GUEST_INSTRUCTIONS = "/etc/opencode-android/AGENTS.md"
  const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

  /** The prefix every proot-wrapped command shares: root filesystem, binds and the fake-root identity. */
  fun base(c: ProotConfig): List<String> {
    val args = ArrayList<String>()

    args += c.prootBin
    args += "--kill-on-exit"
    if (!c.variant.noLink2Symlink) args += "--link2symlink"
    args += "-0"
    args += "-r"
    args += c.rootfs
    args += "-w"
    args += "/root"

    fun bind(host: String, guest: String = host) {
      args += "-b"
      args += if (host == guest) host else "$host:$guest"
    }

    bind("/dev")
    bind("/dev/urandom", "/dev/random")
    bind("/proc")
    bind("/proc/self/fd", "/dev/fd")
    bind("/proc/self/fd/0", "/dev/stdin")
    bind("/proc/self/fd/1", "/dev/stdout")
    bind("/proc/self/fd/2", "/dev/stderr")
    bind("/sys")

    // Android hides most of /proc from apps, and some tools abort when it is missing or empty.
    for ((name, guest) in FakeProc.targets) bind(File(c.fakeProc, name).path, guest)

    bind(c.home, "/root")

    return args
  }

  fun hostEnv(c: ProotConfig): Map<String, String> {
    val env = LinkedHashMap<String, String>()

    env["PROOT_TMP_DIR"] = c.prootTmp
    env["PROOT_LOADER"] = c.loader
    env["LD_LIBRARY_PATH"] = "${c.launcherLibs}:${c.nativeLibDir}"
    env["TMPDIR"] = c.prootTmp

    if (c.variant.noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
    if (c.variant.noMountinfo) env["PROOT_NO_MOUNTINFO"] = "1"
    if (c.variant.forceKompat) env["PROOT_FORCE_KOMPAT"] = "1"
    if (c.variant.verbose > 0) env["PROOT_VERBOSE"] = c.variant.verbose.toString()

    return env
  }

  fun guestEnv(c: ProotConfig): LinkedHashMap<String, String> {
    val env = LinkedHashMap<String, String>()

    env["HOME"] = "/root"
    env["USER"] = "root"
    env["LOGNAME"] = "root"
    env["SHELL"] = "/bin/bash"
    env["PATH"] = GUEST_PATH
    env["LANG"] = c.locale
    env["LC_ALL"] = c.locale
    env["TERM"] = "xterm-256color"
    env["TZ"] = c.timeZone
    env["TMPDIR"] = "/tmp"
    env["OPENCODE_PASSWORD"] = c.password
    env["OPENCODE_CLIENT"] = "android"
    env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"

    // Features that lean on native code or kernel interfaces Android does not allow.
    env["OPENCODE_DISABLE_FFF"] = "1"
    env["OPENCODE_FILEWATCHER_DISABLE"] = "1"

    // A personal OpenCode API key, when the user set one in Settings: the
    // server sends every model request under it instead of the anonymous lane.
    if (!c.apiKey.isNullOrBlank()) {
      env["OPENCODE_API_KEY"] = c.apiKey
    }

    env["OPENCODE_CONFIG_CONTENT"] = configContent()

    return env
  }

  /**
   * The minimal server config: the Android instructions file the runtime
   * ships. The model catalog is left as the upstream server presents it, so
   * the app's own model picker stays in charge of what is offered.
   */
  private fun configContent(): String =
    "{\"instructions\":[\"$GUEST_INSTRUCTIONS\"]}"

  fun server(c: ProotConfig): Launch {
    val command = ArrayList(base(c))

    command += "/usr/bin/env"
    command += "-i"
    for ((k, v) in guestEnv(c)) command += "$k=$v"
    command += GUEST_BINARY
    command += "serve"
    command += "--hostname"
    command += "127.0.0.1"
    command += "--port"
    command += c.port.toString()

    return Launch(command, hostEnv(c))
  }
}

/** Stand-ins for the /proc files Android denies to apps, bound over the real ones. */
object FakeProc {
  val targets: List<Pair<String, String>> = listOf(
    "loadavg" to "/proc/loadavg",
    "stat" to "/proc/stat",
    "uptime" to "/proc/uptime",
    "version" to "/proc/version",
    "vmstat" to "/proc/vmstat",
    "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
    "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
  )

  fun contents(cpus: Int = Runtime.getRuntime().availableProcessors()): Map<String, String> {
    val stat = StringBuilder("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")

    for (i in 0 until maxOf(1, cpus)) stat.append("cpu$i 244 0 359 11660 32 42 31 10 0 0\n")

    stat.append("intr 63361 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1700000000\nprocesses 26442\n")
    stat.append("procs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")

    return mapOf(
      "loadavg" to "0.12 0.07 0.02 2/165 765\n",
      "stat" to stat.toString(),
      "uptime" to "5400.12 4800.00\n",
      "version" to "Linux version 6.2.1-PRoot-Android (proot@android) (gcc (GCC) 12.2.0) #1 SMP PREEMPT Fri Jan 1 00:00:00 UTC 2021\n",
      "vmstat" to "nr_free_pages 1000000\nnr_inactive_anon 100000\nnr_active_anon 100000\npgfault 1000000\npgmajfault 1000\n",
      "cap_last_cap" to "40\n",
      "max_user_watches" to "4096\n",
    )
  }
}
