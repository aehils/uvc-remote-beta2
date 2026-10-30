package com.expiation.reemanremote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address

/**
 * Owns the [DriveController] for as long as the app's process lives, so the connection survives
 * screen recreation (rotation, dark-mode switch) and a routine carries on with no screen at all
 * (see [RunService]), and remembers the robot address, demo choice,
 * disinfection settings and saved disinfection tasks.
 *
 * It also follows the phone's Wi-Fi. Requests go out over Wi-Fi even when mobile data is on (the
 * robot's Wi-Fi often has no internet, so Android would send them over mobile data), and when
 * the robot stops answering, the controller searches the Wi-Fi for it: by its name first (see
 * [Mdns]), then at every address it has had, then across the phone's network (see [RobotFinder]).
 */
class RobotSession private constructor(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var wifi: Network? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            connectivity.bindProcessToNetwork(network)
            scope.launch { controller.networkChanged() }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == wifi) scope.launch { controller.networkChanged() }
        }

        override fun onLost(network: Network) {
            if (network != wifi) return
            wifi = null
            connectivity.bindProcessToNetwork(null)
        }
    }

    val controller: DriveController

    private val _dueRoutine = MutableStateFlow<DisinfectionTask?>(null)

    /** The routine whose time has come, while the app waits for someone to start it or put it off. */
    val dueRoutine: StateFlow<DisinfectionTask?> = _dueRoutine

    private val _history = MutableStateFlow(TaskReport.listFromJson(prefs.getString(KEY_HISTORY, null)))

    /** How the routines went, newest first, kept across restarts (see [TaskReport.MAX_HISTORY]). */
    val history: StateFlow<List<TaskReport>> = _history

    /** [RunService] is up, with its notification showing. */
    @Volatile
    var serviceUp = false

    private var onScreen = false

    init {
        val host = prefs.getString(KEY_HOST, null) ?: DriveController.DEFAULT_HOST
        // Until a mode has been chosen, an emulator starts in demo: it shares the Mac's
        // network, so on the robot's Wi-Fi it could otherwise drive the real robot.
        val emulatorDefault = !prefs.contains(KEY_DEMO) && isEmulator()
        val demo = prefs.getBoolean(KEY_DEMO, emulatorDefault)
        controller = DriveController(
            scope, RobotApi(host), FakeRobot(), demo,
            search = ::searchWifi, robotName = prefs.getString(KEY_ROBOT_NAME, null),
        )
        val defaults = UvSettings()
        controller.setUvSettings(
            UvSettings(prefs.getInt(KEY_UV_MINUTES, defaults.minutes), prefs.getInt(KEY_UV_DELAY, defaults.delaySeconds))
        )
        controller.setLowBattery(prefs.getInt(KEY_LOW_BATTERY, DriveController.DEFAULT_LOW_BATTERY))
        prefs.getFloat(KEY_RUNNING_SPEED, 0f).takeIf { it > 0f }?.let { controller.setRunningSpeed(it.toDouble()) }
        controller.setTasks(DisinfectionTask.listFromJson(prefs.getString(KEY_TASKS, null)))
        RoutineAlarms.schedule(app, controller.state.value.tasks)
        scope.launch {
            while (true) {
                checkDue()
                delay(DUE_CHECK_MS)
            }
        }
        if (emulatorDefault) controller.note("Emulator detected: starting in DEMO mode")
        controller.note(if (demo) "App started in DEMO MODE (simulated robot)" else "App started. Robot address $host")
        controller.testConnection(null)
        // Remember where the robot is (it may be found at a new address), how to recognise it, and
        // every address it has answered at (a search tries them all).
        scope.launch {
            controller.state.map { if (it.demo) null else Triple(it.host, it.robotName, it.connected) }.distinctUntilChanged().collect { live ->
                val (host, name, connected) = live ?: return@collect
                var past = if (name == prefs.getString(KEY_ROBOT_NAME, null)) pastHosts() else emptyList()
                if (connected) past = (listOf(host) + past).distinct().take(MAX_PAST_HOSTS)
                prefs.edit()
                    .putString(KEY_HOST, host)
                    .putString(KEY_ROBOT_NAME, name)
                    .putString(KEY_PAST_HOSTS, past.joinToString(","))
                    .remove(KEY_ROBOT_MAP) // how earlier builds recognised the robot
                    .apply()
            }
        }
        scope.launch { followRuns() }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback,
        )
    }

    fun resume() {
        onScreen = true
        controller.resume()
    }

    /** A run carries on only with the notification up: otherwise nothing would show it, or stop it. */
    fun pause() {
        onScreen = false
        val manager = app.getSystemService(NotificationManager::class.java)
        controller.keepRunningInBackground = serviceUp && manager.areNotificationsEnabled()
        controller.pause()
    }

    /** Starts and stops [RunService] with each run, keeps the reports, and tells whoever is away. */
    private suspend fun followRuns() {
        var underWay = false
        var wasTask = false
        var lastReport = controller.state.value.taskReport?.at
        var lastProblem: String? = null
        controller.state.collect { s ->
            val report = s.taskReport
            if (report != null && report.at != lastReport) {
                lastReport = report.at
                _history.value = (listOf(report) + _history.value).take(TaskReport.MAX_HISTORY)
                prefs.edit().putString(KEY_HISTORY, TaskReport.listToJson(_history.value)).apply()
                if (!onScreen) RunService.alert(app, RunService.ID_FINISHED, report.title, report.detail)
            }
            val problem = s.needsSomeone
            if (problem != lastProblem) {
                lastProblem = problem
                if (problem != null && !onScreen) RunService.alert(app, RunService.ID_PROBLEM, problem, "Open the app.")
            }
            wasTask = wasTask || s.taskRun != null
            if (s.runUnderWay != underWay) {
                underWay = s.runUnderWay
                if (underWay) {
                    runCatching { RunService.start(app) }
                } else {
                    RunService.stop(app)
                    if (!wasTask && !onScreen) RunService.alert(app, RunService.ID_FINISHED, "UV lamps off", null)
                    wasTask = false
                }
            }
        }
    }

    /**
     * Looks for robots from the phone's Wi-Fi. The robot with [hostname] is asked for by name
     * first, which takes a moment; failing that, every address it has had and every address on
     * the phone's network are asked at once.
     */
    private suspend fun searchWifi(lastHost: String, hostname: String?): SearchResult {
        val none = SearchResult(0, emptyList())
        val network = wifi ?: return none
        if (hostname != null) {
            val ip = Mdns.resolve(hostname, bind = network::bindSocket)?.takeIf(RobotApi::isLocalAddress)
            val robot = ip?.let { RobotFinder.identify(RobotApi(it)) }
            if (robot?.hostname == hostname) return SearchResult(1, listOf(robot))
        }
        val v4 = connectivity.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return none
        val known = (listOf(lastHost) + pastHosts()).distinct()
        val hosts = RobotFinder.candidates(v4.address.hostAddress ?: return none, v4.prefixLength, known)
        return SearchResult(hosts.size, RobotFinder.find(hosts) { RobotFinder.identify(RobotApi(it)) })
    }

    private fun pastHosts(): List<String> =
        prefs.getString(KEY_PAST_HOSTS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

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

    fun setLowBattery(percent: Int) {
        controller.setLowBattery(percent)
        prefs.edit().putInt(KEY_LOW_BATTERY, controller.state.value.lowBattery).apply()
    }

    /** Null: the default. */
    fun setRunningSpeed(speed: Double?) {
        controller.setRunningSpeed(speed)
        val saved = controller.state.value.runningSpeed
        prefs.edit().apply { if (saved == null) remove(KEY_RUNNING_SPEED) else putFloat(KEY_RUNNING_SPEED, saved.toFloat()) }.apply()
    }

    fun saveTask(task: DisinfectionTask) {
        controller.saveTask(task)
        saveTasks()
        val tasks = controller.state.value.tasks
        tasks.firstOrNull { it.name == task.name.trim() }?.let { saved ->
            // A time already gone today waits for tomorrow; one still to come counts today.
            val now = System.currentTimeMillis()
            val at = saved.startMinute
            setAsked(saved.id, at != null && at <= RoutineSchedule.minuteOfDay(now))
        }
        RoutineAlarms.schedule(app, tasks)
    }

    fun deleteTask(id: String) {
        controller.deleteTask(id)
        if (controller.state.value.tasks.any { it.id == id }) return // the task under way stays
        saveTasks()
        RoutineAlarms.cancel(app, id)
        if (_dueRoutine.value?.id == id) _dueRoutine.value = null
    }

    /** Someone answered the routine that came due: never asked again today, whichever they chose. */
    fun answerDue(start: Boolean) {
        val task = _dueRoutine.value ?: return
        _dueRoutine.value = null
        setAsked(task.id, true)
        RoutineAlarms.clearReminder(app, task.id)
        if (start) controller.startTask(task.id)
    }

    /** Not over a task or lamps already going: it can wait for them, within its [RoutineSchedule.GRACE_MINUTES]. */
    private fun checkDue() {
        if (_dueRoutine.value != null) return
        val s = controller.state.value
        if (s.taskRun != null || s.uv != null) return
        val now = System.currentTimeMillis()
        val minute = RoutineSchedule.minuteOfDay(now)
        val today = RoutineSchedule.epochDay(now)
        val due = RoutineSchedule.due(s.tasks, minute, today, asked())
        _dueRoutine.value = due
        // Its time is a while gone and no reminder went up: leave a trace of why the phone stayed quiet.
        if (due != null && minute - (due.startMinute ?: minute) >= REMINDER_LATE_MINUTES && RoutineAlarms.remindedOn(app, due.id) != today) {
            val blocked = !app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            controller.note(
                "No reminder went up for ${due.name}: " +
                    if (blocked) "notifications are off for this app" else "the phone held it back, or stopped the app in the background"
            )
        }
    }

    private fun asked(): Map<String, Int> {
        val o = runCatching { JSONObject(prefs.getString(KEY_ROUTINES_ASKED, null) ?: "{}") }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    private fun setAsked(id: String, today: Boolean) {
        val asked = asked().toMutableMap()
        if (today) asked[id] = RoutineSchedule.epochDay(System.currentTimeMillis()) else asked.remove(id)
        prefs.edit().putString(KEY_ROUTINES_ASKED, JSONObject(asked as Map<*, *>).toString()).apply()
    }

    private fun saveTasks() {
        prefs.edit().putString(KEY_TASKS, DisinfectionTask.listToJson(controller.state.value.tasks)).apply()
    }

    companion object {
        private var instance: RobotSession? = null

        /** The one session: a second controller could fight the first over the same robot. */
        fun get(app: Application): RobotSession = instance ?: RobotSession(app).also { instance = it }

        private const val PREFS = RoutineAlarms.PREFS   // same file as beta 0.1, so settings carry over
        private const val KEY_HOST = "host"
        private const val KEY_DEMO = "demo"
        private const val KEY_UV_MINUTES = "uv_minutes"
        private const val KEY_UV_DELAY = "uv_delay_s"
        private const val KEY_TASKS = RoutineAlarms.KEY_TASKS
        private const val KEY_ROUTINES_ASKED = "routines_asked" // routine id to the day it was last asked about
        private const val KEY_HISTORY = "routine_history"
        private const val KEY_LOW_BATTERY = "low_battery"
        private const val KEY_RUNNING_SPEED = "running_speed"
        private const val DUE_CHECK_MS = 20_000L
        private const val REMINDER_LATE_MINUTES = 2
        private const val KEY_ROBOT_MAP = "robot_map"   // earlier builds: removed
        private const val KEY_ROBOT_NAME = "robot_name" // the robot's hostname: how it is recognised at a new address
        private const val KEY_PAST_HOSTS = "past_hosts" // addresses it has answered at, most recent first
        private const val MAX_PAST_HOSTS = 6

        private fun isEmulator() = Build.FINGERPRINT.startsWith("generic") || "emulator" in Build.FINGERPRINT ||
            "ranchu" in Build.HARDWARE || "goldfish" in Build.HARDWARE || "sdk" in Build.PRODUCT
    }
}
