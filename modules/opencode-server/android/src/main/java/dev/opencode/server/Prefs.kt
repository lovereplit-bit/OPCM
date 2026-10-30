package dev.opencode.server

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * The self-generated identity of this install, kept in plain preferences like
 * OCM does: the local server password (random, created once, never shown in
 * the UI), a friendly nickname for the connection profile, and the loopback
 * port the server settled on.
 */
class Prefs(context: Context) {
  private val sp: SharedPreferences = context.getSharedPreferences("opencode-server", Context.MODE_PRIVATE)

  /** The password the local server and the app's own client share. Random, created once. */
  val password: String get() = secret("password", 24)

  /** A friendly auto-generated nickname, used as the connection profile name. */
  val nick: String get() = sp.getString("nick", null) ?: run {
    val value = Nicknames.generate()
    sp.edit().putString("nick", value).apply()
    value
  }

  /**
   * The port the server prefers; moved and remembered when it was taken.
   * Zero means no port has been picked yet: the next start takes a random
   * free one instead of parking every install on the same shared number.
   */
  var port: Int
    get() = sp.getInt("port", DEFAULT_PORT)
    set(value) = sp.edit().putInt("port", value).apply()

  /**
   * True once the server actually answered on the stored port. A port that
   * was only written during failed starts is not proof of anything, so the
   * next start draws a fresh random one instead of clinging to it.
   */
  var portProven: Boolean
    get() = sp.getBoolean("port_proven", false)
    set(value) = sp.edit().putBoolean("port_proven", value).apply()

  /** The personal OpenCode API key the server should send requests under, when set. */
  var apiKey: String?
    get() = sp.getString("api_key", null)?.takeIf { it.isNotBlank() }
    set(value) = sp.edit().putString("api_key", value?.trim()?.take(200)).apply()

  /** Compatibility mode for Android builds that kill proot over a system call. */
  var compat: Boolean
    get() = sp.getBoolean("compat", false)
    set(value) = sp.edit().putBoolean("compat", value).apply()

  /** Keep the CPU awake while the server runs (best effort wake lock). */
  var keepAwake: Boolean
    get() = sp.getBoolean("keep_awake", true)
    set(value) = sp.edit().putBoolean("keep_awake", value).apply()

  /** Keep Wi-Fi alive while the server runs (best effort Wi-Fi lock). */
  var keepWifi: Boolean
    get() = sp.getBoolean("keep_wifi", true)
    set(value) = sp.edit().putBoolean("keep_wifi", value).apply()

  private fun secret(key: String, bytes: Int): String {
    sp.getString(key, null)?.let { return it }

    val raw = ByteArray(bytes)
    SecureRandom().nextBytes(raw)
    val value = raw.joinToString("") { "%02x".format(it) }
    sp.edit().putString(key, value).apply()
    return value
  }

  companion object {
    /** "No port chosen yet" - the start-up code replaces it with a random free port. */
    const val DEFAULT_PORT = 0

    /** Where the port walk starts when even the kernel could not hand out a random port. */
    const val DEFAULT_PORT_FALLBACK = 16384
  }
}

/** Friendly random nicknames: "adjective animal number", e.g. "swift-otter-42". */
object Nicknames {
  private val adjectives = listOf(
    "swift", "cosmic", "lucky", "brave", "clever", "sunny", "quiet", "nimble",
    "bold", "calm", "eager", "fuzzy", "gentle", "happy", "jolly", "kind",
    "lively", "merry", "noble", "proud", "rapid", "serene", "tidy", "vivid",
    "witty", "zesty", "amber", "arctic", "azure", "coral", "crimson", "emerald",
    "indigo", "ivory", "jade", "lavender", "onyx", "opal", "pearl", "quartz",
  )

  private val animals = listOf(
    "otter", "fox", "panda", "koala", "falcon", "heron", "ibex", "lynx",
    "marten", "narwhal", "ocelot", "puffin", "quail", "raven", "seal", "tapir",
    "urchin", "viper", "walrus", "yak", "zebu", "badger", "crane", "dolphin",
    "eagle", "ferret", "gecko", "hare", "lemur", "marmot", "newt", "orca",
    "puma", "stoat", "tiger", "vole", "wombat", "beaver", "catfish", "dingo",
  )

  fun generate(random: SecureRandom = SecureRandom()): String {
    val adjective = adjectives[random.nextInt(adjectives.size)]
    val animal = animals[random.nextInt(animals.size)]
    val number = random.nextInt(90) + 10
    return "$adjective-$animal-$number"
  }
}
