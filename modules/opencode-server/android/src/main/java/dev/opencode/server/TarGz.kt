package dev.opencode.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** What the extractor needs from a file system. The device uses `android.system.Os`. */
interface PlatformFs {
  fun symlink(target: String, link: File)

  fun hardlink(existing: File, link: File)

  fun chmod(file: File, mode: Int)
}

/**
 * A streaming tar.gz reader that writes straight into [root]: ustar, GNU long
 * names and PAX headers, directories, regular files, symlinks and hard links.
 * Entries that would leave [root] are skipped. Adapted from the OCM extractor,
 * which guards against the classic tar symlink attacks.
 */
class TarGzExtractor(
  private val root: File,
  private val fs: PlatformFs,
  private val onEntry: (count: Int) -> Unit = {},
) {
  var files = 0
    private set

  var skipped = 0
    private set

  private val block = ByteArray(512)
  private val buffer = ByteArray(64 * 1024)

  /** Directories already proven to be real directories all the way down from the root. */
  private val safeDirs = HashSet<String>()

  fun extract(source: InputStream) {
    val input = GZIPInputStream(BufferedInputStream(source, 64 * 1024), 64 * 1024)
    var longName: String? = null
    var longLink: String? = null
    var pax: Map<String, String> = emptyMap()
    val rootPath = root.toPath().toAbsolutePath().normalize()

    root.mkdirs()

    while (true) {
      if (!readBlock(input)) return

      if (block.all { it == 0.toByte() }) return

      val type = block[156].toInt().toChar()
      var size = parseNumber(block, 124, 12)
      val mode = parseNumber(block, 100, 8).toInt() and 0xFFF

      pax["size"]?.toLongOrNull()?.let { size = it }

      if (type == 'L' || type == 'K') {
        val text = readText(input, size)

        if (type == 'L') longName = text else longLink = text

        continue
      }

      if (type == 'x') {
        pax = parsePax(readText(input, size))

        continue
      }

      if (type == 'g') {
        skip(input, size)
        skipPadding(input, size)

        continue
      }

      val name = pax["path"] ?: longName ?: entryName()
      val link = pax["linkpath"] ?: longLink ?: cString(block, 157, 100)

      longName = null
      longLink = null
      pax = emptyMap()

      val clean = sanitize(name)
      val isRoot = clean == null && name.replace('\\', '/').split('/').all { it.isEmpty() || it == "." }
      val target = if (clean == null) null else File(root, clean)
      val safe = target != null && stays(rootPath, target) && parentSafe(rootPath, target)

      when (type) {
        '5' -> if (safe) {
          target!!.mkdirs()
          fs.chmod(target, mode or 0b111_000_000)
        }

        '0', '7' -> {
          if (safe) {
            writeFile(input, target!!, size, mode)
            files++
          } else {
            if (!isRoot) skipped++
            skip(input, size)
          }

          skipPadding(input, size)
        }

        '2' -> if (safe) {
          target!!.parentFile?.mkdirs()
          removeEntry(target)
          forget(target)
          fs.symlink(link, target)
        } else {
          skipped++
        }

        '1' -> {
          val source = sanitize(link)?.let { File(root, it) }

          if (safe && source != null && stays(rootPath, source) && parentSafe(rootPath, source) && source.exists()) {
            target!!.parentFile?.mkdirs()
            removeEntry(target)
            runCatching { fs.hardlink(source, target) }.onFailure { source.copyTo(target, true) }
          } else {
            skipped++
          }
        }

        else -> {
          skipped++
          skip(input, size)
          skipPadding(input, size)
        }
      }

      onEntry(files)
    }
  }

  private fun entryName(): String {
    val name = cString(block, 0, 100)
    val magic = cString(block, 257, 6)

    if (!magic.startsWith("ustar")) return name

    val prefix = cString(block, 345, 155)

    return if (prefix.isEmpty()) name else "$prefix/$name"
  }

  private fun writeFile(input: InputStream, target: File, size: Long, mode: Int) {
    target.parentFile?.mkdirs()
    // Never write through a symlink that an earlier entry planted, even a dangling one.
    removeEntry(target)

    var left = size

    FileOutputStream(target).use { out ->
      while (left > 0) {
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())

        if (n < 0) throw EOFException("Archive ended inside ${target.name}")

        out.write(buffer, 0, n)
        left -= n
      }
    }

    fs.chmod(target, mode or 0b110_000_000)
  }

  private fun readBlock(input: InputStream): Boolean {
    var read = 0

    while (read < 512) {
      val n = input.read(block, read, 512 - read)

      if (n < 0) {
        if (read == 0) return false

        throw EOFException("Truncated tar header")
      }

      read += n
    }

    return true
  }

  private fun readText(input: InputStream, size: Long): String {
    require(size in 0..(1 shl 20)) { "Tar metadata entry is too large" }

    val out = ByteArrayOutputStream(size.toInt())
    var left = size

    while (left > 0) {
      val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())

      if (n < 0) throw EOFException("Archive ended inside metadata")

      out.write(buffer, 0, n)
      left -= n
    }

    skipPadding(input, size)

    return out.toString(Charsets.UTF_8.name()).trimEnd('\u0000', '\n')
  }

  private fun skip(input: InputStream, count: Long) {
    var left = count

    while (left > 0) {
      val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())

      if (n < 0) throw EOFException("Archive ended while skipping")

      left -= n
    }
  }

  private fun skipPadding(input: InputStream, size: Long) {
    val rest = (512 - size % 512) % 512

    skip(input, rest)
  }

  private fun stays(rootPath: Path, target: File): Boolean = try {
    val normal = target.toPath().toAbsolutePath().normalize()

    normal.startsWith(rootPath) && normal != rootPath
  } catch (_: java.nio.file.InvalidPathException) {
    false
  }

  /**
   * A faithful root filesystem never stores an entry beneath a symlinked
   * directory, so an entry that would be written through one is the classic
   * tar symlink attack and is dropped.
   */
  private fun parentSafe(rootPath: Path, target: File): Boolean = try {
    parentSafeUnchecked(rootPath, target)
  } catch (_: java.nio.file.InvalidPathException) {
    false
  }

  private fun parentSafeUnchecked(rootPath: Path, target: File): Boolean {
    val parent = target.parentFile ?: return false
    val key = parent.path

    if (key in safeDirs) return true

    var current = rootPath

    for (part in rootPath.relativize(parent.toPath().toAbsolutePath().normalize())) {
      current = current.resolve(part)

      if (Files.isSymbolicLink(current)) return false
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break
    }

    safeDirs += key

    return true
  }

  private fun forget(path: File) {
    val key = path.path

    safeDirs.removeAll { it == key || it.startsWith("$key/") }
  }

  /** Deletes whatever sits at [target] (a file or a symlink, never the contents behind a symlink). */
  private fun removeEntry(target: File) {
    val path = target.toPath()

    if (Files.isSymbolicLink(path) || target.exists()) {
      runCatching { Files.deleteIfExists(path) }
    }
  }

  companion object {
    /** Drops `./` and leading slashes, and rejects anything with a `..` segment or an empty result. */
    fun sanitize(name: String): String? {
      val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

      if (parts.isEmpty() || parts.any { it == ".." }) return null

      return parts.joinToString("/")
    }

    fun cString(bytes: ByteArray, offset: Int, length: Int): String {
      var end = offset

      while (end < offset + length && bytes[end] != 0.toByte()) end++

      return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    fun parseNumber(bytes: ByteArray, offset: Int, length: Int): Long {
      if (bytes[offset].toInt() and 0x80 != 0) {
        var value = (bytes[offset].toInt() and 0x7F).toLong()

        for (i in 1 until length) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)

        return value
      }

      val text = cString(bytes, offset, length).trim()

      return if (text.isEmpty()) 0 else text.toLong(8)
    }

    /** PAX records are `<length> <key>=<value>\n`; the length counts its own digits. */
    fun parsePax(text: String): Map<String, String> {
      val out = HashMap<String, String>()
      val raw = text.toByteArray(Charsets.UTF_8)
      var i = 0

      while (i < raw.size) {
        var space = i

        while (space < raw.size && raw[space] != ' '.code.toByte()) space++

        val length = String(raw, i, space - i, Charsets.UTF_8).toIntOrNull() ?: break

        if (length <= 0 || i + length > raw.size) break

        val record = String(raw, space + 1, i + length - space - 1, Charsets.UTF_8).trimEnd('\n')
        val eq = record.indexOf('=')

        if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)

        i += length
      }

      return out
    }
  }
}

