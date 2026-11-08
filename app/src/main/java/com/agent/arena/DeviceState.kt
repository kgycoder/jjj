package com.agent.arena

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import java.time.ZonedDateTime
import java.util.Locale

/** LMArena 로 함께 보내는 기기 상태/환경 정보. */
object DeviceState {

    fun info(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return buildString {
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("screen_px: ${dm.widthPixels}x${dm.heightPixels} (density ${dm.density})")
            appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("local_time: ${ZonedDateTime.now()}")
            appendLine("battery: $battery%")
            append("accessibility_service: ${if (AgentAccessibilityService.instance != null) "enabled" else "DISABLED (cannot see or control the device)"}")
        }
    }

    fun apps(ctx: Context): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .take(150)
            .joinToString("\n") { "${it.first} | ${it.second}" }
    }

    fun screen(): String {
        val svc = AgentAccessibilityService.instance
            ?: return "(접근성 서비스가 꺼져 있어 화면을 볼 수 없음. 기기 조작이 필요하면 need_user 로 사용자에게 접근성 권한 활성화를 요청하세요.)"
        return svc.dumpScreen()
    }
}
