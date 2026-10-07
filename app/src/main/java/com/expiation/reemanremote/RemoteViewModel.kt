package com.expiation.reemanremote

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/**
 * Owns the [DriveController] so the connection survives screen recreation
 * (rotation, dark-mode switch), and remembers the robot address, demo choice and
 * disinfection settings.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val controller: DriveController

    init {
        val host = prefs.getString(KEY_HOST, null) ?: DriveController.DEFAULT_HOST
        // Until a mode has been chosen, an emulator starts in demo: it shares the Mac's
        // network, so on the robot's Wi-Fi it could otherwise drive the real robot.
        val emulatorDefault = !prefs.contains(KEY_DEMO) && isEmulator()
        val demo = prefs.getBoolean(KEY_DEMO, emulatorDefault)
        controller = DriveController(viewModelScope, RobotApi(host), FakeRobot(), demo)
        val defaults = UvSettings()
        controller.setUvSettings(
            UvSettings(prefs.getInt(KEY_UV_MINUTES, defaults.minutes), prefs.getInt(KEY_UV_DELAY, defaults.delaySeconds))
        )
        if (emulatorDefault) controller.note("Emulator detected: starting in DEMO mode")
        controller.note(if (demo) "App started in DEMO MODE (simulated robot)" else "App started. Robot address $host")
        controller.testConnection(null)
    }

    fun setDemo(on: Boolean) {
        if (controller.setDemo(on)) prefs.edit().putBoolean(KEY_DEMO, on).apply()
    }

    fun testConnection(hostText: String) {
        controller.testConnection(hostText)?.let { prefs.edit().putString(KEY_HOST, it).apply() }
    }

    fun setUvSettings(settings: UvSettings) {
        controller.setUvSettings(settings)
        val saved = controller.state.value.uvSettings
        prefs.edit().putInt(KEY_UV_MINUTES, saved.minutes).putInt(KEY_UV_DELAY, saved.delaySeconds).apply()
    }

    private companion object {
        const val PREFS = "reeman_remote"   // same file as beta 0.1, so settings carry over
        const val KEY_HOST = "host"
        const val KEY_DEMO = "demo"
        const val KEY_UV_MINUTES = "uv_minutes"
        const val KEY_UV_DELAY = "uv_delay_s"

        fun isEmulator() = Build.FINGERPRINT.startsWith("generic") || "emulator" in Build.FINGERPRINT ||
            "ranchu" in Build.HARDWARE || "goldfish" in Build.HARDWARE || "sdk" in Build.PRODUCT
    }
}
