package dev.opencode.server

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The local server's loopback port. When the wanted port is already held by
 * another app on the phone, the next free one is used, so the server starts
 * instead of failing over a busy port.
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
