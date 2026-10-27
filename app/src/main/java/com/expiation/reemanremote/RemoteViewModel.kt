package com.expiation.reemanremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * Owns the [DriveController] so the connection survives screen recreation
 * (rotation, dark-mode switch), and remembers the robot address, demo choice,
 * disinfection settings and saved disinfection tasks.
 *
 * It also follows the phone's Wi-Fi. Requests go out over Wi-Fi even when mobile data is on (the
 * robot's Wi-Fi often has no internet, so Android would send them over mobile data), and when
 * the robot stops answering, the controller searches the Wi-Fi for it (see [RobotFinder]).
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var wifi: Network? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            connectivity.bindProcessToNetwork(network)
            viewModelScope.launch { controller.networkChanged() }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == wifi) viewModelScope.launch { controller.networkChanged() }
        }

        override fun onLost(network: Network) {
            if (network != wifi) return
            wifi = null
            connectivity.bindProcessToNetwork(null)
        }
    }

    val controller: DriveController

    init {
        val host = prefs.getString(KEY_HOST, null) ?: DriveController.DEFAULT_HOST
        // Until a mode has been chosen, an emulator starts in demo: it shares the Mac's
        // network, so on the robot's Wi-Fi it could otherwise drive the real robot.
        val emulatorDefault = !prefs.contains(KEY_DEMO) && isEmulator()
        val demo = prefs.getBoolean(KEY_DEMO, emulatorDefault)
        controller = DriveController(
            viewModelScope, RobotApi(host), FakeRobot(), demo,
            search = ::searchWifi, robotMap = prefs.getString(KEY_ROBOT_MAP, null),
        )
        val defaults = UvSettings()
        controller.setUvSettings(
            UvSettings(prefs.getInt(KEY_UV_MINUTES, defaults.minutes), prefs.getInt(KEY_UV_DELAY, defaults.delaySeconds))
        )
        controller.setTasks(DisinfectionTask.listFromJson(prefs.getString(KEY_TASKS, null)))
        if (emulatorDefault) controller.note("Emulator detected: starting in DEMO mode")
        controller.note(if (demo) "App started in DEMO MODE (simulated robot)" else "App started. Robot address $host")
        controller.testConnection(null)
        // Remember where the robot is (it may be found at a new address) and how to recognise it.
        viewModelScope.launch {
            controller.state.map { if (it.demo) null else it.host to it.robotMap }.distinctUntilChanged().collect { live ->
                if (live != null) prefs.edit().putString(KEY_HOST, live.first).putString(KEY_ROBOT_MAP, live.second).apply()
            }
        }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback,
        )
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(wifiCallback)
        connectivity.bindProcessToNetwork(null)
    }

    /** Every address on the phone's Wi-Fi network that a robot could be at, asked at once. */
    private suspend fun searchWifi(lastHost: String): SearchResult {
        val none = SearchResult(0, emptyList())
        val network = wifi ?: return none
        val v4 = connectivity.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return none
        val hosts = RobotFinder.candidates(v4.address.hostAddress ?: return none, v4.prefixLength, lastHost)
        return SearchResult(hosts.size, RobotFinder.find(hosts) { RobotFinder.identify(RobotApi(it)) })
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

    fun saveTask(task: DisinfectionTask) {
        controller.saveTask(task)
        saveTasks()
    }

    fun deleteTask(id: String) {
        controller.deleteTask(id)
        saveTasks()
    }

    private fun saveTasks() {
        prefs.edit().putString(KEY_TASKS, DisinfectionTask.listToJson(controller.state.value.tasks)).apply()
    }

    private companion object {
        const val PREFS = "reeman_remote"   // same file as beta 0.1, so settings carry over
        const val KEY_HOST = "host"
        const val KEY_DEMO = "demo"
        const val KEY_UV_MINUTES = "uv_minutes"
        const val KEY_UV_DELAY = "uv_delay_s"
        const val KEY_TASKS = "tasks"
        const val KEY_ROBOT_MAP = "robot_map" // the robot's map in use: how it is recognised at a new address

        fun isEmulator() = Build.FINGERPRINT.startsWith("generic") || "emulator" in Build.FINGERPRINT ||
            "ranchu" in Build.HARDWARE || "goldfish" in Build.HARDWARE || "sdk" in Build.PRODUCT
    }
}