/** Deletes a directory tree without following symlinks. */
object Fs {
  fun deleteTree(root: File) {
    val toDelete = ArrayDeque<File>()
    val stack = ArrayDeque<File>()

    stack.addLast(root)

    while (stack.isNotEmpty()) {
      val dir = stack.removeLast()

      if (!dir.exists()) continue

      toDelete.addLast(dir)

      val children = dir.listFiles() ?: continue

      for (child in children) {
        if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
          runCatching { java.nio.file.Files.deleteIfExists(child.toPath()) }
        } else if (child.isDirectory) {
          stack.addLast(child)
        } else {
          runCatching { child.delete() }
        }
      }
    }

    while (toDelete.isNotEmpty()) {
      runCatching { toDelete.removeLast().delete() }
    }
  }
}

/** Reports how many bytes of the underlying stream were consumed. */
class CountingInputStream(source: InputStream, private val onBytes: (Long) -> Unit) : java.io.FilterInputStream(source) {
  private var total = 0L
  private var lastReport = 0L

  override fun read(): Int {
    val b = super.read()

    if (b >= 0) add(1)

    return b
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    val n = super.read(b, off, len)

    if (n > 0) add(n.toLong())

    return n
  }

  private fun add(n: Long) {
    total += n

    if (total - lastReport >= 256 * 1024) {
      lastReport = total
      onBytes(total)
    }
  }
}
