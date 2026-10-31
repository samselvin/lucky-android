package com.sam.lucky

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on helper: listens offline for "Hey Lucky", then hears the command,
 * runs it (alarm / timer / question) and speaks the answer.
 */
class LuckyService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.sam.lucky.STOP"
        const val ACTION_TALK = "com.sam.lucky.TALK"
        @Volatile var running = false
        private val log = ArrayList<String>()
        var onLog: (() -> Unit)? = null

        fun addLog(s: String) {
            synchronized(log) {
                log.add(0, s)
                while (log.size > 40) log.removeAt(log.size - 1)
            }
            Handler(Looper.getMainLooper()).post { onLog?.invoke() }
        }

        fun logText(): String = synchronized(log) { log.joinToString("\n\n") }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var vosk: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var talkWhenReady = false
    private lateinit var brain: Brain

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = Brain(this)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val talk = intent?.action == ACTION_TALK
        if (!running) {
            running = true
            talkWhenReady = talk
            loadModel()
        } else if (talk) {
            onWake()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- notification
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("lucky", "Lucky listening", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getService(this, 2, Intent(this, LuckyService::class.java).setAction(ACTION_TALK), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, LuckyService::class.java).setAction(ACTION_STOP), flags)
        val n = Notification.Builder(this, "lucky")
            .setContentTitle("Lucky is listening")
            .setContentText("Say \"Hey Lucky\", wait for the beep, then speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- wake word (offline)
    private fun loadModel() {
        if (model != null) {
            afterModelReady()
            return
        }
        addLog("Loading voice model…")
        StorageService.unpack(this, "model-en-us", "model",
            { m: Model ->
                model = m
                addLog("Ready. Say \"Hey Lucky\".")
                afterModelReady()
            },
            { e: java.io.IOException -> addLog("Voice model error: ${e.message}") })
    }

    private fun afterModelReady() {
        if (talkWhenReady) {
            talkWhenReady = false
            onWake()
        } else {
            startWakeListening()
        }
    }

    private fun startWakeListening() {
        val m = model ?: return
        if (busy || !running) return
        try {
            stopWakeListening()
            val rec = Recognizer(m, 16000.0f, "[\"hey lucky\", \"hi lucky\", \"okay lucky\", \"[unk]\"]")
            vosk = SpeechService(rec, 16000.0f).also { it.startListening(this) }
        } catch (e: Exception) {
            addLog("Microphone error: ${e.message}")
        }
    }

    private fun stopWakeListening() {
        try {
            vosk?.stop()
            vosk?.shutdown()
        } catch (_: Exception) {
        }
        vosk = null
    }

    private fun heard(json: String?): Boolean {
        val t = try { JSONObject(json ?: "{}").optString("text") } catch (e: Exception) { "" }
        return t.contains("lucky")
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) { if (heard(hypothesis)) onWake() }
    override fun onFinalResult(hypothesis: String?) { if (heard(hypothesis)) onWake() }
    override fun onError(exception: Exception?) {
        addLog("Listening error: ${exception?.message}")
        main.postDelayed({ startWakeListening() }, 3000)
    }
    override fun onTimeout() { startWakeListening() }

    // ---------------------------------------------------------------- command
    private fun onWake() {
        if (busy) return
        busy = true
        stopWakeListening()
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            main.postDelayed({ tg.release() }, 500)
        } catch (_: Exception) {
        }
        main.postDelayed({ listenCommand() }, 350)
    }

    private fun listenCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addLog("Speech recognition isn't available. Install/enable the Google app.")
            finish("Sorry, speech recognition is not available on this phone.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    handle(text)
                }
                override fun onError(error: Int) {
                    addLog("Didn't catch that.")
                    finish(null)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        recognizer?.startListening(i)
    }

    private fun handle(text: String) {
        if (text.isBlank()) {
            finish(null)
            return
        }
        addLog("You: $text")
        val local = Commands.tryLocal(this, text)
        if (local != null) {
            finish(local)
            return
        }
        io.execute {
            val reply = try {
                brain.ask(text)
            } catch (e: Exception) {
                "Sorry, I couldn't reach my brain. ${e.message ?: ""}"
            }
            main.post { finish(reply) }
        }
    }

    private fun finish(reply: String?) {
        if (reply.isNullOrBlank()) {
            busy = false
            startWakeListening()
            return
        }
        addLog("Lucky: $reply")
        speak(reply)
    }

    // ---------------------------------------------------------------- voice out
    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            busy = false
            startWakeListening()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { busy = false; startWakeListening() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { busy = false; startWakeListening() } }
        })
        engine.speak(text.take(800), TextToSpeech.QUEUE_FLUSH, null, "lucky" + System.currentTimeMillis())
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        engine.language = Locale("en", "IN")
        try {
            val female = engine.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                ?.firstOrNull { it.name.contains("female", ignoreCase = true) }
            if (female != null) engine.voice = female
        } catch (_: Exception) {
        }
        ttsReady = true
    }

    override fun onDestroy() {
        running = false
        stopWakeListening()
        recognizer?.destroy()
        tts?.shutdown()
        io.shutdown()
        model?.close()
        model = null
        addLog("Stopped listening.")
        super.onDestroy()
    }
}
