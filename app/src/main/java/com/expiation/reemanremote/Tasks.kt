package com.expiation.reemanremote

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------- disinfection tasks

/** One stop of a task: a saved point, and how long the UV lamps stay on there. */
data class TaskStop(val point: String, val minutes: Int)

/** What the robot does once the last stop of the last round is done. */
enum class WhenDone(val label: String) {
    CHARGER("Go to charger"),
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
) {
    /** UV time over the whole task, every round. Travel and countdowns come on top. */
    val uvMinutes: Int get() = stops.sumOf { it.minutes } * rounds

    fun coerced() = copy(
        name = name.trim().take(MAX_NAME),
        stops = stops.take(MAX_STOPS).map { it.copy(minutes = it.minutes.coerceIn(1, DriveController.MAX_UV_MINUTES)) },
        delaySeconds = delaySeconds.coerceIn(DriveController.MIN_UV_DELAY_S, DriveController.MAX_UV_DELAY_S),
        rounds = rounds.coerceIn(1, MAX_ROUNDS),
    )

    companion object {
        const val MAX_NAME = 32
        const val MAX_STOPS = 20
        const val MAX_ROUNDS = 10
        const val PLAN_ID = "plan"

        /**
         * What START DISINFECTION runs: the points picked on the Tasks tab, in the order they were
         * picked, each for the same UV time and countdown, then [whenDone].
         */
        fun plan(points: List<String>, settings: UvSettings, whenDone: WhenDone) = DisinfectionTask(
            id = PLAN_ID,
            name = "Disinfection",
            stops = points.map { TaskStop(it, settings.minutes) },
            delaySeconds = settings.delaySeconds,
            whenDone = whenDone,
        ).coerced()

        fun listToJson(list: List<DisinfectionTask>): String = JSONArray().apply {
            list.forEach { t ->
                put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
                        .put("stops", JSONArray().apply {
                            t.stops.forEach { put(JSONObject().put("point", it.point).put("minutes", it.minutes)) }
                        })
                        .put("delay_s", t.delaySeconds)
                        .put("rounds", t.rounds)
                        .put("when_done", t.whenDone.name)
                )
            }
        }.toString()

        /** The saved tasks; whatever can't be read is dropped rather than failing the app. */
        fun listFromJson(json: String?): List<DisinfectionTask> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val stops = o.getJSONArray("stops")
                    DisinfectionTask(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        stops = (0 until stops.length()).map {
                            val s = stops.getJSONObject(it)
                            TaskStop(s.getString("point"), s.getInt("minutes"))
                        },
                        delaySeconds = o.optInt("delay_s", DriveController.MIN_UV_DELAY_S),
                        rounds = o.optInt("rounds", 1),
                        whenDone = runCatching { WhenDone.valueOf(o.getString("when_done")) }.getOrDefault(WhenDone.CHARGER),
                    ).coerced()
                }.getOrNull()
            }
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

    /** Couldn't get to the stop. Lamps off, robot still: retry, skip or end (see [DriveController.retryTaskStop]). */
    data class Held(val problem: String) : TaskStage
}

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
) {
    val done: Int get() = results.count { it.outcome == StopOutcome.DONE }
    val total: Int get() = task.stops.size * task.rounds
}
