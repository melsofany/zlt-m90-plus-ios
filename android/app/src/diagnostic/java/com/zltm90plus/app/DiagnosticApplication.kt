package com.zltm90plus.app

import android.app.Application
import com.zltm90plus.app.diagnostics.DiagnosticReporter
import com.zltm90plus.app.diagnostics.Diagnostics

/**
 * Diagnostic variant of the application entry point.
 *
 * It installs a reporter before anything can make a request, so the very first connection attempt
 * is captured. This class exists only in the `diagnostic` source set; [ZltApplication] never
 * installs a sink and therefore never records or uploads anything.
 */
class DiagnosticApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val reporter = DiagnosticReporter(
            context = this,
            endpoint = BuildConfig.DIAGNOSTIC_ENDPOINT,
            deviceLabel = "diagnostic-${BuildConfig.VERSION_NAME}",
        )
        Diagnostics.install(reporter)
        reporter.beginSession(
            context = this,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}