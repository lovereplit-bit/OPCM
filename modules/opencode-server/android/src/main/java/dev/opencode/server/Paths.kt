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
    for (dir in listOf(files, cache, fakeProc, prootTmp, home, logs)) dir.mkdirs()
  }
}
