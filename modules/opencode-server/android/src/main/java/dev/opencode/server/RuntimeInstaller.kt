package dev.opencode.server

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the Linux runtime (proot + Ubuntu + the OpenCode server) the app
 * runs its local server in. Unlike the bundled-asset OCM installer, the
 * archive is downloaded from the project's GitHub releases on first launch:
 *
 *   1. fetch the manifest JSON (version, sha256, size, url)
 *   2. download the archive into the cache with progress
 *   3. verify the SHA-256 before anything is unpacked
 *   4. stream-extract into a staging directory, then swap into place
 */
class RuntimeInstaller(private val paths: Paths) {

  fun installedStamp(): String? = if (paths.rootfsStamp.exists()) paths.rootfsStamp.readText().trim() else null

  private fun hasServer() = File(paths.rootfs, "usr/local/bin/opencode").exists()

  fun ready() = hasServer()

  fun needsInstall(version: String) = installedStamp() != version || !hasServer()

  data class Manifest(val version: String, val sha256: String, val size: Long, val url: String)

  /** Reads and parses the runtime manifest served alongside the release. */
  fun fetchManifest(url: String): Manifest {
    val text = downloadText(url, 64 * 1024)
    val version = regexField(text, """"version"\s*:\s*"([^"]+)"""")
      ?: throw IOException("The runtime manifest has no version")
    val sha = regexField(text, """"sha256"\s*:\s*"([0-9a-fA-F]{64})"""")
      ?: throw IOException("The runtime manifest has no sha256")
    val size = regexField(text, """"size"\s*:\s*(\d+)""")?.toLongOrNull() ?: 0L
    val archive = regexField(text, """"url"\s*:\s*"([^"]+)"""")
      ?: throw IOException("The runtime manifest has no url")

    return Manifest(version, sha.lowercase(), size, archive)
  }

  /**
   * Downloads and unpacks the runtime described by [manifest] into place.
   * [onDownload] receives 0..100 progress of the archive download and
   * [onExtract] the 0..100 progress of the unpack.
   */
  fun install(manifest: Manifest, onDownload: (Int) -> Unit, onExtract: (Int) -> Unit) {
    val archive = downloadArchive(manifest, onDownload)

    verifySha256(archive, manifest.sha256)

    unpack(archive, manifest.version, onExtract)

    archive.delete()
  }

  /** Keeps a partial download out of the tree: the archive lands in the cache dir. */
  private fun downloadArchive(manifest: Manifest, onProgress: (Int) -> Unit): File {
    val connection = openConnection(manifest.url)
    val total = if (manifest.size > 0) manifest.size else connection.contentLength.toLong()
    val target = paths.runtimeArchive

    target.parentFile?.mkdirs()

    var last = 0

    connection.inputStream.use { input ->
      FileOutputStream(target).use { out ->
        val buffer = ByteArray(64 * 1024)
        var done = 0L

        while (true) {
          val n = input.read(buffer)
          if (n < 0) break

          out.write(buffer, 0, n)
          done += n

          if (total > 0) {
            val progress = ((done * 100) / total).toInt().coerceIn(0, 100)
            if (progress != last) {
              last = progress
              onProgress(progress)
            }
          }
        }
      }
    }

    if (manifest.size > 0 && target.length() != manifest.size) {
      target.delete()
      throw IOException("The runtime archive is incomplete: ${target.length()} of ${manifest.size} bytes")
    }

    return target
  }

  private fun verifySha256(archive: File, expected: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = archive.inputStream()

    input.use { stream ->
      val buffer = ByteArray(64 * 1024)

      while (true) {
        val n = stream.read(buffer)
        if (n < 0) break

        digest.update(buffer, 0, n)
      }
    }

    val actual = digest.digest().joinToString("") { "%02x".format(it) }

    if (actual != expected) {
      archive.delete()
      throw IOException("The runtime archive failed its checksum")
    }
  }

  private fun unpack(archive: File, version: String, onProgress: (Int) -> Unit) {
    val staging = paths.staging
    val old = File(paths.files, "rootfs.old")

    Fs.deleteTree(staging)
    Fs.deleteTree(old)

    val total = archive.length()
    val extractor = TarGzExtractor(staging, AndroidFs)

    archive.inputStream().use { raw ->
      extractor.extract(CountingInputStream(raw) { done ->
        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 99))
      })
    }

    if (extractor.skipped > 0) {
      ServerLog.w("runtime", "${extractor.skipped} archive entries were skipped")
    }

    if (!File(staging, "usr/local/bin/opencode").exists() || !File(staging, "usr/bin/env").exists()) {
      Fs.deleteTree(staging)

      throw IOException("The Linux runtime archive is damaged or incomplete")
    }

    finish(staging)

    if (paths.rootfs.exists()) paths.rootfs.renameTo(old)

    if (!staging.renameTo(paths.rootfs)) throw IOException("Could not move the runtime into place")

    paths.rootfsStamp.writeText(version)
    onProgress(100)

    // Deleting the previous tree can take a while, and nothing depends on it.
    Thread { runCatching { Fs.deleteTree(old) } }.apply { isDaemon = true }.start()
  }

  /** Things the archive cannot carry: DNS, hosts and the scratch directories. */
  private fun finish(root: File) {
    File(root, "tmp").mkdirs()
    runCatching { Os.chmod(File(root, "tmp").path, 1023) }
    File(root, "root").mkdirs()
    File(root, "etc").mkdirs()

    val resolv = File(root, "etc/resolv.conf")

    resolv.delete()
    resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 2606:4700:4700::1111\n")

    val hosts = File(root, "etc/hosts")

    hosts.delete()
    hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
  }

  /** The /proc files Android denies to apps. Rewritten on every start; they are tiny. */
  fun writeFakeProc() {
    paths.fakeProc.mkdirs()

    for ((name, text) in FakeProc.contents()) File(paths.fakeProc, name).writeText(text)
  }

  companion object {
    private fun regexField(text: String, pattern: String): String? {
      val match = Regex(pattern).find(text) ?: return null

      return match.groupValues.getOrNull(1)
    }

    private fun downloadText(url: String, limitBytes: Int): String {
      val connection = openConnection(url)

      connection.inputStream.use { input ->
        val out = StringBuilder()
        val buffer = ByteArray(4 * 1024)

        while (out.length < limitBytes) {
          val n = input.read(buffer)
          if (n < 0) break

          out.append(String(buffer, 0, n, Charsets.UTF_8))
        }

        return out.toString()
      }
    }

    private fun openConnection(url: String): HttpURLConnection {
      val connection = URL(url).openConnection() as HttpURLConnection

      connection.connectTimeout = 15_000
      connection.readTimeout = 60_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("User-Agent", "OpenCode-Android/2.0")

      val code = connection.responseCode

      if (code !in 200..299) {
        connection.disconnect()
        throw IOException("The runtime download answered HTTP $code")
      }

      return connection
    }
  }
}
