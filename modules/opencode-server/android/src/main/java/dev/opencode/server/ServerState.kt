package dev.opencode.server

import android.os.Handler
import android.os.Looper

/** The lifecycle the local server walks through. */
enum class Phase {
  IDLE,        // nothing has been asked yet
  DOWNLOADING, // fetching the runtime archive
  INSTALLING,  // unpacking the Linux runtime
  STARTING,    // proot is up, waiting for the server to answer
  RUNNING,     // the server answers on the loopback port
  FAILED,      // the last attempt ended with an error
}

/** A point-in-time snapshot of the local server, safe to hand to JS. */
data class Snapshot(
  val phase: Phase,
  val message: String = "",
  val progress: Int? = null,
  val port: Int? = null,
  val version: String? = null,
)

/**
 * The process-wide state bus the service publishes to and the Expo module
 * subscribes to, mirroring OCM's ServerState/UiBus pair in one object.
 */
object ServerState {
  @Volatile
  private var current = Snapshot(Phase.IDLE)

  private val main = Handler(Looper.getMainLooper())
  private val listeners = ArrayList<(Snapshot) -> Unit>()

  val snapshot: Snapshot get() = current

  @Synchronized
  fun listen(listener: (Snapshot) -> Unit): () -> Unit {
    listeners.add(listener)
    return @Synchronized { listeners.remove(listener) }
  }

  /** Publishes from any thread; listeners always run on the main thread. */
  fun publish(phase: Phase, message: String = "", progress: Int? = null, port: Int? = null, version: String? = null) {
    current = Snapshot(phase, message, progress, port, version ?: current.version)

    val snapshot = current
    val copy = synchronized(this) { listeners.toList() }

    main.post {
      for (listener in copy) {
        runCatching { listener(snapshot) }
      }
    }
  }

  /** Updates only the progress/message fields, keeping the phase. */
  fun progress(message: String, progress: Int?) {
    publish(current.phase, message, progress, current.port)
  }
}
