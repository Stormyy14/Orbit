package app.orbit.core

import android.accounts.Account
import android.accounts.AccountManager
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A browser profile: its own spaces, favorites, history, settings and logins.
 * A profile with an [email] is backed up to that Google account (see [GoogleSync]).
 */
data class UserProfile(
    val id: String,
    val name: String,
    val email: String? = null,
    /** The Google account's photo. */
    val photo: String? = null,
    /** Last time this profile's data changed on this device. */
    val changedAt: Long = 0L,
    /** Last successful sync with Google, 0 if never. */
    val syncedAt: Long = 0L,
) {
    val google: Boolean get() = email != null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("email", email).put("photo", photo)
        .put("changed", changedAt).put("synced", syncedAt)

    companion object {
        fun fromJson(o: JSONObject) = UserProfile(
            id = o.getString("id"),
            name = o.optString("name", "Profile"),
            email = o.optString("email").takeIf { it.isNotBlank() && it != "null" },
            photo = o.optString("photo").takeIf { it.startsWith("https://") },
            changedAt = o.optLong("changed"),
            syncedAt = o.optLong("synced"),
        )
    }
}

open class SyncException(message: String) : Exception(message)

/** Drive answered with an HTTP error. */
class DriveException(val code: Int, message: String) : SyncException(message)

/**
 * Saves a profile to the hidden app folder of the user's own Google Drive (free; it counts a few
 * KB against their storage). Sign-in uses Play services authorization, so no client secret ships
 * with the app: Google matches the app by package name and signing certificate.
 */
class GoogleSync(private val activity: ComponentActivity) {

    private val client = Identity.getAuthorizationClient(activity)
    private var onPicked: ((String?) -> Unit)? = null
    private var onConsent: ((Result<AuthorizationResult?>) -> Unit)? = null

    // Registered while the activity is being created (the Browser is built in onCreate).
    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        onPicked?.invoke(r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
        onPicked = null
    }
    private val consent = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        onConsent?.invoke(runCatching { client.getAuthorizationResultFromIntent(r.data) })
        onConsent = null
    }

    /** Shows the system account picker; returns the chosen Google account's email. */
    suspend fun pickAccount(): String? = suspendCancellableCoroutine { c ->
        onPicked = { c.resume(it) }
        val intent = AccountManager.newChooseAccountIntent(null, null, arrayOf(ACCOUNT_TYPE), null, null, null, null)
        runCatching { picker.launch(intent) }.onFailure { onPicked = null; c.resume(null) }
    }

    /**
     * An access token for [email]. With [interactive] the consent screen may be shown;
     * without it, returns null when the user has to approve access again.
     */
    suspend fun token(email: String, interactive: Boolean): String? {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA), Scope(Scopes.EMAIL), Scope(Scopes.PROFILE)))
            .setAccount(Account(email, ACCOUNT_TYPE))
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            throw SyncException(signInError(e.statusCode))
        }
        if (!result.hasResolution()) return result.accessToken
        if (!interactive) return null
        val pending = result.pendingIntent ?: return null
        val approved = suspendCancellableCoroutine { c ->
            onConsent = { c.resume(it) }
            runCatching { consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
                .onFailure { onConsent = null; c.resume(Result.success(null)) }
        }
        // Backing out of the consent screen isn't an error; anything else is worth saying.
        return approved.getOrElse { e ->
            val code = (e as? ApiException)?.statusCode
            if (code == null || code == CommonStatusCodes.CANCELED) null else throw SyncException(signInError(code))
        }?.accessToken
    }

    /** Forgets a token Google rejected, so the next [token] call gets a fresh one. */
    suspend fun clearToken(token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(activity, token) }
        Unit
    }

    private fun signInError(code: Int): String = when (code) {
        CommonStatusCodes.DEVELOPER_ERROR -> "Google sign-in isn't set up for this build"
        CommonStatusCodes.NETWORK_ERROR -> "No connection to Google"
        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.INVALID_ACCOUNT -> "This Google account isn't on this device"
        else -> "Google sign-in failed (error $code)"
    }

    /** The account's first name and photo (either may be null). */
    suspend fun accountInfo(token: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(request("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token))
            o.optString("given_name").ifBlank { o.optString("name") }.takeIf { it.isNotBlank() } to
                o.optString("picture").takeIf { it.startsWith("https://") }
        }.getOrDefault(null to null)
    }

    /** The saved profile, or null if this account has none yet. */
    suspend fun download(token: String): JSONObject? = withContext(Dispatchers.IO) {
        val id = fileId(token) ?: return@withContext null
        JSONObject(request("GET", "$API/files/$id?alt=media", token))
    }

    suspend fun upload(token: String, data: JSONObject) = withContext(Dispatchers.IO) {
        val body = data.toString()
        val id = fileId(token)
        if (id != null) {
            request("PATCH", "$UPLOAD/files/$id?uploadType=media", token, body, "application/json")
        } else {
            val boundary = "orbit" + System.nanoTime()
            val meta = JSONObject().put("name", FILE).put("parents", org.json.JSONArray().put("appDataFolder"))
            val multipart = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$body\r\n--$boundary--"
            request("POST", "$UPLOAD/files?uploadType=multipart", token, multipart, "multipart/related; boundary=$boundary")
        }
        Unit
    }

    private fun fileId(token: String): String? {
        val q = URLEncoder.encode("name='$FILE'", "UTF-8")
        val list = JSONObject(request("GET", "$API/files?spaces=appDataFolder&q=$q&fields=files(id)", token))
        return list.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun request(method: String, url: String, token: String, body: String? = null, type: String? = null): String {
        val conn = Net.open(url, connectMs = 10_000, readMs = 20_000)
        try {
            try {
                conn.requestMethod = method
            } catch (e: ProtocolException) {
                // Some HttpURLConnection builds refuse PATCH; Google APIs accept the override header.
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", method)
            }
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                throw DriveException(code, driveError(code, error))
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Turns Google's error JSON into something a person can act on. */
    private fun driveError(code: Int, body: String?): String {
        val err = runCatching { JSONObject(body ?: "").optJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val message = err?.optString("message").orEmpty()
        return when {
            reason == "accessNotConfigured" || "SERVICE_DISABLED" in body.orEmpty() || "has not been used" in message ->
                "Turn on the Google Drive API in the app's Cloud project"
            code == 401 -> "Google sign-in expired"
            reason == "insufficientPermissions" || "insufficient" in message.lowercase() -> "Drive access wasn't allowed"
            code == 403 -> "Google refused access to Drive"
            else -> "Google Drive error $code"
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    companion object {
        private const val ACCOUNT_TYPE = "com.google"
        private const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE = "orbit-profile.json"
    }
}
