package com.sesnot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceService : Service() {

    companion object {
        const val ACTION_STOP = "com.sesnot.app.STOP"
        private const val CHANNEL = "voice_listen"
        private const val NOTIF_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private val parser = CommandParser()
    private var recognizer: SpeechRecognizer? = null
    private var currentNoteId: Long? = null
    private var running = false
    private var errorStreak = 0

    private val recognizerIntent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NoteStore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Bus.show(this, "Servis arka plandan başlatılamadı. Uygulamayı açıp tekrar başlat.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Bus.show(this, "Mikrofon izni yok")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Bus.show(this, "Cihazda konuşma tanıma servisi yok")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Bus.running = true
        Bus.changed()
        Bus.show(this, "Dinleme başladı")
        schedule(0)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        apply(CommandParser.Event.NoteClosed)
        Bus.running = false
        Bus.changed()
        super.onDestroy()
    }

    private fun schedule(delayMs: Long) {
        handler.postDelayed({ begin() }, delayMs)
    }

    private fun begin() {
        if (!running) return
        for (e in parser.tick(System.currentTimeMillis())) apply(e)
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        try {
            r.startListening(recognizerIntent)
        } catch (e: Exception) {
            recreate(500)
        }
    }

    private fun recreate(delayMs: Long) {
        recognizer?.destroy()
        recognizer = null
        schedule(delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            errorStreak = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) handleFinal(text)
            schedule(100)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) Bus.showPartial(text)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedule(100)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Bus.show(this@VoiceService, "Mikrofon izni yok")
                    stopSelf()
                }
                12, 13 -> { // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31+)
                    Bus.show(this@VoiceService, "Türkçe tanıma kullanılamıyor")
                    stopSelf()
                }
                else -> {
                    errorStreak++
                    val delay = minOf(300L * errorStreak, 3000L)
                    recreate(delay)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleFinal(text: String) {
        Bus.show(this, text)
        for (e in parser.feed(text, System.currentTimeMillis())) apply(e)
    }

    private fun apply(e: CommandParser.Event) {
        when (e) {
            CommandParser.Event.NoteOpened -> {
                currentNoteId = NoteStore.create("", "").id
                Bus.show(this, "Yeni not açıldı")
                Bus.changed()
            }
            CommandParser.Event.WritingStarted -> Bus.show(this, "Yazılıyor… bitirmek için \"Dur\"")
            is CommandParser.Event.TextAdded -> {
                currentNoteId?.let { NoteStore.appendBody(it, e.text) }
                Bus.changed()
            }
            CommandParser.Event.NoteClosed -> {
                val id = currentNoteId ?: return
                currentNoteId = null
                val n = NoteStore.get(id)
                if (n == null || n.body.isBlank()) {
                    NoteStore.delete(id)
                    Bus.show(this, "Boş not silindi")
                } else {
                    Bus.show(this, "Not kaydedildi")
                }
                Bus.changed()
            }
            CommandParser.Event.NameArmed -> {
                if (NoteStore.latestId() == null) {
                    Bus.show(this, "Adlandırılacak not yok")
                    parser.reset()
                } else Bus.show(this, "Adı söylemek için \"Yaz\" de")
            }
            CommandParser.Event.NamingStarted -> Bus.show(this, "Adı söyle, sonra \"Dur\"")
            is CommandParser.Event.TitleSet -> {
                val id = NoteStore.latestId()
                if (id != null) {
                    NoteStore.setTitle(id, e.title)
                    Bus.show(this, "Not adı: ${e.title}")
                    Bus.changed()
                }
            }
            CommandParser.Event.NameCancelled -> Bus.show(this, "Ad boş, değiştirilmedi")
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sesli not dinleme", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Durdur", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Sesli not dinliyor")
            .setContentText("\"Yeni Not Aç\" de")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }
}
