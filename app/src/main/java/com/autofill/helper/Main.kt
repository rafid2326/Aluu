package com.autofill.helper

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Config {
    val START = listOf("start building now")
    val FIRST = listOf("first name", "firstname", "given name")
    val LAST = listOf("last name", "lastname", "surname", "family name")
    val PHONE = listOf("phone number", "get number")
    val NEW = listOf("new number")
    val AREA = listOf("area code")
    val CREATE = listOf("create number")
    val EMAIL = listOf("email")
    val OTP = listOf("otp", "verification code", "one-time", "security code")
    const val TICK_MS = 1500L
    const val START_RETRY_MS = 8000L
}

object Names {
    val first = listOf(
        "James", "Michael", "Robert", "David", "William", "Daniel", "Joseph", "Thomas", "Ryan", "Kevin",
        "Brian", "Jason", "Eric", "Adam", "Nathan", "Tyler", "Justin", "Aaron", "Jacob", "Ethan",
        "Emma", "Olivia", "Sophia", "Isabella", "Ava", "Mia", "Emily", "Abigail", "Madison", "Chloe",
        "Hannah", "Grace", "Lily", "Natalie", "Rachel", "Sarah", "Megan", "Laura", "Jessica", "Amanda"
    )
    val last = listOf(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Wilson", "Anderson", "Taylor",
        "Moore", "Jackson", "Martin", "Lee", "Thompson", "White", "Harris", "Clark", "Lewis", "Walker",
        "Hall", "Allen", "Young", "King", "Wright", "Scott", "Green", "Baker", "Adams", "Nelson",
        "Hill", "Campbell", "Mitchell", "Roberts", "Carter", "Phillips", "Evans", "Turner", "Parker", "Collins"
    )
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            textSize = 16f
            text = "1. Tap the button below, open Accessibility and turn on Auto Fill.\n\n" +
                "2. Open the target app. A small panel appears on the right.\n\n" +
                "3. Enter Email, OTP and Area code, then press Start."
        })
        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(root)
    }
}

class Engine(
    private val svc: AccessibilityService,
    private val area: () -> String,
    private val email: () -> String,
    private val otp: () -> String,
    private val otpUsed: () -> Unit
) {
    private val done = HashSet<String>()
    private var lastStart = 0L

    fun reset() { done.clear() }

    private fun targetRoot(): AccessibilityNodeInfo? {
        for (w in svc.windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (r.packageName?.toString() != svc.packageName) return r
        }
        val r = svc.rootInActiveWindow
        return if (r != null && r.packageName?.toString() != svc.packageName) r else null
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun lbl(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").lowercase().trim()

    private fun labelOf(nodes: List<AccessibilityNodeInfo>, i: Int): String {
        val n = nodes[i]
        val own = ((n.hintText?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase().trim()
        if (own.isNotEmpty()) return own
        var j = i - 1
        while (j >= 0) {
            if (nodes[j].isEditable) break
            val t = lbl(nodes[j])
            if (t.isNotEmpty()) return t
            j--
        }
        return ""
    }

    private fun matches(label: String, keys: List<String>) = keys.any { label.contains(it) }

    private fun isEmptyField(n: AccessibilityNodeInfo): Boolean {
        val t = n.text
        return t == null || t.isEmpty() || n.isShowingHintText
    }

    private fun fill(n: AccessibilityNodeInfo, s: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findButton(nodes: List<AccessibilityNodeInfo>, labels: List<String>): AccessibilityNodeInfo? {
        val c = nodes.filter { it.isVisibleToUser && !it.isEditable && lbl(it).isNotEmpty() }
        return c.firstOrNull { x -> labels.any { lbl(x) == it } }
            ?: c.firstOrNull { x -> labels.any { lbl(x).contains(it) } }
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun step() {
        val root = targetRoot() ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)

        var did = false
        for ((i, n) in nodes.withIndex()) {
            if (!n.isEditable || !n.isVisibleToUser || !isEmptyField(n)) continue
            val label = labelOf(nodes, i)
            when {
                matches(label, Config.AREA) -> {
                    val a = area()
                    if (a.isNotEmpty()) did = fill(n, a) || did
                }
                matches(label, Config.FIRST) -> did = fill(n, Names.first.random()) || did
                matches(label, Config.LAST) -> did = fill(n, Names.last.random()) || did
                matches(label, Config.EMAIL) -> {
                    val e = email()
                    if (e.isNotEmpty()) did = fill(n, e) || did
                }
                matches(label, Config.OTP) -> {
                    val o = otp()
                    if (o.isNotEmpty() && fill(n, o)) { did = true; otpUsed() }
                }
            }
        }
        if (did) return

        val now = System.currentTimeMillis()
        val startNode = findButton(nodes, Config.START)
        if (startNode != null && now - lastStart > Config.START_RETRY_MS) {
            if (click(startNode)) { lastStart = now; done.clear(); return }
        }
        val steps = listOf("phone" to Config.PHONE, "new" to Config.NEW, "create" to Config.CREATE)
        for ((key, labels) in steps) {
            if (key in done) continue
            val node = findButton(nodes, labels) ?: continue
            if (click(node)) { done.add(key); return }
        }
    }
}
