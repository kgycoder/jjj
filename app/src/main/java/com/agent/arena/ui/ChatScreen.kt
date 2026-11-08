package com.agent.arena.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.arena.AgentAccessibilityService
import com.agent.arena.AgentApp
import com.agent.arena.AgentViewModel
import com.agent.arena.ChatMessage
import com.agent.arena.Role
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

class Palette(dark: Boolean) {
    val bg = if (dark) Color(0xFF0B0B0D) else Color(0xFFFFFFFF)
    val bg2 = if (dark) Color(0xFF16161A) else Color(0xFFF1F2F6)
    val text = if (dark) Color(0xFFF2F2F5) else Color(0xFF0D0D0D)
    val sub = if (dark) Color(0xFF9A9AA3) else Color(0xFF6E6E78)
    val userBubble = if (dark) Color(0xFF2A2A30) else Color(0xFFEFEFF3)
    val glass = if (dark) Color(0xFF26262C).copy(alpha = 0.50f) else Color.White.copy(alpha = 0.42f)
    val error = Color(0xFFE5484D)
    val ok = Color(0xFF30A46C)
}

@Composable
fun ChatScreen(vm: AgentViewModel, onOpenAccessibility: () -> Unit) {
    val ctl = AgentApp.instance.controller
    val p = Palette(isSystemInDarkTheme())
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val a11y by AgentAccessibilityService.connected.collectAsState()

    val last = vm.messages.lastOrNull()
    LaunchedEffect(vm.messages.size, last?.text, last?.steps?.size, last?.planning) {
        if (vm.messages.isNotEmpty()) {
            listState.scrollToItem(vm.messages.lastIndex)
            listState.scrollBy(100000f)
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(p.bg)) {
        // 글래스가 굴절/블러할 대상(backdrop) 레이어
        Box(
            Modifier.fillMaxSize()
                .layerBackdrop(backdrop)
                .background(Brush.verticalGradient(listOf(p.bg, p.bg2.copy(alpha = 0.55f), p.bg)))
        ) {
            if (vm.messages.isEmpty()) {
                EmptyState(p)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 76.dp, bottom = bottom + 108.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(vm.messages, key = { it.id }) { MessageItem(it, p) }
                }
            }
        }

        // 주의: 글래스 컴포넌트는 layerBackdrop 레이어 "바깥"에 있어야 한다(자기 자신을 배경으로 그리면 크래시).
        if (vm.messages.isEmpty() && !a11y) {
            GlassBox(
                backdrop, p.glass,
                Modifier.align(Alignment.Center).padding(top = 150.dp).height(46.dp),
                CapsuleShape, onOpenAccessibility,
            ) {
                BasicText("접근성 서비스 켜기", style = TextStyle(color = p.text, fontSize = 15.sp), modifier = Modifier.padding(horizontal = 22.dp))
            }
        }

        TopBar(
            backdrop = backdrop, p = p, a11yOn = a11y,
            onSettings = onOpenAccessibility,
            onTitle = { ctl.toggleWeb() },
            onNew = { vm.newChat() },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        InputBar(
            backdrop = backdrop, p = p, running = vm.running,
            onSend = { vm.send(it) }, onStop = { vm.stop() },
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        )
    }
}

@Composable
private fun TopBar(
    backdrop: Backdrop, p: Palette, a11yOn: Boolean,
    onSettings: () -> Unit, onTitle: () -> Unit, onNew: () -> Unit, modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassBox(backdrop, p.glass, Modifier.size(44.dp), CircleShape, onSettings) {
            SlidersIcon(p.text, Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        GlassBox(backdrop, p.glass, Modifier.height(44.dp), CapsuleShape, onTitle) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (a11yOn) p.ok else p.error))
                Spacer(Modifier.width(8.dp))
                BasicText("Agent", style = TextStyle(color = p.text, fontSize = 16.sp))
            }
        }
        Spacer(Modifier.weight(1f))
        GlassBox(backdrop, p.glass, Modifier.size(44.dp), CircleShape, onNew) {
            PlusIcon(p.text, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun InputBar(
    backdrop: Backdrop, p: Palette, running: Boolean,
    onSend: (String) -> Unit, onStop: () -> Unit, modifier: Modifier,
) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank()
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .glass(backdrop, RoundedCornerShape(28.dp), p.glass)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) BasicText("무엇이든 부탁하세요", style = TextStyle(color = p.sub, fontSize = 16.sp))
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = p.text, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(p.text), maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(p.text)
                .alpha(if (running || canSend) 1f else 0.28f)
                .clickable(enabled = running || canSend) {
                    if (running) onStop() else { onSend(text); text = "" }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (running) StopIcon(p.bg, Modifier.size(18.dp)) else ArrowUpIcon(p.bg, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyState(p: Palette) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("무엇을 도와드릴까요?", style = TextStyle(color = p.text, fontSize = 26.sp))
        Spacer(Modifier.height(10.dp))
        BasicText("기기를 직접 조작하는 AI 에이전트", style = TextStyle(color = p.sub, fontSize = 15.sp))
    }
}

@Composable
private fun MessageItem(m: ChatMessage, p: Palette) {
    if (m.role == Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp)).background(p.userBubble).padding(horizontal = 16.dp, vertical = 10.dp)) {
                BasicText(m.text, style = TextStyle(color = p.text, fontSize = 16.sp, lineHeight = 22.sp))
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            m.steps.forEach { StepLine(it, p, pulsing = false) }
            if (m.planning) StepLine("작업 계획 중…", p, pulsing = true)
            if (m.text.isNotBlank()) {
                BasicText(m.text, style = TextStyle(color = if (m.isError) p.error else p.text, fontSize = 16.sp, lineHeight = 24.sp))
            } else if (m.streaming && !m.planning) {
                PulseDot(p)
            }
        }
    }
}

@Composable
private fun StepLine(text: String, p: Palette, pulsing: Boolean) {
    val a = if (pulsing) pulseAlpha() else 1f
    Row(Modifier.alpha(a), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(p.sub))
        Spacer(Modifier.width(10.dp))
        BasicText(text, style = TextStyle(color = p.sub, fontSize = 14.sp))
    }
}

@Composable
private fun pulseAlpha(): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    return v
}

@Composable
private fun PulseDot(p: Palette) {
    val t = rememberInfiniteTransition(label = "dot")
    val s by t.animateFloat(0.7f, 1.1f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(14.dp).scale(s).clip(CircleShape).background(p.text))
}
