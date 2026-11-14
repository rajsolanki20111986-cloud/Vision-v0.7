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
