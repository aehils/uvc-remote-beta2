package com.expiation.reemanremote

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------- disinfection tasks

/** One stop of a task: a saved point, and how long the UV lamps stay on there. */
data class TaskStop(val point: String, val minutes: Int)

/** What the robot does once the last stop of the last round is done. */
enum class WhenDone(val label: String) {
    CHARGER("Go to charger"),
    START("Back to first point"),
    STAY("Stay"),
}

/**
 * A saved disinfection task, as in the robot's own app: the robot goes to each stop in turn, the
 * lamps come on [delaySeconds] after it arrives and stay on for the stop's minutes, then go off
 * before it moves on; all of it [rounds] times, then [whenDone]. Points are saved by name, so a
 * task outlives a point being moved; a point no longer on the map stops the task from starting.
 */
data class DisinfectionTask(
    val id: String,
    val name: String,
    val stops: List<TaskStop>,
    val delaySeconds: Int = DriveController.MIN_UV_DELAY_S,
    val rounds: Int = 1,
    val whenDone: WhenDone = WhenDone.CHARGER,
    /** The time of day it is due, in minutes from midnight (see [RoutineSchedule]); null: when someone starts it. */
    val startMinute: Int? = null,
    /** Which days that time comes round. */
    val repeat: Repeat = Repeat(),
    /** A route test (see [routeTest]): goes to the points with the lamps off the whole way. */
    val lampsOff: Boolean = false,
) {
    /** UV time over the whole task, every round. Travel and countdowns come on top. */
    val uvMinutes: Int get() = stops.sumOf { it.minutes } * rounds

    fun coerced() = copy(
        name = name.trim().take(MAX_NAME),
        stops = stops.take(MAX_STOPS).map { it.copy(minutes = it.minutes.coerceIn(1, DriveController.MAX_UV_MINUTES)) },
        delaySeconds = delaySeconds.coerceIn(DriveController.MIN_UV_DELAY_S, DriveController.MAX_UV_DELAY_S),
        rounds = rounds.coerceIn(1, MAX_ROUNDS),
        startMinute = startMinute?.coerceIn(0, 24 * 60 - 1),
        repeat = repeat.coerced(),
    )

    companion object {
        const val MAX_NAME = 32
        const val MAX_STOPS = 20
        const val MAX_ROUNDS = 10
        const val ROUTE_ID = "route"
        const val ROUTE_TEST_ID = "route_test"

        /**
         * What START DISINFECTION runs, like the robot's own app: every point on the map, in the
         * map's order, each for the same UV time and countdown, then the charger.
         */
        fun route(points: List<Waypoint>, settings: UvSettings) = DisinfectionTask(
            id = ROUTE_ID,
            name = "Disinfection",
            stops = points.map { TaskStop(it.name, settings.minutes) },
            delaySeconds = settings.delaySeconds,
            whenDone = WhenDone.CHARGER,
        )

        /**
         * Checks a route with no UV, as the robot's own app does: every point on the map in turn,
         * no lamps and no waiting, then onto the charger. What it finds is in its report.
         */
        fun routeTest(points: List<Waypoint>) = DisinfectionTask(
            id = ROUTE_TEST_ID,
            name = "Route test",
            stops = points.map { TaskStop(it.name, 1) },
            whenDone = WhenDone.STAY, // the charger is part of the test
            lampsOff = true,
        )

        fun listToJson(list: List<DisinfectionTask>): String =
            JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

        fun toJson(t: DisinfectionTask): JSONObject = JSONObject()
            .put("id", t.id)
            .put("name", t.name)
            .put("stops", JSONArray().apply {
                t.stops.forEach { put(JSONObject().put("point", it.point).put("minutes", it.minutes)) }
            })
            .put("delay_s", t.delaySeconds)
            .put("rounds", t.rounds)
            .put("when_done", t.whenDone.name)
            .put("start_minute", t.startMinute ?: JSONObject.NULL)
            .put("repeat", JSONObject()
                .put("every", t.repeat.every)
                .put("unit", t.repeat.unit.name)
                .put("weekdays", JSONArray(t.repeat.weekdays.sorted()))
                .put("start_day", t.repeat.startDay))
            .put("lamps_off", t.lampsOff)

        fun fromJson(o: JSONObject): DisinfectionTask {
            val stops = o.getJSONArray("stops")
            return DisinfectionTask(
                id = o.getString("id"),
                name = o.getString("name"),
                stops = (0 until stops.length()).map {
                    val s = stops.getJSONObject(it)
                    TaskStop(s.getString("point"), s.getInt("minutes"))
                },
                delaySeconds = o.optInt("delay_s", DriveController.MIN_UV_DELAY_S),
                rounds = o.optInt("rounds", 1),
                whenDone = runCatching { WhenDone.valueOf(o.getString("when_done")) }.getOrDefault(WhenDone.CHARGER),
                startMinute = if (o.isNull("start_minute")) null else o.getInt("start_minute"),
                repeat = o.optJSONObject("repeat")?.let { r ->
                    val days = r.optJSONArray("weekdays") ?: JSONArray()
                    Repeat(
                        every = r.optInt("every", 1),
                        unit = runCatching { RepeatUnit.valueOf(r.getString("unit")) }.getOrDefault(RepeatUnit.DAYS),
                        weekdays = (0 until days.length()).map { days.getInt(it) }.toSet(),
                        startDay = r.optInt("start_day", 0),
                    )
                } ?: Repeat(),
                lampsOff = o.optBoolean("lamps_off", false),
            ).coerced()
        }

        /** The saved tasks; whatever can't be read is dropped rather than failing the app. */
        fun listFromJson(json: String?): List<DisinfectionTask> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull() }
        }
    }
}

