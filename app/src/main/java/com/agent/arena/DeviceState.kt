package com.agent.arena

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** UI 를 누르지 않고 시스템 API/인텐트로 즉시 수행하는 "직접 명령" 모음. */
object DeviceActions {

    // ------------------------------------------------------------------ 앱 목록/검색
    @Volatile private var appCache: List<Pair<String, String>> = emptyList()
    @Volatile private var appCacheAt = 0L

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private val aliases = mapOf(
        "유튜브" to "youtube", "크롬" to "chrome", "카톡" to "카카오톡", "설정" to "settings",
        "카메라" to "camera", "갤러리" to "gallery", "계산기" to "calculator", "지도" to "maps",
        "플레이스토어" to "playstore", "문자" to "messages", "전화" to "phone", "시계" to "clock",
        "캘린더" to "calendar", "일정" to "calendar", "메모" to "notes", "파일" to "files",
    )

    fun listApps(ctx: Context): List<Pair<String, String>> {
        val now = SystemClock.uptimeMillis()
        if (appCache.isNotEmpty() && now - appCacheAt < 60_000) return appCache
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        appCache = list; appCacheAt = now
        return list
    }

    /** 이름(라벨/별칭/패키지)으로 앱을 퍼지 검색. */
    fun findApp(ctx: Context, query: String): Pair<String, String>? {
        val apps = listApps(ctx)
        val q0 = norm(query)
        if (q0.isEmpty()) return null
        val candidates = listOfNotNull(q0, aliases[query.trim().lowercase()]?.let { norm(it) }, aliases[q0]?.let { norm(it) }).distinct()
        for (q in candidates) {
            apps.firstOrNull { norm(it.first) == q || it.second.equals(query, true) }?.let { return it }
        }
        for (q in candidates) {
            apps.filter { norm(it.first).contains(q) || it.second.lowercase().contains(q) }
                .minByOrNull { it.first.length }?.let { return it }
        }
        for (q in candidates) {
            apps.filter { norm(it.first).length >= 2 && q.contains(norm(it.first)) }
                .maxByOrNull { it.first.length }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ 볼륨
    fun volume(ctx: Context, c: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (stream, label) = when (c.optString("stream", "music").lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING to "벨소리"
            "alarm" -> AudioManager.STREAM_ALARM to "알람"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "알림"
            "system" -> AudioManager.STREAM_SYSTEM to "시스템"
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL to "통화"
            else -> AudioManager.STREAM_MUSIC to "미디어"
        }
        val mx = am.getStreamMaxVolume(stream)
        val mn = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
        val cur = am.getStreamVolume(stream)
        val target = when {
            c.optBoolean("mute", false) -> mn
            c.has("set") -> (c.getDouble("set") / 100.0 * mx).roundToInt()
            c.has("delta_percent") -> {
                val dp = c.getDouble("delta_percent")
                val steps = max(1, (abs(dp) / 100.0 * mx).roundToInt())
                if (dp >= 0) cur + steps else cur - steps
            }
            c.has("delta") -> cur + c.getInt("delta")
            else -> return "error: volume requires set(0-100), delta_percent, delta or mute"
        }.coerceIn(mn, mx)
        return try {
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            "ok: $label 볼륨 ${(target * 100f / mx).roundToInt()}% ($target/$mx)"
        } catch (e: SecurityException) {
            "error: 방해금지 모드 때문에 $label 볼륨을 바꿀 수 없음"
        }
    }

    // ------------------------------------------------------------------ 밝기 (WRITE_SETTINGS 필요)
    fun brightness(ctx: Context, c: JSONObject): String {
        if (!Settings.System.canWrite(ctx)) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
            return "error: 밝기 조절에는 '시스템 설정 수정' 권한이 필요해요. 권한 화면을 열었으니 허용한 뒤 다시 요청해 주세요. (need_user)"
        }
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val pct = when {
            c.has("set") -> c.getDouble("set")
            c.has("delta_percent") -> cur * 100.0 / 255 + c.getDouble("delta_percent")
            else -> return "error: brightness requires set or delta_percent"
        }.coerceIn(1.0, 100.0)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct / 100.0 * 255).roundToInt().coerceIn(1, 255))
        return "ok: 밝기 ${pct.roundToInt()}%"
    }

    // ------------------------------------------------------------------ 손전등
    fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "error: 플래시가 없는 기기"
            cm.setTorchMode(id, on)
            "ok: 손전등을 ${if (on) "켰어요" else "껐어요"}"
        } catch (e: Exception) {
            "error: 손전등 제어 실패 (${e.message})"
        }
    }

    // ------------------------------------------------------------------ 미디어 키
    fun media(ctx: Context, key: String): String {
        val code = when (key.lowercase()) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "error: unknown media key '$key'"
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "ok: 미디어 $key"
    }

    // ------------------------------------------------------------------ 설정 화면 바로 열기
    fun openSettings(ctx: Context, page: String): String {
        val action = when (page.lowercase()) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "main", "" -> Settings.ACTION_SETTINGS
            else -> return "error: unknown settings page '$page'"
        }
        return start(ctx, Intent(action), "ok: 설정($page) 화면을 열었어요")
    }

    // ------------------------------------------------------------------ 알람 / 타이머 / 전화 / 문자
    fun setAlarm(ctx: Context, c: JSONObject): String {
        val h = c.optInt("hour", -1); val m = c.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) return "error: set_alarm requires hour(0-23) and minute(0-59)"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.optString("label", ""))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(ctx, i, "ok: 알람을 ${"%02d:%02d".format(h, m)}에 맞췄어요")
    }

    fun setTimer(ctx: Context, c: JSONObject): String {
        val sec = c.optInt("seconds", -1)
        if (sec <= 0) return "error: set_timer requires seconds > 0"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val txt = when {
            sec >= 3600 && sec % 3600 == 0 -> "${sec / 3600}시간"
            sec >= 60 && sec % 60 == 0 -> "${sec / 60}분"
            else -> "${sec}초"
        }
        return start(ctx, i, "ok: $txt 타이머를 시작했어요")
    }

    fun dial(ctx: Context, number: String): String {
        if (number.isBlank()) return "error: dial requires number"
        return start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "ok: 전화 앱에 번호를 입력해 두었어요")
    }

    fun composeSms(ctx: Context, c: JSONObject): String {
        val n = c.optString("number")
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", c.optString("body"))
        return start(ctx, i, "ok: 문자 작성 화면을 열었어요 (전송은 사용자 확인 필요)")
    }

    private fun start(ctx: Context, i: Intent, okMsg: String): String = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        okMsg
    } catch (e: Exception) {
        "error: ${e.javaClass.simpleName}: ${e.message}"
    }
}
