package com.zltm90plus.app

import android.app.Application
import com.zltm90plus.app.diagnostics.DiagnosticLog
import com.zltm90plus.app.diagnostics.Diagnostics

/**
 * Application entry point.
 *
 * Demo mode is opt-in per session and never persisted, so the app always starts in the honest
 * "connect to a real device" state.
 *
 * The connection log is installed here, which is what makes the transport record what it sent and
 * received. It is an in-memory ring the user reads from the diagnostics screen and shares
 * deliberately; nothing is uploaded and nothing is written to disk.
 */
class ZltApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(DiagnosticLog)
    }
}