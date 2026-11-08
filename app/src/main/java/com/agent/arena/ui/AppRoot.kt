package com.agent.arena.ui

import android.view.ViewGroup
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import com.agent.arena.CrashReport
import com.agent.arena.AgentViewModel
import com.agent.arena.ArenaController
import com.agent.arena.Phase

@Composable
fun AppRoot(vm: AgentViewModel, ctl: ArenaController, onOpenAccessibility: () -> Unit) {
    val phase by ctl.phase.collectAsState()
    val hint by ctl.webHint.collectAsState()
    val p = Palette(isSystemInDarkTheme())
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var report by remember { mutableStateOf(CrashReport.read(appCtx)) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        // WebView 는 항상 트리에 붙어 있다(숨김 상태에서도 JS/쿠키 동작). CHAT 이면 대화 UI 가 위를 덮는다.
        Column(
            Modifier.fillMaxSize().then(
                if (phase == Phase.WEB) Modifier.systemBarsPadding().imePadding() else Modifier
            )
        ) {
            if (phase == Phase.WEB) WebBanner(hint, p) { ctl.closeWeb() }
            AndroidView(
                factory = {
                    ctl.webView.also { v -> (v.parent as? ViewGroup)?.removeView(v) }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        val r = report
        if (r != null) {
            CrashScreen(r, p) { CrashReport.clear(appCtx); report = null }
        } else {
            when (phase) {
                Phase.CHAT -> ChatScreen(vm, onOpenAccessibility)
                Phase.LOADING -> LoadingScreen(p)
                Phase.WEB -> Unit
            }
        }
    }
}

@Composable
private fun WebBanner(hint: String, p: Palette, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(hint, modifier = Modifier.weight(1f), style = TextStyle(color = p.text, fontSize = 14.sp))
        Spacer(Modifier.size(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(p.userBubble)
                .clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 8.dp)
        ) { BasicText("닫기", style = TextStyle(color = p.text, fontSize = 14.sp)) }
    }
}

@Composable
private fun LoadingScreen(p: Palette) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Column(
        Modifier.fillMaxSize().background(p.bg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val c = p.text
        Canvas(Modifier.size(44.dp)) {
            val sw = 3.dp.toPx()
            drawArc(c.copy(alpha = 0.12f), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
            drawArc(c, angle, 90f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(22.dp))
        BasicText("LMArena에 연결하는 중…", style = TextStyle(color = p.text, fontSize = 16.sp))
        Spacer(Modifier.height(6.dp))
        BasicText("Google 로그인 화면으로 이동합니다", style = TextStyle(color = p.sub, fontSize = 13.sp))
    }
}

@Composable
private fun CrashScreen(report: String, p: Palette, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().background(p.bg).systemBarsPadding().padding(16.dp)) {
        BasicText("이전 실행에서 오류로 종료되었습니다", style = TextStyle(color = p.error, fontSize = 17.sp))
        Spacer(Modifier.height(6.dp))
        BasicText("아래 내용을 길게 눌러 복사해 전달해 주세요.", style = TextStyle(color = p.sub, fontSize = 13.sp))
        Spacer(Modifier.height(12.dp))
        SelectionContainer(Modifier.weight(1f)) {
            BasicText(report, modifier = Modifier.verticalScroll(rememberScrollState()), style = TextStyle(color = p.text, fontSize = 11.sp, lineHeight = 15.sp))
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(p.text).clickable(onClick = onContinue).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { BasicText("계속하기", style = TextStyle(color = p.bg, fontSize = 16.sp)) }
    }
}
