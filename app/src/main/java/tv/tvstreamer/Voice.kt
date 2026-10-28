package tv.tvstreamer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Voice requests from outside the search bar: the remote's mic / search key and search intents. */
object VoiceBus {
    /** Bumped to open the search bar and start listening. */
    var request by mutableIntStateOf(0)
    /** Last [request] acted on (so a recomposition or activity recreate does not start listening again). */
    var handled = 0
    /** A query from a search intent (android.intent.action.SEARCH etc.), shown in the search bar. */
    var query by mutableStateOf<String?>(null)
}

/** Voice input: the platform SpeechRecognizer when there is one, else the system speech activity, else typed input. */
class VoiceInput(val start: () -> Unit, val stop: () -> Unit, listening: State<Boolean>) {
    val listening by listening
}

const val NO_VOICE = "Voice input isn't available on this device. Type your request instead."

@Composable
fun rememberVoiceInput(onPartial: (String) -> Unit, onText: (String) -> Unit, onStatus: (String) -> Unit): VoiceInput {
    val ctx = LocalContext.current
    val partial by rememberUpdatedState(onPartial)
    val text by rememberUpdatedState(onText)
    val status by rememberUpdatedState(onStatus)
    val listening = remember { mutableStateOf(false) }
    val rec = remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { rec.value?.destroy() }; rec.value = null } }

    fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title, or ask for ideas")

    val activity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        listening.value = false
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (t.isNullOrBlank()) status("No speech heard. Try again or type.") else text(t)
    }
    fun viaActivity(): Boolean = try {
        if (speechIntent().resolveActivity(ctx.packageManager) == null) false
        else { activity.launch(speechIntent()); listening.value = true; status("Listening…"); true }
    } catch (e: ActivityNotFoundException) { false } catch (e: Exception) { false }

    fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { if (!viaActivity()) status(NO_VOICE); return }
        val r = rec.value ?: runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()?.also { rec.value = it }
        if (r == null) { if (!viaActivity()) status(NO_VOICE); return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening.value = true; status("Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status("…") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partial(it) }
            }
            override fun onResults(b: Bundle?) {
                listening.value = false
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) status("No speech heard. Try again or type.") else { status(""); text(t) }
            }
            override fun onError(error: Int) {
                listening.value = false
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> status("Didn't catch that. Try again or type.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> status("Microphone permission is off. Type your request instead.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> status("Voice needs a network connection. Type your request instead.")
                    else -> { runCatching { rec.value?.destroy() }; rec.value = null; if (!viaActivity()) status(NO_VOICE) }
                }
            }
        })
        try { r.startListening(speechIntent()); listening.value = true; status("Listening…") }
        catch (e: Exception) { listening.value = false; if (!viaActivity()) status(NO_VOICE) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else if (!viaActivity()) status("Microphone permission denied. Type your request instead.")
    }
    return remember {
        VoiceInput(
            start = {
                when {
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                    // No in-app recognizer: the system speech screen handles its own microphone permission
                    !SpeechRecognizer.isRecognitionAvailable(ctx) -> if (!viaActivity()) status(NO_VOICE)
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            stop = { runCatching { rec.value?.stopListening() }; listening.value = false },
            listening = listening
        )
    }
}
