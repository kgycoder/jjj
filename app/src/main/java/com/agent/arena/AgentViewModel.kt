package com.agent.arena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Role { USER, AGENT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String = "",
    val steps: List<String> = emptyList(),
    val streaming: Boolean = false,
    val planning: Boolean = false,
    val isError: Boolean = false,
)

class AgentViewModel : ViewModel() {

    companion object { private const val MAX_STEPS = 40 }

    private val app get() = AgentApp.instance
    private val ctl get() = AgentApp.instance.controller

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var sessionStarted = false
    private var nextId = 1L

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || running) return
        messages.add(ChatMessage(nextId++, Role.USER, t))
        val idx = messages.size
        messages.add(ChatMessage(nextId++, Role.AGENT, streaming = true))
        running = true
        job = viewModelScope.launch {
            try {
                runAgent(t, idx)
            } finally {
                running = false
                AgentAccessibilityService.instance?.cursorHide(1300)
            }
        }
    }

    fun stop() {
        if (!running) return
        job?.cancel()
        viewModelScope.launch { ctl.stopGeneration() }
    }

    /** 새 채팅 = LMArena URL 을 다시 열어 새 세션 시작. */
    fun newChat() {
        job?.cancel()
        running = false
        messages.clear()
        sessionStarted = false
        viewModelScope.launch {
            ctl.stopGeneration()
            ctl.newChat()
        }
    }

    // ---------------------------------------------------------------- agent loop

    private fun upd(i: Int, f: (ChatMessage) -> ChatMessage) {
        if (i in 0 until messages.size) messages[i] = f(messages[i])
    }

    private fun finish(i: Int, text: String, error: Boolean) {
        upd(i) { it.copy(text = text, streaming = false, planning = false, isError = error) }
    }

    private suspend fun runAgent(userText: String, idx: Int) {
        var outgoing = if (!sessionStarted) AgentPrompt.first(userText, app) else AgentPrompt.followUp(userText)
        var step = 0
        var formatRetries = 0
        try {
            while (true) {
                step++
                if (step > MAX_STEPS) {
                    finish(idx, "최대 단계($MAX_STEPS)에 도달해 작업을 중단했습니다.", true); return
                }
                val ticket = ctl.send(outgoing)
                if (ticket == null) {
                    finish(idx, "LMArena로 메시지를 보내지 못했습니다. 상단 'Agent' 버튼으로 LMArena 페이지 상태(로그인/캡차)를 확인하세요.", true)
                    return
                }
                sessionStarted = true

                val raw = ctl.awaitResponse(ticket) { partial ->
                    upd(idx) {
                        it.copy(
                            text = WorkParser.visibleStreaming(partial),
                            planning = WorkParser.isPlanning(partial),
                        )
                    }
                }
                if (raw.isBlank()) {
                    finish(idx, "LMArena 응답을 받지 못했습니다. (시간 초과 또는 사이트 오류)\n진단: ${ctl.debug(ticket)}", true); return
                }

                val parsed = WorkParser.parse(raw)
                val block = parsed.block
                if (block == null) {
                    if (parsed.looksBroken && formatRetries < 2) {
                        formatRetries++
                        outgoing = AgentPrompt.formatError()
                        continue
                    }
                    // work 블록이 없으면 일반 대화 답변으로 간주
                    finish(idx, parsed.visible.ifBlank { raw }, false)
                    return
                }
                formatRetries = 0
                upd(idx) {
                    it.copy(
                        steps = it.steps + block.summary.ifBlank { block.status },
                        text = "",
                        planning = false,
                    )
                }

                val svc = AgentAccessibilityService.instance
                if (block.commands.isNotEmpty()) svc?.cursorShow(block.summary.ifBlank { "작업 중" })
                val results = if (block.commands.isNotEmpty()) executeAll(block.commands) else "[]"

                when (block.status) {
                    "done" -> {
                        svc?.cursorLabel("작업 완료", CursorState.DONE); finish(idx, parsed.visible.ifBlank { block.summary }, false); return }
                    "failed" -> { svc?.cursorLabel("작업 실패", CursorState.FAILED); finish(idx, parsed.visible.ifBlank { block.summary }, true); return }
                    "need_user" -> { svc?.cursorLabel("확인이 필요해요", CursorState.ASK); finish(idx, parsed.visible.ifBlank { block.summary }, false); return }
                }
                svc?.cursorLabel("다음 작업 생각 중…", CursorState.THINKING)
                delay(500)
                outgoing = AgentPrompt.result(step, results, app)
            }
        } catch (e: CancellationException) {
            AgentAccessibilityService.instance?.cursorLabel("중단됨", CursorState.STOPPED)
            upd(idx) { it.copy(streaming = false, planning = false, text = it.text.ifBlank { "(중단됨)" }) }
            throw e
        } catch (e: Exception) {
            finish(idx, "오류가 발생했습니다: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    private suspend fun executeAll(cmds: List<JSONObject>): String {
        val svc = AgentAccessibilityService.instance
        val arr = JSONArray()
        for (c in cmds.take(6)) {
            val r = svc?.execute(c) ?: "error: accessibility service is not enabled"
            arr.put(JSONObject().put("action", c.optString("action")).put("result", r))
            if (r.startsWith("error")) break
        }
        return arr.toString()
    }
}
