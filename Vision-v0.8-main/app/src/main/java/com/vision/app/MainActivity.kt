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
