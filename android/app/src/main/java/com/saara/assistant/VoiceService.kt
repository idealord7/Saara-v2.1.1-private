package com.saara.assistant
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Gemini Live text intelligence with device-local storage. Private mic, camera, screen and notification payloads are not uploaded. */
class VoiceService : Service() {
  companion object { var instance: VoiceService? = null }
  private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null
  @Volatile private var running = false
  @Volatile private var ready = false
  @Volatile private var speakingUntil = 0L
  @Volatile var muted = false
  @Volatile private var micStarted = false
  @Volatile private var micEnabled = true
  private var micThread: Thread? = null
  private var audioRecord: AudioRecord? = null
  private var retries = 0
  private var track: AudioTrack? = null
  private val audioQ = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
  private var bubble: Bubble? = null
  private var overlay: Overlay? = null
  private var cam: CamShare? = null
  private var screen: ScreenShare? = null
  private var gotSpeech = false
  private val main = Handler(Looper.getMainLooper())
  private val busListener: () -> Unit = { updateOverlay() }

  fun isMicOn(): Boolean = micEnabled && micStarted
  fun toggleMic() {
    micEnabled = !micEnabled
    Prefs.plain(this).edit().putBoolean("mic_enabled", micEnabled).apply()
    if (micEnabled) { if (ready) startMic(); Bus.add("system", "Microphone ON. Your live voice query is sent to Gemini; local contacts and saved records are never attached.", true) }
    else { stopMic(); Bus.add("system", "Microphone muted.", true) }
    Bus.ping()
  }

  override fun onBind(i: Intent?): IBinder? = null

  private fun notif(): Notification {
    val stop = PendingIntent.getService(this, 0, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val sharing = listOfNotNull(if (Bus.camOn) "camera" else null, if (Bus.screenOn) "screen" else null)
    val title = if (sharing.isEmpty()) "Saara is listening" else "Saara is listening • sharing ${sharing.joinToString(" & ")}"
    return Notification.Builder(this, "saara").setContentTitle(title).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(open).addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build()).build()
  }

