package com.pulsepointlabs.sarahmonitor.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * If the app crashes, the reason is written to a small file before Android closes it. Next time the app opens it
 * offers that note ("The app closed unexpectedly… Copy details") so Ben can send it, instead of crashes leaving no
 * trace on his phone.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                File(app.filesDir, FILE).writeText(
                    "Sarah Monitor $version crashed ${java.time.LocalDateTime.now().withNano(0)} on thread ${thread.name}\n$trace".take(20_000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The note from the last crash, if there is one not yet dismissed. */
    fun pending(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun dismiss(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }

    /** First line in plain words, for the dialog. Pure; unit-tested. */
    fun summary(note: String): String {
        val lines = note.lines()
        val cause = lines.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.substringAfterLast('.')?.take(160) ?: "unknown"
        return lines.first().removePrefix("Sarah Monitor ") + "\nCause: " + cause
    }
}
