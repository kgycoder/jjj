package com.agent.arena

import org.json.JSONObject

/** LMArena 가 출력한 ```work 코드블록(JSON)을 해석한다. 언어 라벨이 달라도 JSON 스키마가 맞으면 인식. */
object WorkParser {

    data class Block(val status: String, val summary: String, val commands: List<JSONObject>)
    data class Parsed(val visible: String, val block: Block?, val looksBroken: Boolean)

    private val fence = Regex("```([A-Za-z0-9_-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String): Parsed {
        var block: Block? = null
        var broken = false
        val sb = StringBuilder()
        var last = 0
        for (m in fence.findAll(raw)) {
            val lang = m.groupValues[1].lowercase()
            val body = m.groupValues[2].trim()
            var isWork = false
            if (body.startsWith("{")) {
                try {
                    val o = JSONObject(body)
                    if (o.has("status") || o.has("commands")) {
                        block = toBlock(o)
                        isWork = true
                    }
                } catch (e: Exception) {
                    if (lang == "work") { broken = true; isWork = true }
                }
            } else if (lang == "work") {
                broken = true; isWork = true
            }
            sb.append(raw, last, m.range.first)
            if (!isWork) sb.append(m.value)
            last = m.range.last + 1
        }
        sb.append(raw, last, raw.length)
        var visible = stripOpenFence(sb.toString()).trim()
        if (block == null && raw.contains("```work")) broken = true
        if (visible.isBlank() && block == null && !broken) visible = raw.trim()
        return Parsed(visible, block, broken && block == null)
    }

    private fun toBlock(o: JSONObject): Block {
        val arr = o.optJSONArray("commands")
        val cmds = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { cmds.add(it) }
        val status = o.optString("status", if (cmds.isEmpty()) "done" else "continue").lowercase().trim()
        return Block(status, o.optString("summary", ""), cmds)
    }

    private fun fenceCount(s: String): Int {
        var n = 0
        var i = s.indexOf("```")
        while (i >= 0) { n++; i = s.indexOf("```", i + 3) }
        return n
    }

    private fun stripOpenFence(s: String): String {
        if (fenceCount(s) % 2 == 1) {
            val i = s.lastIndexOf("```")
            if (i >= 0) return s.substring(0, i)
        }
        return s
    }

    /** 스트리밍 중 화면에 보여줄 텍스트(미완성/완성된 work 블록 제거). */
    fun visibleStreaming(partial: String): String = parse(partial).visible

    fun isPlanning(partial: String): Boolean = fenceCount(partial) % 2 == 1 || partial.contains("```work")
}
