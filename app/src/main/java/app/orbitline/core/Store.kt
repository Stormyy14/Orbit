package app.orbitline.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny JSON-file persistence. Writes are debounced and happen off the main thread.
 * [sub] is a folder under `orbit/`; each profile keeps its data in its own one.
 */
class Store(context: Context, private val scope: CoroutineScope, sub: String = "") {
    private val dir = File(context.filesDir, if (sub.isEmpty()) "orbit" else "orbit/$sub").apply { mkdirs() }
    private val pending = HashMap<String, Job>()
    @Volatile private var wiped = false

    /** Deletes this store's folder, dropping any writes that haven't happened yet. */
    fun wipe() {
        synchronized(pending) {
            wiped = true
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
        dir.deleteRecursively()
    }

    fun readObject(name: String): JSONObject? = runCatching {
        File(dir, "$name.json").takeIf { it.exists() }?.readText()?.let(::JSONObject)
    }.getOrNull()

    fun readArray(name: String): JSONArray? = runCatching {
        File(dir, "$name.json").takeIf { it.exists() }?.readText()?.let(::JSONArray)
    }.getOrNull()

    /**
     * Debounced write. [produce] runs on an IO thread after the debounce, so callers should pass
     * it immutable snapshots (e.g. `list.toList()`) rather than live Compose state.
     */
    fun write(name: String, debounceMs: Long = 400, produce: () -> String) {
        synchronized(pending) {
            pending[name]?.cancel()
            pending[name] = scope.launch(Dispatchers.IO) {
                delay(debounceMs)
                if (wiped) return@launch
                val text = produce()
                runCatching {
                    val tmp = File(dir, "$name.json.tmp")
                    tmp.writeText(text)
                    tmp.renameTo(File(dir, "$name.json"))
                }
            }
        }
    }
}
