package com.agent.arena

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

enum class Phase { LOADING, WEB, CHAT }
enum class LoginState { IN, OUT, UNKNOWN }
data class SendTicket(val baseline: Int, val head: String, val marker: String)

/**
 * LMArena 웹(WebView)을 "숨은 AI 백엔드"로 쓰는 컨트롤러.
 * - 쿠키는 WebView CookieManager 가 디스크에 영속화한다.
 * - 프롬프트 입력/전송, 스트리밍 읽기, 중단, 새 세션은 모두 DOM 조작으로 처리한다.
 */
class ArenaController(private val ctx: Context) {

    companion object {
        const val URL = "https://arena.ai/text/direct?model_a=max"
        private const val ARENA_HOST = "arena.ai"
    }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _webHint = MutableStateFlow("")
    val webHint: StateFlow<String> = _webHint.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("ArenaController", "background error", e) }
    )

    @Volatile private var pageLoaded = false
    private var started = false

    val webView: WebView by lazy { buildWebView() }

    // ---------------------------------------------------------------- setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false) // window.open 을 같은 WebView 에서 처리
            // Google 로그인은 임베디드 WebView UA("; wv", "Version/x.x")를 차단하므로 제거
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
            }

            // WebView 렌더러 프로세스가 죽어도 앱이 같이 종료되지 않도록 처리
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                pageLoaded = false
                android.util.Log.e("ArenaController", "WebView renderer gone (crash=${detail?.didCrash()})")
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                CookieManager.getInstance().flush()
            }
        }
        wv.webChromeClient = WebChromeClient()
        return wv
    }

    fun start() {
        if (started) return
        started = true
        val hasCookie = !CookieManager.getInstance().getCookie("https://$ARENA_HOST").isNullOrEmpty()
        // 저장된 쿠키가 있으면 즉시 대화창, 없으면(첫 실행) 로딩 UI
        _phase.value = if (hasCookie) Phase.CHAT else Phase.LOADING
        pageLoaded = false
        webView.loadUrl(URL)
        scope.launch {
            try { bootstrap(hasCookie) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; android.util.Log.e("ArenaController", "bootstrap", e) }
        }
    }

    private suspend fun bootstrap(hasCookie: Boolean) {
        waitReady(30_000)
        if (probeLogin() == LoginState.IN) {
            _phase.value = Phase.CHAT
            return
        }
        if (hasCookie) showWeb("LMArena 로그인이 필요합니다. 로그인하면 자동으로 닫힙니다.")
        driveToGoogle(25_000)
        showWeb("Google 계정으로 로그인하세요. 완료되면 자동으로 닫힙니다.")
        awaitLoginCompletion()
    }

    /** 로그인 버튼 → Continue with Google 을 자동 클릭해 Google 로그인 창까지 이동. */
    private suspend fun driveToGoogle(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (currentUrl().contains("google.com")) return true
            if (js("__ag.clickText('continue with google')") == "true") { delay(1200); continue }
            if (js("__ag.clickText('log in')") == "true") { delay(800); continue }
            js("__ag.clickFallback()") // 모바일 레이아웃에서 숨겨진 로그인 UI 열기
            delay(800)
        }
        return false
    }

    private suspend fun awaitLoginCompletion() {
        while (true) {
            delay(1500)
            CookieManager.getInstance().flush()
            if (_phase.value == Phase.CHAT) return
            if (!currentUrl().contains(ARENA_HOST)) continue
            if (loginState() == LoginState.IN && probeLogin() == LoginState.IN) {
                _phase.value = Phase.CHAT
                return
            }
        }
    }

    // ---------------------------------------------------------------- phase controls

    private fun showWeb(hint: String) {
        _webHint.value = hint
        _phase.value = Phase.WEB
    }

    fun toggleWeb() {
        if (_phase.value == Phase.WEB) {
            _phase.value = Phase.CHAT
        } else {
            showWeb("LMArena 페이지 (수동 확인 중)")
        }
    }

    fun closeWeb() { _phase.value = Phase.CHAT }

    // ---------------------------------------------------------------- login helpers

    private suspend fun loginState(): LoginState = when (js("__ag.loginState()")) {
        "in" -> LoginState.IN
        "out" -> LoginState.OUT
        else -> LoginState.UNKNOWN
    }

    /** 모바일 레이아웃에서 Log In 버튼이 숨겨져 있을 수 있어, 한 번 열어 확인 후 되돌린다. */
    private suspend fun probeLogin(): LoginState {
        val s = loginState()
        if (s != LoginState.IN) return s
        if (js("__ag.clickFallback()") == "true") {
            delay(800)
            if (loginState() == LoginState.OUT) return LoginState.OUT
            js("__ag.clickFallback()")
            delay(300)
        }
        return LoginState.IN
    }

    private suspend fun waitReady(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageLoaded && loginState() != LoginState.UNKNOWN) return true
            delay(400)
        }
        return false
    }

    private suspend fun waitComposer(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageLoaded && js("__ag.hasComposer()") == "true") return true
            delay(400)
        }
        return false
    }

    private suspend fun currentUrl(): String = withContext(Dispatchers.Main) { webView.url ?: "" }

    // ---------------------------------------------------------------- JS bridge

    private suspend fun js(expr: String): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<String?> { cont ->
                val code = "(function(){" + JsLib.SRC + ";return String(" + expr + ");})()"
                try {
                    webView.evaluateJavascript(code) { r ->
                    val v = try {
                        if (r == null || r == "null") null else JSONArray("[$r]").getString(0)
                    } catch (e: Exception) {
                        null
                    }
                        if (cont.isActive) cont.resume(v)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    // ---------------------------------------------------------------- chat API

    /** 새 세션: 같은 URL 을 다시 열어 새 대화를 시작한다. */
    suspend fun newChat() {
        withContext(Dispatchers.Main) {
            pageLoaded = false
            webView.loadUrl(URL)
        }
        delay(600)
        waitComposer(40_000)
    }

    suspend fun send(text: String): SendTicket? {
        if (!waitComposer(40_000)) return null
        val base = js("__ag.segCount()")?.toIntOrNull() ?: 0
        val head = text.replace(Regex("\\s+"), " ").trim().take(30)
        // 메시지마다 고유 마커를 끝에 붙여, DOM 에서 "내 메시지의 끝"을 정확히 찾는다
        val marker = "ENDMARK" + java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val payload = text.trimEnd() + "\n\n" + marker
        if (js("__ag.setPrompt(${JSONObject.quote(payload)})") != "true") return null
        delay(250)
        repeat(30) {
            if (js("__ag.clickSend()") == "true") return SendTicket(base, head, marker)
            delay(250)
        }
        return null
    }

    /** 답변이 끝날 때까지 폴링하며 스트리밍. 끝나면 최종 마크다운 텍스트를 반환. */
    suspend fun awaitResponse(t: SendTicket, onUpdate: (String) -> Unit): String {
        val start = System.currentTimeMillis()
        var sawGenerating = false
        var idle = 0
        var stable = 0
        var last = ""
        val headArg = JSONObject.quote(t.head)
        val markerArg = JSONObject.quote(t.marker)
        while (System.currentTimeMillis() - start < 240_000) {
            delay(250)
            val raw = js("__ag.answer($markerArg)") ?: continue
            val o = try { JSONObject(raw) } catch (e: Exception) { continue }
            val gen = o.optBoolean("g")
            // 마커 뒤 조각을 우선 사용. 없으면 6초 후부터 "전송 전에 없던 조각" 전체를 사용
            val fromMarker = o.optString("a")
            val text = when {
                fromMarker.isNotBlank() -> fromMarker
                System.currentTimeMillis() - start > 6_000 -> o.optString("c")
                else -> ""
            }
            if (gen) sawGenerating = true
            if (text != last) {
                last = text
                stable = 0
                onUpdate(text)
            } else {
                stable++
            }
            if (gen) {
                idle = 0
            } else {
                idle++
                // 답변 중단 UI 가 사라진 상태가 일정 시간 지속되면 완료
                if (sawGenerating && idle >= 3 && text.isNotBlank()) break
                if (!sawGenerating && text.isNotBlank() && stable >= 8) break
            }
            // 중단 버튼 판별이 틀려 "생성 중"으로 고정되는 경우 대비: 텍스트가 10초간 변하지 않으면 완료로 간주
            if (text.isNotBlank() && stable >= 40) break
        }
        CookieManager.getInstance().flush()
        return last
    }

    /** 진단용: 마커/조각 수/생성 상태 */
    suspend fun debug(t: SendTicket): String = js("__ag.debug(${JSONObject.quote(t.marker)})") ?: "(js 응답 없음)"

    suspend fun stopGeneration() {
        js("__ag.clickStop()")
    }
}
