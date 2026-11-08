package com.agent.arena

import android.content.Context
import android.util.Log
import java.io.File

/** 치명적 예외를 파일에 남기고, 다음 실행 때 화면에 보여 줘서 크래시 루프를 끊는다. */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(ctx).writeText(Log.getStackTraceString(e).take(8000))
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
