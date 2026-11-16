package it.watchcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaActionSound
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WatchCam: controlla foto e video del telefono dai comandi multimediali dell'orologio.
 *   Play / Pausa  -> scatta foto
 *   Avanti        -> AVVIA video (se sta già registrando non fa nulla)
 *   Indietro      -> FERMA video (se non sta registrando non fa nulla)
 * Il cambio camera resta solo sul pulsante a schermo.
 */
class MainActivity : ComponentActivity() {

    private enum class Cmd { PHOTO, VIDEO_START, VIDEO_STOP }

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var blackOverlay: View
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var dimmed = false

    private var session: MediaSession? = null
    private var lastCmdAt = 0L
    private val sound by lazy { MediaActionSound() }

    @Volatile private var silentRunning = false
    private var silentTrack: AudioTrack? = null
    private var silentThread: Thread? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true) startCamera()
            else setStatus("Permesso fotocamera negato")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        buildUi()
        setupMediaSession()
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun buildUi() {
        previewView = PreviewView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(24, 24, 24, 8)
            text = "Pronto. Orologio: Play/Pausa = foto, Avanti = avvia video, Indietro = ferma video"
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Foto") { takePhoto() })
            addView(btn("Rec") { startVideo() })
            addView(btn("Stop") { stopVideo() })
            addView(btn("Gira") { flipCamera() })
            addView(btn("Scuro") { toggleDim() })
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            addView(status)
            addView(row)
        }
        val content = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        // Schermata nera a pieno schermo: sull'OLED i pixel neri sono spenti. Tocca per riaccendere.
        blackOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { toggleDim() }
        }
        val outer = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(blackOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(outer)
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
            videoCapture = VideoCapture.withOutput(recorder)
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            } catch (e: Exception) {
                setStatus("Errore fotocamera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/WatchCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        sound.play(MediaActionSound.SHUTTER_CLICK)
        ic.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) =
                    setStatus("Foto salvata in Immagini/WatchCam")
                override fun onError(e: ImageCaptureException) =
                    setStatus("Errore foto: ${e.message}")
            })
    }

    /** Avvia la registrazione. Se è già in corso non fa nulla. */
    private fun startVideo() {
        if (recording != null) return
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/WatchCam")
        }
        val out = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        val pending = vc.output.prepareRecording(this, out).apply {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) withAudioEnabled()
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> setStatus("● REGISTRAZIONE IN CORSO")
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setStatus(
                        if (ev.hasError()) "Errore video (${ev.error})"
                        else "Video salvato in Film/WatchCam"
                    )
                }
                else -> {}
            }
        }
    }

    /** Ferma la registrazione. Se non è in corso non fa nulla. */
    private fun stopVideo() {
        val current = recording ?: return
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        current.stop()
        recording = null
    }

    private fun flipCamera() {
        if (recording != null) { setStatus("Ferma il video prima di cambiare camera"); return }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    private fun toggleDim() {
        dimmed = !dimmed
        blackOverlay.visibility = if (dimmed) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness =
            if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (dimmed) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setStatus(msg: String) { status.text = msg }

    // ---------- Comandi dall'orologio (MediaSession) ----------

    private fun command(c: Cmd) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCmdAt < 500) return // evita doppio comando (evento + callback)
        lastCmdAt = now
        runOnUiThread {
            when (c) {
                Cmd.PHOTO -> takePhoto()
                Cmd.VIDEO_START -> startVideo()
                Cmd.VIDEO_STOP -> stopVideo()
            }
            publishPlaying() // mantiene lo stato "in riproduzione" per i prossimi comandi
        }
    }

    private fun setupMediaSession() {
        val s = MediaSession(this, "WatchCam")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = mediaButtonIntent.getParcelableExtra(
                    Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
                ) ?: return false
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> command(Cmd.PHOTO)
                        KeyEvent.KEYCODE_MEDIA_NEXT -> command(Cmd.VIDEO_START)
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> command(Cmd.VIDEO_STOP)
                    }
                }
                return true
            }
            override fun onPlay() = command(Cmd.PHOTO)
            override fun onPause() = command(Cmd.PHOTO)
            override fun onSkipToNext() = command(Cmd.VIDEO_START)
            override fun onSkipToPrevious() = command(Cmd.VIDEO_STOP)
        })
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "WatchCam")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Play=foto  Avanti=rec  Indietro=stop")
                .build()
        )
        session = s
    }

    private fun publishPlaying() {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
    }

    /** Audio silenzioso in loop: serve perché Android invii i tasti multimediali alla nostra sessione. */
    private fun startSilentAudio() {
        if (silentTrack != null) return
        val rate = 44100
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        silentTrack = track
        silentRunning = true
        silentThread = Thread {
            val buf = ShortArray(minBuf / 2)
            while (silentRunning) track.write(buf, 0, buf.size)
        }.also { it.start() }
    }

    private fun stopSilentAudio() {
        silentRunning = false
        silentThread?.join(500)
        silentThread = null
        silentTrack?.run { stop(); release() }
        silentTrack = null
    }

    override fun onResume() {
        super.onResume()
        startSilentAudio()
        session?.isActive = true
        publishPlaying()
    }

    override fun onPause() {
        super.onPause()
        stopSilentAudio()
        session?.isActive = false
    }

    override fun onDestroy() {
        recording?.stop()
        session?.release()
        sound.release()
        super.onDestroy()
    }
}
