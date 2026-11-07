package com.aram.voiceengine.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import com.aram.voiceengine.api.EngineResult
import com.aram.voiceengine.api.TtsRequest
import com.aram.voiceengine.core.AtomicGenerationCancellation
import com.aram.voiceengine.runtime.MossTtsEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IshtarPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var currentUri: String? = null
    private var prepared = false
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var engine: MossTtsEngine? = null
    @Volatile private var cancellation: AtomicGenerationCancellation? = null
    @Volatile private var generationWakeLock: PowerManager.WakeLock? = null
    private var generationJob: kotlinx.coroutines.Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentMessage = "تشغيل عشتار"

    companion object {
        const val ACTION_PLAY = "com.aram.voiceengine.app.action.PLAY"
        const val ACTION_TOGGLE = "com.aram.voiceengine.app.action.TOGGLE"
        const val ACTION_STOP = "com.aram.voiceengine.app.action.STOP"
        const val ACTION_SYNTHESIZE = "com.aram.voiceengine.app.action.SYNTHESIZE"
        const val EXTRA_URI = "com.aram.voiceengine.app.extra.URI"
        const val EXTRA_TEXT = "com.aram.voiceengine.app.extra.TEXT"
        const val EXTRA_VOICE_ID = "com.aram.voiceengine.app.extra.VOICE_ID"
        private const val CHANNEL_ID = "ishtar_playback"
        private const val NOTIFICATION_ID = 8417
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val value = intent.getStringExtra(EXTRA_URI)
                if (!value.isNullOrBlank()) startPlayback(value) else stopPlayback()
            }
            ACTION_SYNTHESIZE -> startSynthesis(intent)
            ACTION_TOGGLE -> togglePlayback()
            ACTION_STOP -> stopPlayback()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(value: String) {
        cancelGeneration()
        stopPlayerOnly()
        currentUri = value
        prepared = false
        currentMessage = "يتم تجهيز الملف الصوتي"
        startForeground(NOTIFICATION_ID, buildNotification(currentMessage, false))
        try {
            val next = MediaPlayer()
            player = next
            next.setWakeMode(applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            next.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            next.setOnPreparedListener { media ->
                prepared = true
                media.start()
                currentMessage = "يعمل الصوت في الخلفية"
                updateNotification()
            }
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            next.setDataSource(this, Uri.parse(value))
            next.prepareAsync()
        } catch (_: Exception) { stopPlayback() }
    }

    private fun startSynthesis(intent: Intent) {
        cancelGeneration()
        stopPlayerOnly()
        currentUri = null
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val voiceId = intent.getStringExtra(EXTRA_VOICE_ID)
        val token = AtomicGenerationCancellation()
        cancellation = token
        currentMessage = "تهيئة محرك MOSS"
        startForeground(NOTIFICATION_ID, buildNotification(currentMessage, false))

        val job = serviceScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val thisJob = coroutineContext[kotlinx.coroutines.Job]
            var localEngine: MossTtsEngine? = null
            var localTrack: AudioTrack? = null
            var writer: WavStreamWriter? = null
            var outputFile: File? = null
            var fileCompleted = false
            var completedPlayback = false
            var localWakeLock: PowerManager.WakeLock? = null
            try {
                require(text.isNotBlank()) { "أرسل نصًا غير فارغ إلى خدمة التوليد." }
                require(!voiceId.isNullOrBlank()) { "يتطلب التوليد معرف صوت تختاره صراحةً؛ لا يوجد صوت افتراضي." }
                val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ISHTAR:MossGeneration")
                    .apply { setReferenceCounted(false); acquire(60L * 60L * 1000L) }
                localWakeLock = wakeLock
                generationWakeLock = wakeLock
                val activeEngine = MossTtsEngine(applicationContext, cpuThreads = 2)
                localEngine = activeEngine
                engine = activeEngine
                when (val initialized = activeEngine.initialize()) {
                    is EngineResult.Success -> Unit
                    is EngineResult.Failure -> error(initialized.error.message)
                }
                if (token.isCancelled()) throw CancellationException("cancelled")

                val sampleRate = 48000
                val channels = 2
                val channelMask = AudioFormat.CHANNEL_OUT_STEREO
                val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                require(minBytes > 0) { "AudioTrack لا يدعم 48 kHz stereo PCM على هذا الجهاز (code=$minBytes)." }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                        .setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBytes * 4, sampleRate * channels * 2 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                localTrack = track
                require(track.state == AudioTrack.STATE_INITIALIZED) { "تعذر تهيئة AudioTrack." }
                audioTrack = track
                track.play()

                val generatedFile = File(File(filesDir, "recordings"), "ISHTAR-${System.currentTimeMillis()}.wav")
                outputFile = generatedFile
                val wavWriter = WavStreamWriter(generatedFile, sampleRate, channels)
                writer = wavWriter
                withContext(Dispatchers.Main) {
                    currentMessage = "يولّد الصوت ويبثه تدريجيًا"
                    updateNotification()
                }

                val result = activeEngine.synthesize(TtsRequest(
                    text = text,
                    voiceId = voiceId,
                    cancellation = token,
                )) { chunk ->
                    if (token.isCancelled()) throw CancellationException("cancelled")
                    require(chunk.sampleRate == sampleRate && chunk.channels == channels) {
                        "صيغة الصوت الناتجة ${chunk.sampleRate}Hz/${chunk.channels}ch لا تطابق AudioTrack/WAV."
                    }
                    val pcm = ShortArray(chunk.samples.size) { index ->
                        (chunk.samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    wavWriter.write(pcm)
                    var offset = 0
                    while (offset < pcm.size) {
                        if (token.isCancelled()) throw CancellationException("cancelled")
                        val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) error("فشل بث الصوت إلى AudioTrack (code=$written).")
                        if (written == 0) Thread.sleep(5) else offset += written
                    }
                }
                when (result) {
                    is EngineResult.Success -> {
                        wavWriter.finish()
                        fileCompleted = true
                        // AudioTrack.stop() drains already queued MODE_STREAM PCM before release.
                        track.stop()
                        completedPlayback = true
                        getSharedPreferences("ishtar_settings", MODE_PRIVATE).edit()
                            .putString("last_generated_wav", generatedFile.absolutePath)
                            .putLong("last_generated_at", System.currentTimeMillis()).apply()
                        withContext(Dispatchers.Main) {
                            currentMessage = "اكتمل التوليد · ${result.value.generatedAudioDurationMs / 1000}s"
                            Toast.makeText(this@IshtarPlaybackService, "اكتمل توليد الصوت وحُفظ ملف WAV.", Toast.LENGTH_LONG).show()
                        }
                    }
                    is EngineResult.Failure -> error(result.error.message)
                }
            } catch (error: CancellationException) {
                token.cancel()
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    currentMessage = error.message ?: "تعذر توليد الصوت"
                    Toast.makeText(this@IshtarPlaybackService, currentMessage, Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { writer?.close() } catch (_: Exception) { }
                    if (!fileCompleted) try { outputFile?.delete() } catch (_: Exception) { }
                    if (!completedPlayback) {
                        try { localTrack?.pause() } catch (_: Exception) { }
                        try { localTrack?.flush() } catch (_: Exception) { }
                        try { localTrack?.stop() } catch (_: Exception) { }
                    }
                    try { localTrack?.release() } catch (_: Exception) { }
                    try { localEngine?.release() } catch (_: Exception) { }
                    try { if (localWakeLock?.isHeld == true) localWakeLock?.release() } catch (_: Exception) { }
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    if (audioTrack === localTrack) audioTrack = null
                    if (engine === localEngine) engine = null
                    if (generationWakeLock === localWakeLock) generationWakeLock = null
                    if (cancellation === token) cancellation = null
                    if (generationJob === thisJob) {
                        generationJob = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        generationJob = job
        job.start()
    }

    private fun togglePlayback() {
        val activePlayer = player
        if (activePlayer != null && prepared) {
            try {
                if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.start()
                currentMessage = if (activePlayer.isPlaying) "يعمل الصوت في الخلفية" else "التشغيل متوقف مؤقتًا"
                updateNotification()
            } catch (_: IllegalStateException) { stopPlayback() }
            return
        }
        val track = audioTrack ?: return
        try {
            when (track.playState) {
                AudioTrack.PLAYSTATE_PLAYING -> {
                    track.pause(); currentMessage = "التوليد مستمر · التشغيل متوقف مؤقتًا"
                }
                AudioTrack.PLAYSTATE_PAUSED, AudioTrack.PLAYSTATE_STOPPED -> {
                    track.play(); currentMessage = "يولّد الصوت ويبثه تدريجيًا"
                }
            }
            updateNotification()
        } catch (_: Exception) { stopPlayback() }
    }

    private fun cancelGeneration() {
        val cancelledJob = generationJob
        generationJob = null
        cancellation?.cancel()
        try { engine?.cancelCurrentSynthesis() } catch (_: Exception) { }
        try { audioTrack?.pause() } catch (_: Exception) { }
        try { audioTrack?.flush() } catch (_: Exception) { }
        try { audioTrack?.stop() } catch (_: Exception) { }
        cancelledJob?.cancel()
    }

    private fun releaseGenerationWakeLock() {
        try { generationWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        generationWakeLock = null
    }

    private fun stopPlayerOnly() {
        val active = player
        player = null
        prepared = false
        if (active != null) {
            try { if (active.isPlaying) active.stop() } catch (_: Exception) { }
            try { active.reset() } catch (_: Exception) { }
            try { active.release() } catch (_: Exception) { }
        }
    }

    private fun stopPlayback() {
        cancelGeneration()
        stopPlayerOnly()
        currentUri = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(currentMessage, isPlaying()),
        )
    }

    private fun isPlaying(): Boolean =
        (player?.let { try { it.isPlaying } catch (_: Exception) { false } } == true) ||
            (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "تشغيل عشتار الصوتي", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "إشعار دائم للتحكم بالتشغيل الصوتي في الخلفية"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun servicePending(action: String): PendingIntent {
        val intent = Intent(this, IshtarPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(message: String, playing: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 8418, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleTitle = if (playing) "إيقاف مؤقت" else "استئناف"
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ISHTAR · عشتار")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, toggleIcon), toggleTitle,
                servicePending(ACTION_TOGGLE)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "إيقاف", servicePending(ACTION_STOP)).build())
            .build()
    }

    override fun onDestroy() {
        cancelGeneration()
        releaseGenerationWakeLock()
        stopPlayerOnly()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