  /** (Re)declares the foreground service types that are currently needed. */
  private fun fg() {
    try {
      if (Build.VERSION.SDK_INT >= 30) {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Bus.camOn) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Bus.screenOn) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(1, notif(), t)
      } else startForeground(1, notif())
    } catch (e: Exception) { Bus.add("system", "Foreground service: ${e.message}", true) }
  }

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    Bus.init(this)
    when (i?.action) {
      "STOP" -> { stopSelf(); return START_NOT_STICKY }
      "CAM_START", "CAM_STOP", "SCREEN_START", "SCREEN_STOP" -> { if (!running) { stopSelf(); return START_NOT_STICKY }; share(i!!); return START_STICKY }
    }
    if (running) return START_STICKY
    running = true; instance = this; Bus.running = true
    micEnabled = Prefs.plain(this).getBoolean("mic_enabled", true)
    migrateFacts(this)
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("saara", "Saara", NotificationManager.IMPORTANCE_LOW))
    fg()
    // USAGE_MEDIA => loudspeaker (USAGE_VOICE_COMMUNICATION is routed to the earpiece). Also makes the volume tools (STREAM_MUSIC) control Saara's voice.
    val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    track = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(maxOf(48000, minBuf)).build().also { it.play() }
    // Separate playback thread so a full audio buffer never blocks the websocket (tool calls / interruptions stay responsive).
    Thread {
      while (running) {
        val b: ByteArray = (try { audioQ.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }) ?: continue
        try { track?.write(b, 0, b.size) } catch (e: Exception) {}
      }
    }.start()
    refreshBubble()
    Bus.listen(busListener)
    Bus.add("system", "Connecting…", true)
    connect()
    return START_STICKY
  }

  // ---------- floating bubble + caption overlay ----------
  fun refreshBubble() {
    if (Prefs.on(this, "f_bubble", false)) {
      if (bubble == null) bubble = Bubble(this) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      bubble?.show()
    } else bubble?.hide()
    if (!Prefs.on(this, "f_overlay", true)) overlay?.hide()
  }

  private fun updateOverlay() {
    if (!running) return
    if (!Prefs.on(this, "f_overlay", true) || !Settings.canDrawOverlays(this)) { overlay?.hide(); return }
    if (overlay == null) overlay = Overlay(this)
    if (Bus.camOn) {
      overlay?.show("📷 LIVE CAMERA SHARING ON\nCamera is active on this phone. Frames are not uploaded to Gemini.", true)
      return
    }
    if (MainActivity.visible) { overlay?.hide(); return }
    val m = synchronized(Bus) { Bus.msgs.lastOrNull() } ?: return
    val icon = when (m.role) { "you" -> "🧑 "; "saara" -> "✨ "; "tool" -> "⚙️ "; else -> "ℹ️ " }
    overlay?.show(icon + m.text.takeLast(220))
  }

  // ---------- camera / screen sharing ----------
  private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun share(i: Intent) {
    when (i.action) {
      "CAM_START" -> startCam(i.getBooleanExtra("front", false))
      "CAM_STOP" -> stopCam(true)
      "SCREEN_START" -> startScreen(i.getIntExtra("code", 0), projData(i))
      "SCREEN_STOP" -> stopScreen(true)
    }
  }
  @Suppress("DEPRECATION")
  private fun projData(i: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java) else i.getParcelableExtra("data")

  private fun tellModel(t: String) { if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) }

  private fun startMic() {
    if (!running || !ready || !micEnabled || micStarted) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      Bus.add("system", "Microphone permission is missing. Allow Microphone in Android app permissions.", true); return
    }
    try {
      val rate = 16000
      val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val rec = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(min, rate)).build()
      if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Bus.add("system", "Microphone could not initialize on this phone.", true); return }
      audioRecord = rec; rec.startRecording(); micStarted = true
      micThread = Thread {
        val buf = ByteArray(6400)
        while (running && micEnabled && micStarted) {
          val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
          if (n > 0 && ready) {
            val chunk = ByteArray(n); System.arraycopy(buf, 0, chunk, 0, n)
            val data = Base64.encodeToString(chunk, Base64.NO_WRAP)
            // ADD this fixed code block:
val payload = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", data).put("mime_type", "audio/pcm;rate=16000")))
ws?.send(payload.toString())

          } else if (n < 0) break
        }
      }.also { it.name = "saara-microphone"; it.start() }
      Bus.add("system", "Microphone is active. Audio is streamed to Gemini for live processing; personal device databases remain local.", true)
    } catch (e: Exception) { micStarted = false; Bus.add("system", "Microphone error: ${e.message}", true) }
  }

  private fun stopMic() {
    micStarted = false
    try { audioRecord?.stop() } catch (_: Exception) {}
    try { audioRecord?.release() } catch (_: Exception) {}
    audioRecord = null; micThread = null
  }

  private fun startCam(front: Boolean) {
    if (!has(Manifest.permission.CAMERA)) { Bus.add("system", "Camera permission missing. Allow it in the Saara app.", true); return }
    val was = Bus.camOn
    Bus.camOn = true; fg()
    val c = cam ?: CamShare(this, { sendFrame(it) }, { m -> Bus.add("system", m, true); main.post { stopCam(false) } }).also { cam = it }
    val err = c.start(front)
    if (err != null) { Bus.add("system", err, true); stopCam(false); return }
    Bus.ping()
    if (!was) { Bus.add("system", "Camera sharing is ON (${if (front) "front" else "back"}).", true); Bus.add("system", "Camera preview is local-only; camera frames are not sent to Gemini.", true) }
  }
  private fun stopCam(say: Boolean) {
    cam?.stop(); cam = null
    if (Bus.camOn) { Bus.camOn = false; fg(); if (say) { Bus.add("system", "Camera sharing stopped.", true); tellModel("[System] Camera sharing stopped. Do not reply.") } }
    Bus.ping()
  }
  private fun startScreen(code: Int, data: Intent?) {
    if (data == null || code == 0) { Bus.add("system", "Screen sharing was not allowed.", true); return }
    Bus.screenOn = true; fg()
    val s = screen ?: ScreenShare(this, { sendFrame(it) }, { main.post { stopScreen(true) } }).also { screen = it }
    val err = s.start(code, data)
    if (err != null) { Bus.add("system", err, true); stopScreen(false); return }
    Bus.add("system", "Screen sharing is ON.", true)
    Bus.add("system", "Screen capture is local-only; screen frames are not sent to Gemini.", true)
  }
  private fun stopScreen(say: Boolean) {
    screen?.stop(); screen = null
    if (Bus.screenOn) { Bus.screenOn = false; fg(); if (say) { Bus.add("system", "Screen sharing stopped.", true); tellModel("[System] Screen sharing stopped. Do not reply.") } }
    Bus.ping()
  }

  /** Privacy mode: camera/screen frames stay on-device and are never uploaded to Gemini. */
  fun sendFrame(jpeg: ByteArray) { /* intentionally not transmitted */ }

  // ---------- Gemini Live connection ----------
  private fun connect() {
    if (!running) return
    ready = false
    Bus.setStatus("Connecting…")
    val key = Prefs.secure(this).getString("apiKey", "") ?: ""
    if (key.isBlank()) { Bus.add("system", "API key missing. Add it in Settings.", true); stopSelf(); return }
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
    ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onOpen(w: WebSocket, r: Response) { w.send(setup()) }
      override fun onMessage(w: WebSocket, text: String) { handle(text) }
      override fun onMessage(w: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
      override fun onClosing(w: WebSocket, code: Int, reason: String) { w.close(1000, null) }
      override fun onClosed(w: WebSocket, code: Int, reason: String) { lost("Disconnected ($code) $reason") }
      override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { lost("Connection failed: ${t.message} ${r?.code ?: ""}") }
    })
  }

  private fun lost(msg: String) {
    ready = false
    stopMic()
    if (!running) return
    Bus.add("system", msg, true)
    if (++retries > 3) { Bus.add("system", "Stopped after repeated failures. Check API key / model name in Settings.", true); main.post { stopSelf() }; return }
    Bus.setStatus("Reconnecting…")
    main.postDelayed({ connect() }, 3000)
  }

    private fun setup(): String {
    val p = Prefs.plain(this)
    val model = p.getString("model", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview"
    val voice = p.getString("voice", "Aoede") ?: "Aoede"
    val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(Date())
    val ins = "You are Saara, an elegant, proactive, warm FEMALE voice assistant (use feminine forms when speaking Hindi, e.g. 'main kar rahi hoon'). Be concise. " + langRule(this) + " " +
      "Current local time: $now. " +
      "You can use device_control and local task tools for permitted phone actions. Do not request or reveal contacts, SMS, call logs, notifications, local knowledge, learning, chat history, or sheet contents. URL imports are handled locally by the app. Never ask for private data to be pasted into Gemini. " +
      "If a tool result says a feature is OFF or an Android permission is missing, tell the user in one short sentence exactly what to switch on or grant in the Saara app; do not just say you cannot. " +
      // --- autonomous agent behaviour ---
      "AUTONOMY (very important): for any task with several steps (open an app, search, type, send, post, take a photo, answer a call...), complete the WHOLE task yourself by chaining tool calls one after another. " +
      "Do not claim you can inspect the user's screen or local databases. Screen automation content is not sent to Gemini in privacy mode. " +
      "Only ask the user when information is truly missing (which of two contacts, what text to write) or before irreversible/sensitive actions like payments or deleting data. " +
      "Do not narrate each step; when everything is done, say one short sentence with the result. " +
      "Follow the user's command in the simplest way: never make it complicated, never lecture, find a way to get it done. " +
      "Photos: call device_control take_photo (it presses the shutter by itself; text=front for selfie). Calls: answer_call / end_call. " +
      "Notifications and scheduled-task text are private local data and are never forwarded to Gemini. " +
      // --- learning ---
      "Do not store personal facts in Gemini. The app stores Learning and Knowledge locally on the phone. " +
      // --- shop ---
      "Do not ask for private shop ledger details. Local ledger data is not sent to Gemini. " +
      "PRIVACY: Never ask for, reveal, infer, or request private local data. Contacts, SMS, call logs, chat history, learning facts, knowledge base, and sheet contents are stored only on this device. No private database content is provided in this session. Do not claim to have read local private data. " +
      "Camera and screen frames are not sent to Gemini in privacy mode. Links and spreadsheet data are fetched and stored locally by the app, not by Gemini."
    val s = JSONObject()
      .put("model", "models/$model")
      .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", ins))))
      .put("tools", Tools.declarations())
      .put("inputAudioTranscription", JSONObject())
      .put("outputAudioTranscription", JSONObject())
    return JSONObject().put("setup", s).toString()
  }

  private fun handle(s: String) {
    val m = try { JSONObject(s) } catch (e: Exception) { return }
    if (m.has("setupComplete")) { ready = true; retries = 0; Bus.setStatus("Connected · Gemini active"); if (micEnabled) startMic() else Bus.add("system", "Gemini connected. Microphone is muted.", true) }
    if (m.has("error")) Bus.add("system", "Gemini error: " + m.optString("error"), true)
    val sc = m.optJSONObject("serverContent")
    if (sc != null) {
      val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
      if (parts != null) for (i in 0 until parts.length()) {
        val inl = parts.getJSONObject(i).optJSONObject("inlineData")
        if (inl != null) play(Base64.decode(inl.getString("data"), Base64.DEFAULT))
      }
      sc.optJSONObject("inputTranscription")?.let { gotSpeech = true; Bus.add("you", it.optString("text")) }
      sc.optJSONObject("outputTranscription")?.let { Bus.add("saara", it.optString("text")) }
      if (sc.optBoolean("interrupted")) { audioQ.clear(); track?.pause(); track?.flush(); track?.play(); speakingUntil = 0 }
      if (sc.optBoolean("turnComplete")) {
        Bus.endTurn()
        if (gotSpeech) { gotSpeech = false; try { Db.get(this).usage("command", "turn") } catch (_: Exception) {} }
      }
    }
    val calls = m.optJSONObject("toolCall")?.optJSONArray("functionCalls")
    if (calls != null) Thread {
      val out = JSONArray()
      for (i in 0 until calls.length()) {
        val c = calls.getJSONObject(i)
        val name = c.getString("name")
        val args = c.optJSONObject("args") ?: JSONObject()
        Bus.add("tool", "$name $args", true)
        val r = Tools.run(this, name, args)
        Bus.add("tool", "→ $r", true)
        out.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", JSONObject().put("result", "Action handled on device. No local data or tool output is shared with Gemini.")))
      }
      ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString())
    }.start()
  }

  private fun play(pcm: ByteArray) {
    if (track == null) return
    speakingUntil = maxOf(speakingUntil, System.currentTimeMillis()) + pcm.size / 48
    audioQ.offer(pcm)
  }

  fun sendText(t: String) {
    val q = t.lowercase(Locale.ROOT)
    val sensitivePhrase = Prefs.on(this, "privacy_mode", true) && listOf(
      "my contact", "my contacts", "my phone", "my address", "call history", "call log", "sms history", "my messages", "message from", "my email", "my whatsapp", "my saved learning", "my knowledge", "my sheet", "my spreadsheet", "my call", "dial ", "send sms", "remind me", "schedule ",
      "আমার নম্বর", "আমার কন্টাক্ট", "আমার যোগাযোগ", "আমার মেসেজ", "আমার কল", "আমার ঠিকানা", "ব্যাংক অ্যাকাউন্ট", "मेरा नंबर", "मेरे संपर्क", "मेरे मैसेज", "मेरी कॉल हिस्ट्री", "मेरा पता", "बैंक खाता"
    ).any { q.contains(it) }
    val sensitive = sensitivePhrase || listOf("phone number", "home address", "bank account", "account number").any { q.contains(it) } ||
      Regex("\\b[+0-9][0-9 ()-]{7,}\\b").containsMatchIn(t) ||
      Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(t)
    Bus.add("you", t, true)
    if (sensitive) {
      Bus.add("saara", "This request appears to involve private information or a personal device action. I kept it on this phone and did not send it to Gemini.", true)
      return
    }
    try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
    if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
    else Bus.add("saara", "Gemini is not connected yet. Check the API key and connection in Settings.", true)
  }

  /** Fired by the scheduler. Returns false when Saara is not connected (the caller then shows a notification). */
  fun runScheduled(text: String): Boolean {
    Bus.add("system", "Scheduled task fired at ${SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())}: $text", true)
    // Scheduled task text can contain private details; never forward it to Gemini.
    return false
  }

  fun onNotification(key: String, pkg: String, title: String, text: String) {
    // Notification content is private local data. Never forward it to Gemini.
    Bus.add("system", "A notification arrived from $pkg. Its contents were kept on this device.", true)
  }


  override fun onDestroy() {
    running = false; ready = false
    stopMic()
    Bus.unlisten(busListener)
    audioQ.clear()
    cam?.stop(); cam = null; Bus.camOn = false
    screen?.stop(); screen = null; Bus.screenOn = false
    ws?.close(1000, null)
    track?.release()
    bubble?.hide(); overlay?.hide()
    instance = null; Bus.running = false
    Bus.add("system", "Stopped.", true); Bus.setStatus("Stopped")
    super.onDestroy()
  }
}
