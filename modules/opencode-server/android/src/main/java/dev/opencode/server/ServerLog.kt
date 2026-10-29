package dev.opencode.server

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A tiny logger that keeps a rotating in-app file the settings screen can show. */
object ServerLog {
  private const val TAG = "OpenCodeServer"
  private const val MAX_FILE_BYTES = 256 * 1024

  @Volatile
  private var logFile: File? = null

  fun attach(file: File) {
    logFile = file
    file.parentFile?.mkdirs()
  }

  fun i(scope: String, message: String) {
    Log.i(TAG, "[$scope] $message")
    write("I", scope, message)
  }

  fun w(scope: String, message: String) {
    Log.w(TAG, "[$scope] $message")
    write("W", scope, message)
  }

  fun e(scope: String, message: String, error: Throwable? = null) {
    Log.e(TAG, "[$scope] $message", error)
    write("E", scope, message + (error?.let { ": ${it.message}" } ?: ""))
  }

  @Synchronized
  private fun write(level: String, scope: String, message: String) {
    val file = logFile ?: return
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    try {
      if (file.exists() && file.length() > MAX_FILE_BYTES) {
        val rotated = File(file.parentFile, file.name + ".1")
        rotated.delete()
        file.renameTo(rotated)
      }

      file.appendText("$stamp $level [$scope] $message\n")
    } catch (_: Exception) {
      // Logging must never break the caller.
    }
  }

  /** The tail of the module log, for the in-app diagnostics screen. */
  fun tail(bytes: Int = 12 * 1024): String = try {
    val file = logFile ?: return ""
    val content = file.readText()
    if (content.length > bytes) content.takeLast(bytes) else content
  } catch (_: Exception) {
    ""
  }

  /** Lines of the server log since a byte mark, bounded, for failure hints. */
  fun since(file: File, mark: Long, bytes: Int): String {
    return try {
      val length = file.length()
      if (length <= mark) return ""
      val from = maxOf(mark, length - bytes)
      val out = StringBuilder()

      file.inputStream().use { input ->
        input.skip(from)
        val buffer = ByteArray(4 * 1024)

        while (out.length < bytes) {
          val n = input.read(buffer)
          if (n < 0) break

          out.append(String(buffer, 0, n, Charsets.UTF_8))
        }
      }

      out.toString()
    } catch (_: Exception) {
      ""
    }
  }
}
