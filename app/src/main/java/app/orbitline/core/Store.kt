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

/** Tiny JSON-file persistence. Writes are debounced and happen off the main thread. */
class Store(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "orbit").apply { mkdirs() }
    private val pending = HashMap<String, Job>()

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
                val text = produce()
                val tmp = File(dir, "$name.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(dir, "$name.json"))
            }
        }
    }
}
