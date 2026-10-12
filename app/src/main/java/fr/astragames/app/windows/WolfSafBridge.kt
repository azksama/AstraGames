package fr.astragames.app.windows

/** Test entry point into the same interposer loaded by Wine/Box64. No vendor payload. */
@androidx.annotation.Keep
internal object WolfSafBridge {
    init { System.loadLibrary("astra_exec_bridge") }
    external fun probe(root: String, socket: String): String
    external fun stage(root: String, socket: String, path: String, value: String, commit: Boolean = false,
                       beforeCommit: Runnable? = null): String
    external fun read(root: String, socket: String, path: String): String
}
