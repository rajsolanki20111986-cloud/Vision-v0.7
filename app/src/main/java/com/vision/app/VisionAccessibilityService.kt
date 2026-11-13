package com.vision.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream

/** Vision's hands and eyes: reads the UI tree, takes screenshots, taps, types and scrolls. */
class VisionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var inst: VisionAccessibilityService? = null
        private const val MAX_NODES = 300
        // Payment and banking apps are never operated. Extend as needed.
        private val BLOCK = listOf("paisa", "phonepe", "paytm", "paypal", "upi", "bhim", "bank", "wallet", "razorpay")
    }

    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { inst = null; super.onDestroy() }

    /** Returns a refusal message, or null when acting is allowed. */
    fun blockedReason(): String? {
        if (!Prefs.controlEnabled) return "Phone control is switched off by the user in Vision settings."
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return null
        if (BLOCK.any { pkg.contains(it, ignoreCase = true) })
            return "Blocked: this looks like a payment or banking app. Do not operate it."
        return null
    }

    /**
     * Lists what is on screen: text, and also buttons or inputs that have no text
     * (shown by their resource id or class), each with its center point for tap_xy.
     */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "No screen available"
        val dm = resources.displayMetrics
        val sb = StringBuilder()
        sb.append("app=").append(root.packageName)
            .append(" screen=").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n')
        val count = IntArray(1)
        collect(root, sb, count, 0, dm.heightPixels)
        if (count[0] >= MAX_NODES) sb.append("(list cut off; use capture_screen for the rest)\n")
        return sb.toString()
    }

    private fun collect(nd: AccessibilityNodeInfo?, sb: StringBuilder, count: IntArray, depth: Int, screenH: Int) {
        if (nd == null || count[0] >= MAX_NODES || depth > 40) return
        if (nd.isVisibleToUser) {
            val r = Rect()
            nd.getBoundsInScreen(r)
            val onScreen = r.width() > 0 && r.height() > 0 && r.bottom > 0 && r.top < screenH
            val text = (nd.text ?: nd.contentDescription)?.toString()?.trim()?.take(80)
            val interactive = nd.isClickable || nd.isLongClickable || nd.isEditable || nd.isCheckable
            if (onScreen && (!text.isNullOrBlank() || interactive)) {
                val id = nd.viewIdResourceName?.substringAfter('/')
                val label = when {
                    !text.isNullOrBlank() -> text
                    !id.isNullOrBlank() -> "id:$id"
                    else -> "unlabeled " + (nd.className?.toString()?.substringAfterLast('.') ?: "")
                }
                val tag = when {
                    nd.isEditable -> "[input] "
                    interactive -> "[btn] "
                    nd.isScrollable -> "[scroll] "
                    else -> ""
                }
                sb.append(tag).append(label.trim()).append(" @")
                    .append(r.centerX()).append(',').append(r.centerY()).append('\n')
                count[0]++
            }
        }
        for (i in 0 until nd.childCount) collect(nd.getChild(i), sb, count, depth + 1, screenH)
    }

    fun clickText(t: String): String {
        val root = rootInActiveWindow ?: return "No screen available"
        val node = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser } ?: return "Not found: $t"
        var c: AccessibilityNodeInfo? = node
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped $t"
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (tap(r.centerX(), r.centerY())) "Tapped $t by position" else "Could not tap $t"
    }

    fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null
        )
    }

    /** Adds text after what is already in the field. If the app refuses, it pastes through the clipboard. */
    fun typeText(t: String): String {
        val root = rootInActiveWindow ?: return "No screen available"
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return "No input field found"
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val old = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + t)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Typed"

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vision", t))
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "Pasted" else "Typing failed"
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    fun pressEnter(): String {
        if (Build.VERSION.SDK_INT < 30) return "Enter key is not supported on this Android; tap the search or send button instead."
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "No focused input"
        return if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "Enter pressed" else "Enter failed"
    }

    /** direction = which part of the content you want to see: "down" shows content further down. */
    fun scroll(dir: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        val ok = when (dir) {
            "down" -> swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)
            "up" -> swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)
            "right" -> swipe(w * 3 / 4, h / 2, w / 4, h / 2)
            "left" -> swipe(w / 4, h / 2, w * 3 / 4, h / 2)
            else -> false
        }
        return if (ok) "Scrolled $dir" else "Scroll failed"
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null
        )
    }

    fun press(key: String): String {
        val a = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Unknown key: $key"
        }
        return if (performGlobalAction(a)) "Done" else "Failed"
    }

    /** Screenshot as base64 JPEG. Needs Android 11+ and canTakeScreenshot in the service config. */
    suspend fun captureJpegBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    val out = ByteArrayOutputStream()
                    val ok = bmp?.compress(Bitmap.CompressFormat.JPEG, 70, out) == true
                    cont.resume(if (ok) Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) else null)
                }

                override fun onFailure(errorCode: Int) {
                    cont.resume(null)
                }
            })
        }
    }
}