/** Where a running task is at its current stop (see [DriveController.startTask]). */
sealed interface TaskStage {
    /** Checking whether the robot already stands on the stop's point. */
    data object Checking : TaskStage

    /** On its way to the stop (undocking first, if it was on its pile). */
    data object Travelling : TaskStage

    /** At the stop: the UV run (countdown, then lamps on) is [RemoteState.uv]. */
    data object Disinfecting : TaskStage

    /** The stop's time is up: waiting for the robot to accept OFF before moving on. */
    data object LampsOff : TaskStage

    /** Couldn't get to the stop, lamps off: tries again at [nextTryAt], until [TaskRun.retryUntil], then moves on. */
    data class Retrying(val problem: String, val nextTryAt: Long) : TaskStage

    /** Paused by the user: robot stopped, lamps off, until [DriveController.resumeTask]. */
    data object Paused : TaskStage

    /** A route test's last part: trying the charger. */
    data object Docking : TaskStage
}

/** SKIPPED is only in reports saved by earlier builds: a stop now ends as NOT_REACHED by itself. */
enum class StopOutcome { DONE, SKIPPED, NOT_REACHED, INTERRUPTED }

/** What became of one stop in one round. [uvMs] is how long the lamps were on there. */
data class StopResult(val round: Int, val point: String, val outcome: StopOutcome, val uvMs: Long = 0)

/** A task under way. [round] counts from 1, [index] is the stop in [task]'s list. */
data class TaskRun(
    val task: DisinfectionTask,
    val round: Int,
    val index: Int,
    val stage: TaskStage,
    val results: List<StopResult>,
    /** When this stop is given up on; set by its first failed trip. */
    val retryUntil: Long? = null,
    /** UV time still to run at this stop, once a pause has interrupted it; null: all of it. */
    val uvLeftMs: Long? = null,
    /** UV time this stop has already had before a pause. */
    val uvDoneMs: Long = 0,
) {
    val stop: TaskStop get() = task.stops[index]

    /** Stops in the whole task, every round, and this one's place among them (from 1). */
    val total: Int get() = task.stops.size * task.rounds
    val number: Int get() = (round - 1) * task.stops.size + index + 1
}

