package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** One thing to tap on screen: matches text / content-description (or a view id). */
data class Step(val labels: List<String>, val exact: Boolean = false, val viewIdSuffix: String? = null)

/**
 * Jarvis' "hands". Gives Jarvis a floating "J" button over every app, and lets it
 * tap, type and scroll in ANY app. It only acts when YOU give a voice command.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        @Volatile private var steps: List<Step> = emptyList()
        @Volatile private var deadline = 0L

        /** Queue steps to perform automatically as screens appear (15 s window). */
        fun run(list: List<Step>) { steps = list; deadline = System.currentTimeMillis() + 15_000 }
        fun cancel() { steps = emptyList() }
    }

    private var tts: TextToSpeech? = null
    private var bubble: TextView? = null
    private var listener: VoiceListener? = null

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        val engine = JarvisEngine(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
        listener = VoiceListener(this, onText = { engine.handle(it) }, onStatus = {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        })
        showBubble()
    }

    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val btn = TextView(this).apply {
            text = "J"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1565C0.toInt()) }
            setOnClickListener { listener?.start() }
        }
        val size = (56 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        wm.addView(btn, lp)
        bubble = btn
    }

    // ---------- auto steps (used for "send" in chat apps) ----------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val list = steps
        if (list.isEmpty()) return
        if (System.currentTimeMillis() > deadline) { steps = emptyList(); return }
        val root = rootInActiveWindow ?: return
        val node = findNode(root, list.first()) ?: return
        if (clickNodeOrParent(node)) steps = list.drop(1)
    }

    // ---------- direct voice actions ----------
    fun clickLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, Step(listOf(label))) ?: return false
        return clickNodeOrParent(node)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findScrollable(root) ?: return false
        return node.performAction(
            if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    // ---------- helpers ----------
    private fun findNode(root: AccessibilityNodeInfo, step: Step): AccessibilityNodeInfo? {
        step.viewIdSuffix?.let { id ->
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id").firstOrNull()?.let { return it }
        }
        return dfs(root) { n ->
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            step.labels.any { l ->
                if (step.exact) t.equals(l, true) || d.equals(l, true)
                else t.contains(l, true) || d.contains(l, true)
            }
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo) = dfs(root) { it.isEditable }
    private fun findScrollable(root: AccessibilityNodeInfo) = dfs(root) { it.isScrollable }

    private fun dfs(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) dfs(node.getChild(i), match)?.let { return it }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        bubble?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        listener?.destroy()
        tts?.shutdown()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
