package dev.opencode.server

import android.content.Context
import java.io.File

/** Every location the server module uses, in one place. All inside the app's private storage. */
class Paths(context: Context) {
  val files: File = context.filesDir

  val cache: File = context.cacheDir

  val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

  val prootBin: String = "$nativeLibDir/libproot.so"

  val prootLoader: String = "$nativeLibDir/libproot-loader.so"

  /**
   * The small directory the launcher's shared libraries are resolved through. The
   * packaged proot links against `libtalloc.so.2` (the name Termux built it with),
   * while Android only unpacks `lib*.so` files - so the app provides the name as a
   * symlink to the unpacked copy and puts this directory first on LD_LIBRARY_PATH.
   */
  val launcherLibs = File(files, "launcher-libs")

  val rootfs = File(files, "rootfs")

  val rootfsStamp = File(files, "rootfs.stamp")

  val staging = File(files, "rootfs.new")

  val runtimeArchive = File(cache, "opencode-runtime.tgz")

  val fakeProc = File(files, "fakeproc")

  val prootTmp = File(files, "tmp")

  val home = File(files, "home")

  val logs = File(files, "logs")

  val serverLog = File(logs, "server.log")

  fun ensure() {
    for (dir in listOf(files, cache, fakeProc, prootTmp, home, logs, launcherLibs)) dir.mkdirs()
  }
}
