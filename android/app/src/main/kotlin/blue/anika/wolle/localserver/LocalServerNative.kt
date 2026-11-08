package blue.anika.wolle.localserver

/**
 * The Rust server's JNI entry points (`src/local_jni.rs`), packaged as `libstricknani_server.so`.
 * Builds without the library (no NDK on the build host) simply do not offer the local-only mode.
 */
internal object LocalServerNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("stricknani_server") }.isSuccess
    }

    /** Starts the server (or returns the running one): `"<port>\n<token>"`. Throws on failure. */
    external fun start(dataDir: String, preferredPort: Int): String

    external fun stop()
}
