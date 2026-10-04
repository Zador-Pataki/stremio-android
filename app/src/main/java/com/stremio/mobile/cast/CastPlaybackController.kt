package com.stremio.mobile.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.Toast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.stremio.mobile.core.StremioCore
import com.stremio.mobile.player.PlayerRuntimeState
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CastPlaybackState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val mediaSource: String? = null,
    val castUrl: String? = null,
    val error: String? = null,
    val endedGeneration: Long = 0L,
)

class CastPlaybackController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val proxy = CastHttpProxy()
    private val _state = MutableStateFlow(CastPlaybackState())
    val state: StateFlow<CastPlaybackState> = _state.asStateFlow()
    private val _runtime = MutableStateFlow(PlayerRuntimeState())
    val runtimeState: StateFlow<PlayerRuntimeState> = _runtime.asStateFlow()

    private var castContext: CastContext? = null
    private var client: RemoteMediaClient? = null
    private var started = false
    private var lastEnded = false

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        _runtime.update { it.copy(positionMs = position.coerceAtLeast(0), durationMs = duration.coerceAtLeast(0), bufferedPositionMs = position.coerceAtLeast(0)) }
    }

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = syncStatus()
        override fun onMetadataUpdated() = syncStatus()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = fail("Could not start Chromecast session ($error).")
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = disconnected()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) { disconnected(); fail("Could not resume Chromecast session ($error).") }
        override fun onSessionSuspended(session: CastSession, reason: Int) { _runtime.update { it.copy(isBuffering = true) } }
    }

    fun start() {
        if (started) return
        started = true
        val ctx = runCatching { CastContext.getSharedInstance(appContext) }.getOrElse {
            fail("Google Cast is unavailable: ${it.message ?: "unknown error"}")
            return
        }
        castContext = ctx
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
    }

    fun load(sourceUri: String, title: String, startPositionMs: Long, durationHintMs: Long) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        if (!session.isConnected) return
        _state.update { it.copy(error = null) }
        lastEnded = false
        scope.launch {
            val prepared = runCatching { withContext(Dispatchers.IO) { prepare(sourceUri) } }.getOrElse {
                fail(it.message ?: "Could not prepare this stream for Chromecast.")
                return@launch
            }
            val remote = session.remoteMediaClient ?: run { fail("Chromecast media channel is unavailable."); return@launch }
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, title) }
            val builder = MediaInfo.Builder(prepared.url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(prepared.mime)
                .setMetadata(metadata)
            if (durationHintMs > 0) builder.setStreamDuration(durationHintMs)
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(builder.build())
                .setCurrentTime(startPositionMs.coerceAtLeast(0))
                .build()
            remote.load(request).setResultCallback { result ->
                if (result.status.isSuccess) {
                    _state.update { it.copy(mediaSource = sourceUri, castUrl = prepared.url, error = null) }
                    _runtime.update { it.copy(isPlaying = true, isBuffering = false, positionMs = startPositionMs.coerceAtLeast(0), durationMs = durationHintMs.coerceAtLeast(0), error = null, ended = false) }
                } else fail("Chromecast rejected this stream.")
            }
        }
    }

    fun play() { client?.play(); _runtime.update { it.copy(isPlaying = true, isBuffering = false) } }
    fun pause() { client?.pause(); _runtime.update { it.copy(isPlaying = false, isBuffering = false) } }
    fun seekTo(ms: Long) {
        val options = MediaSeekOptions.Builder().setPosition(ms.coerceAtLeast(0)).setResumeState(MediaSeekOptions.RESUME_STATE_UNCHANGED).build()
        client?.seek(options)
        _runtime.update { it.copy(positionMs = ms.coerceAtLeast(0)) }
    }
    fun setPlaybackSpeed(speed: Float) {
        val value = speed.coerceIn(0.5f, 2f)
        client?.setPlaybackRate(value.toDouble())
        _runtime.update { it.copy(speed = value) }
    }
    fun endCurrentSession(stopReceiver: Boolean = true) { castContext?.sessionManager?.endCurrentSession(stopReceiver) }

    private fun attach(session: CastSession) {
        detach()
        client = session.remoteMediaClient?.also {
            it.registerCallback(callback)
            it.addProgressListener(progressListener, 500L)
        }
        _state.update { it.copy(connected = true, deviceName = runCatching { session.castDevice?.friendlyName }.getOrNull(), error = null) }
        syncStatus()
    }

    private fun detach() {
        client?.let { remote ->
            runCatching { remote.unregisterCallback(callback) }
            runCatching { remote.removeProgressListener(progressListener) }
        }
        client = null
    }

    private fun disconnected() {
        detach()
        proxy.stop()
        _state.update { it.copy(connected = false, deviceName = null, mediaSource = null, castUrl = null) }
    }

    private fun syncStatus() {
        val remote = client ?: return
        val status = remote.mediaStatus
        val ps = status?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
        val ended = ps == MediaStatus.PLAYER_STATE_IDLE && status?.idleReason == MediaStatus.IDLE_REASON_FINISHED
        val errored = ps == MediaStatus.PLAYER_STATE_IDLE && status?.idleReason == MediaStatus.IDLE_REASON_ERROR
        _runtime.update {
            it.copy(
                isPlaying = ps == MediaStatus.PLAYER_STATE_PLAYING,
                isBuffering = ps == MediaStatus.PLAYER_STATE_BUFFERING || ps == MediaStatus.PLAYER_STATE_LOADING,
                durationMs = (remote.mediaInfo?.streamDuration ?: it.durationMs).coerceAtLeast(0),
                error = if (errored) "Chromecast could not play this stream." else null,
                ended = ended,
            )
        }
        if (ended && !lastEnded) _state.update { it.copy(endedGeneration = it.endedGeneration + 1) }
        lastEnded = ended
        if (errored) fail("Chromecast could not decode or fetch this stream.")
    }

    private data class Prepared(val url: String, val mime: String)

    private fun prepare(source: String): Prepared {
        val uri = Uri.parse(source)
        val mime = detectMime(source) ?: inferMime(uri) ?: "video/mp4"
        val lower = mime.lowercase()
        if (lower.contains("matroska") || lower.contains("msvideo") || lower.contains("quicktime") || lower.contains("ms-wmv")) {
            error("This stream uses a container the standard Chromecast receiver cannot play. Choose an MP4/WebM/TS stream.")
        }
        val local = uri.scheme.equals("http", true) &&
            (uri.host == "127.0.0.1" || uri.host.equals("localhost", true)) &&
            uri.port == 11470
        if (!local) return Prepared(source, mime)
        val ip = findLanIpv4() ?: error("Phone and Chromecast must be on the same Wi-Fi/LAN.")
        return Prepared(proxy.start(source, ip), mime)
    }

    private fun detectMime(source: String): String? {
        val inferred = inferMime(Uri.parse(source))
        if (inferred != null && !source.startsWith(StremioCore.STREAMING_SERVER_BASE)) return inferred
        return runCatching {
            val c = URL(source).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"; c.instanceFollowRedirects = true; c.connectTimeout = 7000; c.readTimeout = 7000
            try { c.responseCode; c.contentType?.substringBefore(';')?.trim()?.lowercase() } finally { c.disconnect() }
        }.getOrNull() ?: inferred
    }

    private fun inferMime(uri: Uri): String? = when {
        uri.lastPathSegment?.lowercase()?.endsWith(".mp4") == true -> "video/mp4"
        uri.lastPathSegment?.lowercase()?.endsWith(".webm") == true -> "video/webm"
        uri.lastPathSegment?.lowercase()?.endsWith(".ts") == true -> "video/mp2t"
        uri.lastPathSegment?.lowercase()?.endsWith(".m3u8") == true -> "application/x-mpegURL"
        uri.lastPathSegment?.lowercase()?.endsWith(".mkv") == true -> "video/x-matroska"
        else -> null
    }

    private fun findLanIpv4(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val networks = buildList { cm.activeNetwork?.let(::add); cm.allNetworks.forEach { if (!contains(it)) add(it) } }
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val address = cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            if (address != null) return address.hostAddress
        }
        return null
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message) }
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }

    override fun close() {
        if (started) castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        detach()
        proxy.close()
        scope.cancel()
        started = false
    }
}
