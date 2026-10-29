package dev.opencode.server

import android.system.Os
import java.io.File

/** The device implementation of the extractor's file operations. */
object AndroidFs : PlatformFs {
  override fun symlink(target: String, link: File) = Os.symlink(target, link.path)

  override fun hardlink(existing: File, link: File) = Os.link(existing.path, link.path)

  override fun chmod(file: File, mode: Int) {
    try {
      Os.chmod(file.path, mode)
    } catch (_: Exception) {
      // A mode that cannot be set is not worth failing the whole install.
    }
  }
}
