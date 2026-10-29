package dev.opencode.server

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.io.File

class ServerInfo : Record {
  @Field var phase: String = "idle"
  @Field var message: String = ""
  @Field var progress: Double = -1.0
  @Field var port: Double = -1.0
  @Field var password: String = ""
  @Field var nick: String = ""
  @Field var version: String = ""
  @Field var installed: Boolean = false
  @Field var running: Boolean = false
  @Field var compat: Boolean = false
  @Field var apiKey: String? = null
}

class RuntimeManifest : Record {
  @Field var version: String = ""
  @Field var sha256: String = ""
  @Field var size: Double = 0.0
  @Field var url: String = ""
}

/**
 * The bridge between React Native and the self-hosted OpenCode server. The
 * app never configures a server by hand: the module generates the password
 * and the nickname once, starts the Linux runtime on first launch and hands
 * the loopback address to the JS side to create the connection profile.
 */
class OpencodeServerModule : Module() {
  private fun context(): android.content.Context =
    requireNotNull(appContext.reactContext ?: appContext.runtime.reactContext)

  private fun paths(): Paths {
    val path = Paths(context()).also { it.ensure() }
    ServerLog.attach(path.serverLog)
    return path
  }

  private fun info(): ServerInfo {
    val path = paths()
    val prefs = Prefs(context())
    val snapshot = ServerState.snapshot

    return ServerInfo().apply {
      phase = snapshot.phase.name.lowercase()
      message = snapshot.message
      progress = (snapshot.progress ?: -1).toDouble()
      port = (snapshot.port ?: prefs.port).toDouble()
      password = prefs.password
      nick = prefs.nick
      version = snapshot.version ?: (path.rootfsStamp.takeIf { it.exists() }?.readText()?.trim() ?: "")
      installed = File(path.rootfs, "usr/local/bin/opencode").exists()
      running = snapshot.phase == Phase.RUNNING
      compat = prefs.compat
      apiKey = prefs.apiKey
    }
  }

  override fun definition() = ModuleDefinition {
    Name("OpencodeServer")

    Events("onServerEvent")

    OnCreate {
      // The listener itself is context-free; the log file attaches lazily on
      // the first paths() call, when a react context is guaranteed.
      ServerState.listen { snapshot ->
        sendEvent(
          "onServerEvent",
          mapOf(
            "phase" to snapshot.phase.name.lowercase(),
            "message" to snapshot.message,
            "progress" to (snapshot.progress ?: -1),
            "port" to (snapshot.port ?: -1),
            "version" to (snapshot.version ?: ""),
          ),
        )
      }
    }

    Function("getServerInfo") { info() }

    Function("isRuntimeInstalled") {
      val path = paths()
      File(path.rootfs, "usr/local/bin/opencode").exists()
    }

    AsyncFunction("start") { manifestUrl: String ->
      val ctx = context()
      ServerService.send(ctx, ServerService.ACTION_START, manifestUrl)
      info()
    }

    AsyncFunction("restart") { manifestUrl: String? ->
      val ctx = context()
      ServerService.send(ctx, ServerService.ACTION_RESTART, manifestUrl)
      info()
    }

    AsyncFunction("stop") {
      val ctx = context()
      ServerService.send(ctx, ServerService.ACTION_STOP)
      info()
    }

    AsyncFunction("setApiKey") { apiKey: String? ->
      val prefs = Prefs(context())
      prefs.apiKey = apiKey?.takeIf { it.isNotBlank() }
      info()
    }

    AsyncFunction("setCompatMode") { enabled: Boolean ->
      val prefs = Prefs(context())
      prefs.compat = enabled
      info()
    }

    AsyncFunction("getLogs") {
      val path = paths()
      val moduleLog = ServerLog.tail()
      val serverLog = runCatching { path.serverLog.readText().takeLast(16 * 1024) }.getOrDefault("")
      "--- module log ---\n$moduleLog\n--- server log ---\n$serverLog"
    }

    AsyncFunction("fetchRuntimeManifest") { manifestUrl: String ->
      // Validates a manifest the same way the installer will, before a long
      // download starts, so the UI can fail fast on a bad source.
      val manifest = RuntimeInstaller(paths()).fetchManifest(manifestUrl)

      RuntimeManifest().apply {
        version = manifest.version
        sha256 = manifest.sha256
        size = manifest.size.toDouble()
        url = manifest.url
      }
    }
  }
}
