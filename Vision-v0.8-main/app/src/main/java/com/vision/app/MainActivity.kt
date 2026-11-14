package com.vision.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.Locale

private val BG = Color(0xFF05070D)
private val GOLD = Color(0xFFFFD54F)
private val BLUE = Color(0xFF2979FF)
private val BUBBLE_USER = Color(0xFF1565C0)
private val BUBBLE_VISION = Color(0xFFFFD54F)
private val CARD = Color(0xFF111622)
private val GREY = Color(0xFF8A93A3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VisionApp() }
        val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micOk && Config.WAKE_WORD_ENABLED && Config.PICOVOICE_KEY.isNotBlank()) {
            startForegroundService(android.content.Intent(this, WakeWordService::class.java))
        }
    }
}

enum class Screen { VOICE, CHAT, SETTINGS, MEMORY }

/** Permissions the features need. Asked once when the app opens. */
fun neededPermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= 33) {
        list.add("android.permission.READ_MEDIA_IMAGES")
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return list.toTypedArray()
}

@Composable
fun VisionApp() {
    var screen by remember { mutableStateOf(Screen.VOICE) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) { perms.launch(neededPermissions()) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            containerColor = BG, contentColor = Color.White,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (screen == Screen.SETTINGS || screen == Screen.MEMORY) {
                        Text("←", fontSize = 24.sp, modifier = Modifier.clickable { screen = Screen.VOICE }.padding(8.dp))
                    }
                    Text(
                        "V I S I O N", color = GOLD, fontSize = 16.sp, letterSpacing = 4.sp,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Text("🧠", fontSize = 22.sp, modifier = Modifier.clickable { screen = Screen.MEMORY }.padding(8.dp))
                    Text("⚙", fontSize = 22.sp, modifier = Modifier.clickable { screen = Screen.SETTINGS }.padding(8.dp))
                }
            },
            bottomBar = {
                if (screen == Screen.VOICE || screen == Screen.CHAT) {
                    NavigationBar {
                        listOf(Triple(Screen.VOICE, "🎙", "Voice"), Triple(Screen.CHAT, "💬", "Chat"))
                            .forEach { (s, icon, name) ->
                                NavigationBarItem(
                                    selected = screen == s, onClick = { screen = s },
                                    icon = { Text(icon) }, label = { Text(name) }
                                )
                            }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (screen) {
                    Screen.VOICE -> VoiceScreen()
                    Screen.CHAT -> ChatScreen()
                    Screen.SETTINGS -> SettingsScreen()
                    Screen.MEMORY -> MemoryScreen()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Voice

@Composable
fun VoiceScreen() {
    val ctx = LocalContext.current
    val state by VisionCore.state.collectAsState()
    val caption by VisionCore.caption.collectAsState()
    val mic by VisionCore.micOn.collectAsState()
    val speaker by VisionCore.speakerOn.collectAsState()

    val active = state != LiveState.Disconnected
    val color = when (state) {
        LiveState.Disconnected -> Color(0xFF555B66)
        LiveState.Connecting -> Color(0xFFFFB300)
        LiveState.Listening -> BLUE
        LiveState.Speaking -> GOLD
    }
    val label = when (state) {
        LiveState.Disconnected -> "Tap to wake Vision"
        LiveState.Connecting -> "Connecting…"
        LiveState.Listening -> "Listening"
        LiveState.Speaking -> "Speaking"
    }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )

    fun toggle() {
        if (active) { ctx.stopService(Intent(ctx, VoiceService::class.java)); return }
        if (Prefs.apiKey.isBlank()) { Toast.makeText(ctx, "Settings में Gemini API key डालो", Toast.LENGTH_LONG).show(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "माइक की permission दो", Toast.LENGTH_LONG).show(); return
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            // The blue arc turns while Vision is awake.
            Canvas(Modifier.size(240.dp)) {
                val stroke = 10.dp.toPx()
                drawArc(
                    color = BLUE.copy(alpha = if (active) 1f else 0.25f),
                    startAngle = spin,
                    sweepAngle = if (active) 200f else 70f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Box(
                Modifier.size(170.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)).clickable { toggle() },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(120.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Text(if (active) "■" else "⏻", fontSize = 36.sp, color = Color.Black)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(label, color = color)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (mic) "🎤" else "🚫", fontSize = 32.sp,
                modifier = Modifier.clickable { VisionCore.micOn.value = !mic }.padding(10.dp)
            )
            Text(
                if (speaker) "🔊" else "🔇", fontSize = 32.sp,
                modifier = Modifier.clickable { VisionCore.speakerOn.value = !speaker }.padding(10.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(caption, color = GREY, maxLines = 6)
    }
}

// ---------------------------------------------------------------- Chat

class ChatVM : ViewModel() {
    val msgs = mutableStateListOf<Turn>()
    var busy by mutableStateOf(false)

    init { reload() }

    /** Reloads from the shared history, so voice turns show up here too. */
    fun reload() {
        msgs.clear()
        msgs.addAll(MemoryStore.turns(300))
    }

    fun send(app: Context, text: String) {
        if (text.isBlank() || busy) return
        MemoryStore.addTurn("text", true, text)
        reload()
        busy = true
        viewModelScope.launch {
            val r = try { ChatEngine.reply(app) } catch (e: Exception) { "Error: ${e.message}" }
            MemoryStore.addTurn("text", false, r)
            reload()
            busy = false
            MemoryKeeper.maybeRun()
        }
    }
}

fun speak(tts: TextToSpeech?, text: String) {
    tts ?: return
    tts.language = if (LanguageDetector.detectLanguage(text) == "hi") Locale("hi", "IN") else Locale.US
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
}

@Composable
fun ChatScreen(vm: ChatVM = viewModel()) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val ttsRef = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(ctx) { s -> if (s == TextToSpeech.SUCCESS) ttsRef[0]?.language = Locale.US }
        ttsRef[0] = t
        onDispose { t.shutdown() }
    }
    LaunchedEffect(Unit) { vm.reload() }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(vm.msgs) { t ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = if (t.user) Alignment.End else Alignment.Start
                ) {
                    Text(
                        (if (t.source == "voice") "🎙 " else "") + t.text,
                        color = if (t.user) Color.White else Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (t.user) BUBBLE_USER else BUBBLE_VISION)
                            .padding(12.dp)
                    )
                    if (!t.user) Text(
                        "🔊",
                        modifier = Modifier.clickable { speak(ttsRef[0], t.text) }.padding(6.dp)
                    )
                }
            }
            if (vm.busy) item { Text("…", color = GREY) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 4
            )
            Text("🎤", fontSize = 26.sp, modifier = Modifier.clickable { listen(ctx) { input = it } }.padding(8.dp))
            Text(
                "➤", fontSize = 26.sp, color = BLUE,
                modifier = Modifier.clickable { vm.send(ctx.applicationContext, input); input = "" }.padding(8.dp)
            )
        }
    }
}

fun listen(ctx: Context, onText: (String) -> Unit) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
        Toast.makeText(ctx, "Speech recognition unavailable", Toast.LENGTH_SHORT).show(); return
    }
    val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
    sr.setRecognitionListener(object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
            sr.destroy()
        }
        override fun onError(error: Int) { sr.destroy() }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    })
    sr.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
    )
}

