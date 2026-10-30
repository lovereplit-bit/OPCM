package dev.opencode.server

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The local server's loopback port. A fresh install does not sit on one
 * shared number: it takes whatever free port the kernel hands out, so two
 * apps (or two installs) never fight over the same address. When a wanted
 * port turns out to be held by another app on the phone after all, the next
 * free one is used, so the server starts instead of failing over a busy port.
 */
object PortPicker {
  /** How many ports at and above the wanted one are tried before giving up. */
  const val SPAN = 64

  /** The first port at or after [wanted] that [busy] does not report as taken, or null. */
  fun pick(wanted: Int, busy: (Int) -> Boolean): Int? {
    if (wanted !in 1..65535) return null

    var candidate = wanted

    while (candidate < wanted + SPAN) {
      if (candidate > 65535) return null
      if (!busy(candidate)) return candidate

      candidate++
    }

    return null
  }

  /**
   * A random port that is free right now: the kernel picks it itself when a
   * socket binds to port 0, so no two installs land on the same number and
   * nothing collides with a well-known service. Null only if loopback
   * sockets stopped working at all.
   */
  fun randomFree(): Int? = try {
    ServerSocket().use { probe ->
      probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
      probe.localPort
    }
  } catch (_: IOException) {
    null
  }

  /** True when nothing on this phone listens on the loopback [port] right now. */
  fun free(port: Int): Boolean {
    if (port !in 1..65535) return false

    return try {
      ServerSocket().use { probe ->
        probe.reuseAddress = true
        probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
      }

      true
    } catch (_: IOException) {
      false
    }
  }
}
