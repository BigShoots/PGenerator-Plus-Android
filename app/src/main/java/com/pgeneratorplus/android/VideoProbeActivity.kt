package com.pgeneratorplus.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoProbeActivity : AppCompatActivity() {

 companion object {
  private const val TAG = "VideoProbeActivity"
  private const val DEFAULT_URL = "https://storage.googleapis.com/wvmedia/clear/hevc/tears/tears.mpd"
   const val EXTRA_URL = "video_probe_url"
 }

 private var player: ExoPlayer? = null
 private lateinit var tvLog: TextView
 private lateinit var etUrl: EditText

 override fun onNewIntent(intent: android.content.Intent) {
  super.onNewIntent(intent)
  setIntent(intent)
  val newUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
  if (newUrl.isNotEmpty()) {
   etUrl.setText(newUrl)
   stopPlayback()
   appendLog("NEW_INTENT_URL: $newUrl")
   startPlayback()
  }
 }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContentView(R.layout.activity_video_probe)

  val playerView = findViewById<PlayerView>(R.id.playerView)
  etUrl = findViewById(R.id.etVideoUrl)
  tvLog = findViewById(R.id.tvVideoProbeLog)
  val btnPlay = findViewById<Button>(R.id.btnPlay)
  val btnStop = findViewById<Button>(R.id.btnStop)
  val btnReadLog = findViewById<Button>(R.id.btnReadLog)

   val startupUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty().ifEmpty { DEFAULT_URL }
   etUrl.setText(startupUrl)

  player = ExoPlayer.Builder(this).build().also {
   playerView.player = it
  }

  btnPlay.setOnClickListener { startPlayback() }
  btnStop.setOnClickListener { stopPlayback() }
  btnReadLog.setOnClickListener { refreshLogView() }

  refreshLogView()

    // Auto-start once so the probe runs even without manual D-pad interaction.
    window.decorView.postDelayed({
     if (!isFinishing && !isDestroyed) {
        startPlayback()
     }
    }, 700)
 }

 private fun startPlayback() {
  val p = player ?: return
  val url = etUrl.text.toString().trim()
  if (url.isEmpty()) {
   appendLog("ERROR: URL is empty")
   return
  }

  appendLog("PLAY: $url")
  appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")

  p.clearMediaItems()
  p.addListener(object : Player.Listener {
   override fun onPlaybackStateChanged(playbackState: Int) {
    val stateName = when (playbackState) {
     Player.STATE_IDLE -> "IDLE"
     Player.STATE_BUFFERING -> "BUFFERING"
     Player.STATE_READY -> "READY"
     Player.STATE_ENDED -> "ENDED"
     else -> "UNKNOWN($playbackState)"
    }
    appendLog("STATE: $stateName")

    if (playbackState == Player.STATE_READY) {
     val fi = p.videoFormat
     if (fi != null) {
      appendLog("FORMAT: mime=${fi.sampleMimeType} codecs=${fi.codecs} ${fi.width}x${fi.height} fps=${fi.frameRate}")
      appendLog("COLOR: space=${fi.colorInfo?.colorSpace} transfer=${fi.colorInfo?.colorTransfer} range=${fi.colorInfo?.colorRange}")
      appendLog("HDR_STATIC_INFO_PRESENT=${fi.colorInfo?.hdrStaticInfo != null}")
     }
    }
   }

   override fun onPlayerError(error: PlaybackException) {
    appendLog("ERROR: ${error.errorCodeName} ${error.message}")
   }

   override fun onIsPlayingChanged(isPlaying: Boolean) {
    appendLog("IS_PLAYING: $isPlaying")
   }
  })

  val item = MediaItem.fromUri(url)
  p.setMediaItem(item)
  p.prepare()
  p.playWhenReady = true
 }

 private fun stopPlayback() {
  player?.stop()
  appendLog("STOP")
 }

 private fun appendLog(line: String) {
  val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
  val msg = "[$ts] $line"
  Log.i(TAG, msg)

  try {
   val dir = File(filesDir, "hdr_probe")
   if (!dir.exists()) dir.mkdirs()
   val f = File(dir, "video_probe_latest.txt")
   f.appendText(msg + "\n")
  } catch (_: Exception) { }

  tvLog.append(msg + "\n")
 }

 private fun refreshLogView() {
  try {
   val f = File(File(filesDir, "hdr_probe"), "video_probe_latest.txt")
   if (f.exists()) {
    tvLog.text = f.readText().takeLast(12000)
   } else {
    tvLog.text = "No video probe log yet. Press Play to begin."
   }
  } catch (e: Exception) {
   tvLog.text = "Failed to read log: ${e.message}"
  }
 }

 override fun onStop() {
  super.onStop()
  player?.pause()
 }

 override fun onDestroy() {
  player?.release()
  player = null
  super.onDestroy()
 }
}
