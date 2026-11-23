package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** One thing to tap on screen: matches text / content-description (or a view id). */
data class Step(
    val labels: List<String>,
    val exact: Boolean = false,
    val viewIdSuffix: String? = null,
    val requiredText: String? = null,        // message must be in a text box before we tap Send
    val requiredRecipient: String? = null    // contact name must be visible (chat header) before we tap Send
)

/**
 * Jarvis' "hands". Shows two floating buttons over every app:
 *   J  = speak a command      KB = type a command
 * It only acts when YOU give a command.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        @Volatile private var steps: List<Step> = emptyList()
        @Volatile private var deadline = 0L

        @Volatile private var allowed: Set<String>? = null

        /** Queue steps to perform as screens appear (15 s window), only inside the given app packages. */
        fun run(list: List<Step>, packages: Set<String>? = null) {
            steps = list; allowed = packages; deadline = System.currentTimeMillis() + 20_000
        }
        fun cancel() { steps = emptyList(); allowed = null }
    }

    private var tts: TextToSpeech? = null
    private var engine: JarvisEngine? = null
    private var listener: VoiceListener? = null
    private var voiceBubble: TextView? = null
    private var typeBubble: TextView? = null
    private var panel: LinearLayout? = null

    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        engine = JarvisEngine(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
        listener = VoiceListener(this, onText = { engine?.handle(it) }, onStatus = {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        })
        voiceBubble = addBubble("J", 0) { listener?.start() }
        typeBubble = addBubble("KB", 64) { showTypePanel() }
    }

    private fun addBubble(label: String, yOffsetDp: Int, onClick: () -> Unit): TextView {
        val btn = TextView(this).apply {
            text = label
            textSize = if (label.length > 1) 16f else 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1565C0.toInt()) }
            setOnClickListener { onClick() }
        }
        val lp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; y = dp(yOffsetDp) }
        wm.addView(btn, lp)
        return btn
    }

    // ---------- typed command box ----------
    private fun showTypePanel() {
        if (panel != null) return
        val input = EditText(this).apply {
            hint = "Type a command, e.g. open YouTube"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        val go = Button(this).apply { text = "Go" }
        val close = Button(this).apply { text = "X" }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(go)
            addView(close)
        }
        fun submit() {
            val text = input.text.toString().trim()
            hidePanel()
            // small delay so the app underneath becomes active again before Jarvis acts
            if (text.isNotEmpty()) Handler(Looper.getMainLooper()).postDelayed({ engine?.handle(text) }, 400)
        }
        go.setOnClickListener { submit() }
        close.setOnClickListener { hidePanel() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        wm.addView(row, lp)
        panel = row
        input.requestFocus()
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null
    }

    // ---------- auto steps (used for "send" in chat apps) ----------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val list = steps
        if (list.isEmpty()) return
        if (System.currentTimeMillis() > deadline) {
            cancel()
            Toast.makeText(this, "Jarvis could not verify the chat and message. Nothing was sent automatically.", Toast.LENGTH_LONG).show()
            return
        }
        val root = rootInActiveWindow ?: return
        allowed?.let { a -> if (root.packageName?.toString() !in a) return }   // never tap inside a different app
        val step = list.first()
        // only tap Send after the right person's chat AND the right message are on screen
        step.requiredRecipient?.let { expected -> if (!hasVisibleLabel(root, expected)) return }
        step.requiredText?.let { expected -> if (!hasEditableText(root, expected)) return }
        val node = findNode(root, step) ?: return
        if (clickNodeOrParent(node)) steps = list.drop(1)
    }

    // ---------- direct actions ----------
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

    private fun hasVisibleLabel(root: AccessibilityNodeInfo, expected: String): Boolean =
        dfs(root) { n ->
            n.text?.toString()?.trim()?.equals(expected.trim(), ignoreCase = true) == true ||
                n.contentDescription?.toString()?.trim()?.equals(expected.trim(), ignoreCase = true) == true
        } != null

    /** Only the composer field can authorize Send; old messages elsewhere on screen do not count. */
    private fun hasEditableText(root: AccessibilityNodeInfo, expected: String): Boolean =
        dfs(root) { n -> n.isEditable && n.text?.toString()?.trim() == expected.trim() } != null

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
        hidePanel()
        voiceBubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        typeBubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        listener?.destroy()
        tts?.shutdown()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
