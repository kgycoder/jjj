package com.agent.arena

import android.app.Application

class AgentApp : Application() {
    lateinit var controller: ArenaController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReport.install(this)
        controller = ArenaController(this)
    }

    companion object {
        lateinit var instance: AgentApp
            private set
    }
}
