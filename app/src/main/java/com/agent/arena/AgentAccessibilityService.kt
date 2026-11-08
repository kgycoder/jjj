package com.agent.arena

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** 화면 읽기(UI 트리 덤프) + 탭/스와이프/입력/전역 키/앱 실행 등 기기 조작 실행기. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Volatile var instance: AgentAccessibilityService? = null
            private set

        private const val MAX_NODES = 160
    }

    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ------------------------------------------------------------ screen dump

    fun dumpScreen(): String {
        val root = rootInActiveWindow
            ?: return "(화면 정보를 읽을 수 없음: 잠금/보안 화면이거나 전환 중일 수 있음. wait 후 다시 확인)"
        val pkg = root.packageName?.toString().orEmpty()
        nodes.clear()
        if (pkg == packageName) {
            return "foreground_package: $pkg\n(현재 앞에 있는 화면은 에이전트 앱 자신입니다. 기기 조작이 필요하면 launch_app 또는 key home 으로 이동하세요.)"
        }
        val sb = StringBuilder()
        sb.appendLine("foreground_package: $pkg")
        var id = 0

        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.replace("\n", " ")?.take(100)
            val desc = n.contentDescription?.toString()?.replace("\n", " ")?.take(100)
            val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() ||
                n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (interesting && id < MAX_NODES) {
                id++
                nodes[id] = n
                val r = Rect()
                n.getBoundsInScreen(r)
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("click")
                if (n.isLongClickable) flags.add("long")
                if (n.isEditable) flags.add("edit")
                if (n.isScrollable) flags.add("scroll")
                if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
                if (n.isFocused) flags.add("focused")
                if (!n.isEnabled) flags.add("disabled")
                sb.append("[$id] ${n.className?.toString()?.substringAfterLast('.')}")
                if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
                if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
                sb.append(" center=(${r.centerX()},${r.centerY()})")
                if (flags.isNotEmpty()) sb.append(" ${flags.joinToString(",")}")
                sb.appendLine()
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        if (id == 0) sb.appendLine("(상호작용 가능한 요소가 보이지 않음)")
        if (id >= MAX_NODES) sb.appendLine("(요소가 많아 일부만 표시됨. scroll 로 더 확인)")
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------ command execution

    /** 명령 1개를 실행하고 사람이 읽을 수 있는 결과 문자열("ok: ..." / "error: ...")을 반환. */
    suspend fun execute(c: JSONObject): String {
        val a = c.optString("action").lowercase().trim()
        return try {
            when (a) {
                "tap", "click" -> tap(c, longPress = false)
                "long_press", "longpress" -> tap(c, longPress = true)
                "swipe" -> swipe(c)
                "scroll" -> scroll(c.optString("direction", "down"))
                "type", "input", "set_text" -> typeText(c)
                "key" -> key(c.optString("key"))
                "back", "home", "recents", "notifications", "quick_settings", "lock_screen" -> key(a)
                "launch_app", "open_app" -> launchApp(c)
                "open_url" -> openUrl(c.optString("url"))
                "wait" -> {
                    val ms = c.optLong("ms", 1000).coerceIn(100, 10_000)
                    delay(ms); "ok: waited ${ms}ms"
                }
                else -> "error: unknown action '$a'"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun tap(c: JSONObject, longPress: Boolean): String {
        val id = c.optInt("id", -1)
        if (id > 0) {
            val node = nodes[id] ?: return "error: id $id 는 최신 DEVICE_STATE 에 없음(만료)"
            if (!longPress) {
                var n: AccessibilityNodeInfo? = node
                while (n != null && !n.isClickable) n = n.parent
                if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    delay(500); return "ok: clicked id $id"
                }
            }
            val r = Rect(); node.getBoundsInScreen(r)
            val ok = gesture(point(r.centerX().toFloat(), r.centerY().toFloat()), if (longPress) 700 else 50)
            delay(500)
            return if (ok) "ok: ${if (longPress) "long-pressed" else "tapped"} id $id at (${r.centerX()},${r.centerY()})" else "error: gesture failed"
        }
        if (!c.has("x") || !c.has("y")) return "error: tap requires id or x,y"
        val x = c.getDouble("x").toFloat(); val y = c.getDouble("y").toFloat()
        val ok = gesture(point(x, y), if (longPress) 700 else 50)
        delay(500)
        return if (ok) "ok: ${if (longPress) "long-pressed" else "tapped"} ($x,$y)" else "error: gesture failed"
    }

    private suspend fun swipe(c: JSONObject): String {
        val p = Path().apply {
            moveTo(c.getDouble("x1").toFloat(), c.getDouble("y1").toFloat())
            lineTo(c.getDouble("x2").toFloat(), c.getDouble("y2").toFloat())
        }
        val ok = gesture(p, c.optLong("duration_ms", 350).coerceIn(50, 3000))
        delay(500)
        return if (ok) "ok: swiped" else "error: gesture failed"
    }

    /** direction = 콘텐츠를 이동하고 싶은 방향 (down: 페이지 아래쪽으로 = 손가락은 위로). */
    private suspend fun scroll(direction: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val p = Path()
        when (direction.lowercase()) {
            "down" -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.28f) }
            "up" -> { p.moveTo(w / 2, h * 0.28f); p.lineTo(w / 2, h * 0.72f) }
            "right" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "left" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return "error: direction must be up/down/left/right"
        }
        val ok = gesture(p, 350)
        delay(600)
        return if (ok) "ok: scrolled $direction" else "error: gesture failed"
    }

    private suspend fun typeText(c: JSONObject): String {
        val text = c.optString("text")
        val id = c.optInt("id", -1)
        val node: AccessibilityNodeInfo? = when {
            id > 0 -> nodes[id]
            else -> rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: nodes.values.firstOrNull { it.isEditable }
        }
        if (node == null) return "error: 입력 가능한 필드를 찾지 못함 (먼저 필드를 tap 하세요)"
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val base = if (c.optBoolean("append", false) && !node.isShowingHintText) node.text?.toString().orEmpty() else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "error: ACTION_SET_TEXT 실패"
        if (c.optBoolean("submit", false) && Build.VERSION.SDK_INT >= 30) {
            delay(200)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        delay(400)
        return "ok: typed ${text.length} chars"
    }

    private suspend fun key(k: String): String {
        val action = when (k.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "error: lock_screen requires Android 9+"
            else -> return "error: unknown key '$k'"
        }
        val ok = performGlobalAction(action)
        delay(700)
        return if (ok) "ok: key $k" else "error: key $k failed"
    }

    private suspend fun launchApp(c: JSONObject): String {
        val pm = packageManager
        var pkg = c.optString("package")
        if (pkg.isBlank()) {
            val name = c.optString("name").trim()
            if (name.isBlank()) return "error: launch_app requires package or name"
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
            val hit = list.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
                ?: list.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
                ?: return "error: '$name' 앱을 찾지 못함"
            pkg = hit.activityInfo.packageName
        }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "error: $pkg 실행 불가(미설치/실행 인텐트 없음)"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        delay(1600)
        return "ok: launched $pkg"
    }

    private suspend fun openUrl(url: String): String {
        if (url.isBlank()) return "error: url is empty"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        delay(1600)
        return "ok: opened $url"
    }

    // ------------------------------------------------------------ gestures

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
                .build()
            val dispatched = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
}