// ---------------------------------------------------------------- Settings

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, fontSize = 12.sp, color = GREY)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.apiKey) }
    var voice by remember { mutableStateOf(Prefs.voice) }
    var control by remember { mutableStateOf(Prefs.controlEnabled) }
    var memory by remember { mutableStateOf(Prefs.memoryOn) }
    var notify by remember { mutableStateOf(Prefs.notifyAlerts) }
    var agent by remember { mutableStateOf(Prefs.agentOn) }
    var gmail by remember { mutableStateOf(Prefs.gmailEnabled) }
    var gmailToken by remember { mutableStateOf(Prefs.gmailToken) }
    val connected = VisionAccessibilityService.inst != null

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontSize = 22.sp)
        OutlinedTextField(
            key, { key = it; Prefs.apiKey = it }, Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            voice, { voice = it; Prefs.voice = it }, Modifier.fillMaxWidth(),
            label = { Text("Voice (Charon, Puck, Kore, Fenrir, Orus…)") }, singleLine = true
        )
        HorizontalDivider()
        ToggleRow(
            "Phone control",
            if (connected) "Accessibility connected" else "Accessibility not enabled yet",
            control
        ) { control = it; Prefs.controlEnabled = it }
        ToggleRow(
            "Memory",
            "Vision reads and saves memory files and the shared history",
            memory
        ) { memory = it; Prefs.memoryOn = it }
        ToggleRow(
            "Message alerts",
            "Tell me about WhatsApp, Telegram, Instagram and SMS messages (needs notification access)",
            notify
        ) { notify = it; Prefs.notifyAlerts = it }
        ToggleRow(
            "Agent mode",
            "Vision answers chat messages by itself for the people you named, also in the background (needs notification access)",
            agent
        ) { agent = it; Prefs.agentOn = it }
        ToggleRow(
            "Gmail API",
            "Search your mail (read only) with the access token below",
            gmail
        ) { gmail = it; Prefs.gmailEnabled = it }
        if (gmail) {
            OutlinedTextField(
                gmailToken, { gmailToken = it; Prefs.gmailToken = it }, Modifier.fillMaxWidth(),
                label = { Text("Gmail OAuth access token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        HorizontalDivider()
        Button({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
        Button({
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
        }) { Text("Allow launching apps (Display over other apps)") }
        Button({ ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text("Notification access")
        }
    }
}

// ---------------------------------------------------------------- Memory

@Composable
fun MemoryScreen() {
    var files by remember { mutableStateOf(MemoryStore.list()) }
    var openPath by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var newPath by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val path = openPath
        if (path == null) {
            Text("Memory", fontSize = 22.sp)
            Text(
                "Vision reads these every time it talks. You can edit or delete any file. " +
                    "Folders: facts, routines, projects, skills (things you taught it).",
                fontSize = 12.sp, color = GREY
            )
            files.forEach { f ->
                Text(
                    f,
                    modifier = Modifier.fillMaxWidth().background(CARD).clickable {
                        content = MemoryStore.read(f).orEmpty()
                        openPath = f
                    }.padding(12.dp)
                )
            }
            if (files.isEmpty()) Text("No files yet.", color = GREY)
            HorizontalDivider()
            OutlinedTextField(
                newPath, { newPath = it }, Modifier.fillMaxWidth(),
                label = { Text("New file, e.g. facts/favourite-food.md") }, singleLine = true
            )
            Button({
                val res = MemoryStore.write(newPath, "")
                status = res
                if (res.startsWith("Saved")) {
                    content = ""
                    openPath = newPath.trim().trimStart('/')
                }
                files = MemoryStore.list()
            }) { Text("Create") }
        } else {
            Text(path, color = GOLD)
            OutlinedTextField(
                content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 320.dp),
                label = { Text("Content") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({
                    status = MemoryStore.write(path, content)
                    files = MemoryStore.list()
                    openPath = null
                }) { Text("Save") }
                Button({
                    status = MemoryStore.delete(path)
                    files = MemoryStore.list()
                    openPath = null
                }) { Text("Delete") }
                Button({ openPath = null }) { Text("Back") }
            }
        }
        if (status.isNotBlank()) Text(status, color = GREY, fontSize = 12.sp)
    }
}