/** How a task ended, kept on the Tasks tab until dismissed or the next task starts. */
data class TaskReport(
    val task: DisinfectionTask,
    /** Went through to its last stop (some may have been skipped: see [results]). */
    val completed: Boolean,
    /** Why it ended, when it didn't finish ("STOP pressed", "battery low (14%)"). */
    val reason: String?,
    val results: List<StopResult>,
    /** When it ended, on the phone's clock. */
    val at: Long = 0,
) {
    val done: Int get() = results.count { it.outcome == StopOutcome.DONE }
    val total: Int get() = task.stops.size * task.rounds

    /** Every stop of every round disinfected, or for a route test, reached and the charger docked on. */
    val good: Boolean get() = completed && done == total && reason == null

    /** "Ward A finished" */
    val title: String
        get() = when {
            !task.lampsOff -> "${task.name} ${if (completed) "finished" else "ended"}"
            good -> "Route test passed"
            completed -> "Route test found a problem"
            else -> "Route test ended"
        }

    /** The points the robot never got to, each once however many rounds missed it. */
    val notReached: List<String>
        get() = results.filter { it.outcome == StopOutcome.NOT_REACHED || it.outcome == StopOutcome.SKIPPED }.map { it.point }.distinct()

    /** "2 of 3 points done · not reached: Bed 2 · battery low (14%)" */
    val detail: String
        get() = "$done of $total point${if (total == 1) "" else "s"} ${if (task.lampsOff) "reached" else "done"}" +
            (if (notReached.isEmpty()) "" else " · not reached: ${notReached.joinToString()}") +
            (reason?.let { " · " + if (it.startsWith("ON ")) "the lamps didn't come on" else it } ?: "")

    /** What to do about how it went, for whoever is holding the phone; null when there's nothing to do. */
    val nextStep: String?
        get() {
            val r = reason.orEmpty()
            return when {
                r.startsWith("battery low") -> "Charge the robot, then run it again."
                r.startsWith("e-stop") -> "Release the e-stop when it's safe, then run it again."
                r == "lost connection" || r.startsWith("ON ") -> "Check the phone is on the robot's Wi-Fi, then run it again."
                r == "the robot moved" -> "Make sure nothing moves the robot while the lamps are on, then run it again."
                r == "app left" || r == "app closed" -> "Leave the app running in the background, then run it again."
                r == NO_PILE -> "Dock the robot by hand, then mark the charging pile on the Map page."
                r == NOT_DOCKED -> "Drive it in front of its charger and press GO TO CHARGER. If its screen asks, press Confirm."
                notReached.isNotEmpty() -> "Clear the way to ${notReached.joinToString()}, then run it again."
                else -> null
            }
        }

    companion object {
        const val MAX_HISTORY = 50
        const val NO_PILE = "no charging pile on the map"
        const val NOT_DOCKED = "didn't dock on its charger"

        fun listToJson(list: List<TaskReport>): String = JSONArray().apply {
            list.forEach { r ->
                put(
                    JSONObject()
                        .put("task", DisinfectionTask.toJson(r.task))
                        .put("completed", r.completed)
                        .put("reason", r.reason ?: JSONObject.NULL)
                        .put("at", r.at)
                        .put("results", JSONArray().apply {
                            r.results.forEach {
                                put(JSONObject().put("round", it.round).put("point", it.point).put("outcome", it.outcome.name).put("uv_ms", it.uvMs))
                            }
                        })
                )
            }
        }.toString()

        /** The saved reports; whatever can't be read is dropped rather than failing the app. */
        fun listFromJson(json: String?): List<TaskReport> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val results = o.getJSONArray("results")
                    TaskReport(
                        task = DisinfectionTask.fromJson(o.getJSONObject("task")),
                        completed = o.getBoolean("completed"),
                        reason = if (o.isNull("reason")) null else o.getString("reason"),
                        results = (0 until results.length()).map {
                            val r = results.getJSONObject(it)
                            StopResult(r.getInt("round"), r.getString("point"), StopOutcome.valueOf(r.getString("outcome")), r.getLong("uv_ms"))
                        },
                        at = o.getLong("at"),
                    )
                }.getOrNull()
            }
        }
    }
}
