package app.orbit.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.orbit.MainActivity
import app.orbit.R

/** What a tab is playing, as shown in the media notification and on the lock screen. */
data class MediaInfo(
    val tabId: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasNext: Boolean,
    val hasPrev: Boolean,
    val art: Bitmap?,
    /** Ghost tabs: the notification stays off the lock screen. */
    val private: Boolean,
)

/**
 * Connects the browser to [MediaService]. The browser publishes what's playing; commands from the
 * notification, lock screen and headset buttons come back through [onCommand].
 */
object MediaCenter {
    const val ACTION_OPEN = "app.orbit.action.OPEN_MEDIA_TAB"
    const val EXTRA_TAB = "tab"

    @Volatile var info: MediaInfo? = null
        private set
    /** "play", "pause", "next", "prev", "seek:<ms>" or "stop". */
    var onCommand: ((String) -> Unit)? = null
    internal var running = false

    fun publish(context: Context, next: MediaInfo) {
        info = next
        val intent = Intent(context, MediaService::class.java).setAction(MediaService.ACTION_UPDATE)
        runCatching {
            // Once the service is in the foreground it can be updated from anywhere; the first
            // start happens when the user presses play, while Orbit is on screen.
            if (running) context.startService(intent) else context.startForegroundService(intent)
        }
    }

    fun clear(context: Context) {
        if (info == null && !running) return
        info = null
        runCatching { context.stopService(Intent(context, MediaService::class.java)) }
    }

    internal fun command(cmd: String) {
        Handler(Looper.getMainLooper()).post { onCommand?.invoke(cmd) }
    }
}

/** Keeps playback alive in the background and shows the media notification. */
class MediaService : Service() {

    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private var foreground = false
    private val idleStop = Runnable {
        MediaCenter.command("stop")
        MediaCenter.clear(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MediaCenter.running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playing media", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for video and music playing in Orbit"
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "Orbit").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = MediaCenter.command("play")
                override fun onPause() = MediaCenter.command("pause")
                override fun onSkipToNext() = MediaCenter.command("next")
                override fun onSkipToPrevious() = MediaCenter.command("prev")
                override fun onSeekTo(pos: Long) = MediaCenter.command("seek:$pos")
                override fun onStop() {
                    MediaCenter.command("stop")
                    MediaCenter.clear(this@MediaService)
                }
            })
            setSessionActivity(openApp(null))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_CMD) ?: return START_NOT_STICKY
                MediaCenter.command(cmd)
                if (cmd == "stop") {
                    MediaCenter.clear(this)
                    return START_NOT_STICKY
                }
            }
        }
        val info = MediaCenter.info
        if (info == null) {
            // Must still enter the foreground once, then leave.
            if (!foreground) startInForeground(placeholder())
            stopSelf()
            return START_NOT_STICKY
        }
        update(info)
        return START_NOT_STICKY
    }

    private fun update(info: MediaInfo) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, info.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, info.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, info.durationMs)
                .apply { info.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (info.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (info.durationMs > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (info.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    info.positionMs, if (info.playing) 1f else 0f, SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        session.setSessionActivity(openApp(info.tabId))
        session.isActive = true

        val n = notification(info)
        if (!foreground) startInForeground(n) else getSystemService(NotificationManager::class.java).notify(ID, n)

        // Paused for a long time: tidy up the notification and let Android reclaim memory.
        main.removeCallbacks(idleStop)
        if (!info.playing) main.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun notification(info: MediaInfo): Notification {
        val playPause = if (info.playing) action(R.drawable.ic_n_pause, "Pause", "pause") else action(R.drawable.ic_n_play, "Play", "play")
        val list = buildList {
            add(action(R.drawable.ic_n_prev, "Previous", "prev"))
            add(playPause)
            if (info.hasNext) add(action(R.drawable.ic_n_next, "Next", "next"))
            add(action(R.drawable.ic_n_close, "Stop", "stop"))
        }
        val compact = if (info.hasNext) intArrayOf(0, 1, 2) else intArrayOf(0, 1)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.orbit_mark)
            .setContentTitle(info.title)
            .setContentText(info.artist)
            .setLargeIcon(info.art)
            .setContentIntent(openApp(info.tabId))
            .setDeleteIntent(commandIntent("stop"))
            .setOngoing(info.playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(if (info.private) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .apply { list.forEach { addAction(it) } }
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact))
            .build()
    }

    private fun placeholder(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.orbit_mark)
        .setContentTitle("Orbit")
        .build()

    private fun startInForeground(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(ID, n)
            }
            foreground = true
        }
    }

    private fun action(icon: Int, title: String, cmd: String) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, commandIntent(cmd)).build()

    private fun commandIntent(cmd: String): PendingIntent = PendingIntent.getService(
        this, cmd.hashCode(),
        Intent(this, MediaService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_CMD, cmd),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(tabId: String?): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java)
            .setAction(MediaCenter.ACTION_OPEN)
            .putExtra(MediaCenter.EXTRA_TAB, tabId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Orbit was swiped away from Recents: stop playing with it.
        MediaCenter.command("stop")
        MediaCenter.clear(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacks(idleStop)
        session.isActive = false
        session.release()
        MediaCenter.running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_UPDATE = "app.orbit.media.UPDATE"
        const val ACTION_COMMAND = "app.orbit.media.COMMAND"
        private const val EXTRA_CMD = "cmd"
        private const val CHANNEL = "media"
        private const val ID = 7
        private const val IDLE_STOP_MS = 10 * 60_000L
    }
}
