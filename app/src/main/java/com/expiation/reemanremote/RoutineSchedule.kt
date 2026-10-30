package com.expiation.reemanremote

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class RepeatUnit { DAYS, WEEKS }

/**
 * How often a routine with a time of day comes round: every [every] days, or every [every] weeks
 * on its [weekdays] (0 is Monday). Counted from [startDay], so "every other day" and "every 2
 * weeks" have a day or a week to count from. Days are local days since 1970 (see [RoutineSchedule.epochDay]).
 */
data class Repeat(
    val every: Int = 1,
    val unit: RepeatUnit = RepeatUnit.DAYS,
    val weekdays: Set<Int> = emptySet(),
    val startDay: Int = 0,
) {
    fun occursOn(day: Int): Boolean {
        if (day < startDay) return false
        return when (unit) {
            RepeatUnit.DAYS -> (day - startDay) % every == 0
            RepeatUnit.WEEKS -> {
                val weeks = (weekStart(day) - weekStart(startDay)) / 7
                weeks % every == 0 && RoutineSchedule.weekday(day) in weekdays
            }
        }
    }

    /** "Every day", "Every 2 days", "Every week on Mon, Thu", "Every 2 weeks on Mon" */
    val text: String
        get() = when {
            unit == RepeatUnit.DAYS -> if (every == 1) "Every day" else "Every $every days"
            every == 1 && weekdays.size == 7 -> "Every day"
            else -> (if (every == 1) "Every week" else "Every $every weeks") + " on " +
                weekdays.sorted().joinToString { RoutineSchedule.WEEKDAYS[it] }
        }

    fun coerced(): Repeat {
        val n = every.coerceIn(1, if (unit == RepeatUnit.DAYS) MAX_DAYS else MAX_WEEKS)
        return if (unit == RepeatUnit.DAYS) copy(every = n, weekdays = emptySet())
        else copy(every = n, weekdays = weekdays.filter { it in 0..6 }.toSet().ifEmpty { setOf(RoutineSchedule.weekday(startDay)) })
    }

    private fun weekStart(day: Int) = day - RoutineSchedule.weekday(day)

    companion object {
        const val MAX_DAYS = 30
        const val MAX_WEEKS = 12
    }
}

/**
 * When a routine with a time of day is due. A routine is never started by the clock: UV-C burns,
 * so someone has to say the rooms are clear (see [RobotSession.answerDue]). The clock only asks.
 */
object RoutineSchedule {

    /** How long after its time a routine is still asked about when the app is opened. */
    const val GRACE_MINUTES = 60

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** "08:05" */
    fun timeText(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    fun minuteOfDay(nowMs: Long): Int = calendar(nowMs).run { get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE) }

    /** Today on the phone's calendar, in days since 1970: the same number all day, one more the next. */
    fun epochDay(nowMs: Long): Int = Math.floorDiv(nowMs + TimeZone.getDefault().getOffset(nowMs), DAY_MS).toInt()

    /** 0 is Monday. */
    fun weekday(day: Int): Int = Math.floorMod(day + 3, 7)

    /** "Sat 31 Oct" */
    fun dateText(day: Int): String =
        SimpleDateFormat("EEE d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(day * DAY_MS))

    /** Where a new schedule starts counting: today if [minuteOfDay] is still to come, otherwise tomorrow. */
    fun firstDay(minuteOfDay: Int, nowMs: Long): Int =
        epochDay(nowMs) + if (minuteOfDay > minuteOfDay(nowMs)) 0 else 1

    /** When [task] next comes round, strictly after [nowMs]; null with no time of day. */
    fun nextAt(task: DisinfectionTask, nowMs: Long): Long? {
        val minute = task.startMinute ?: return null
        val first = maxOf(epochDay(nowMs), task.repeat.startDay)
        for (day in first..first + LOOK_AHEAD_DAYS) {
            if (!task.repeat.occursOn(day)) continue
            val at = atMs(day, minute)
            if (at > nowMs) return at
        }
        return null
    }

    private fun atMs(day: Int, minute: Int): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = day * DAY_MS }
        return Calendar.getInstance().apply {
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), minute / 60, minute % 60, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** The first routine that comes round [today], whose time has just come, not asked about today ([asked]: id to [epochDay]). */
    fun due(tasks: List<DisinfectionTask>, minuteNow: Int, today: Int, asked: Map<String, Int>): DisinfectionTask? =
        tasks.firstOrNull { t ->
            val at = t.startMinute ?: return@firstOrNull false
            t.repeat.occursOn(today) && minuteNow in at until at + GRACE_MINUTES && asked[t.id] != today
        }

    private const val LOOK_AHEAD_DAYS = 400 // past the longest gap there is: 12 weeks

    private fun calendar(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }
}

/**
 * Wakes the phone at each routine's time to post a reminder, since the app is usually closed then.
 * Tapping it opens the app, which asks (see [RobotSession]). An exact alarm: an inexact one may be
 * held back until the phone next wakes, so the reminder could come after the app had already asked.
 * A phone maker's battery manager can still kill the app so that no alarm fires; [RobotSession]
 * notes that in the log when it finds a routine due with no reminder sent.
 */
class RoutineAlarms : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tasks = DisinfectionTask.listFromJson(prefs.getString(KEY_TASKS, null))
        if (intent.action == ACTION_DUE) {
            tasks.firstOrNull { it.id == intent.data?.schemeSpecificPart }?.let {
                notify(context, it)
                prefs.edit().putInt(REMINDED + it.id, RoutineSchedule.epochDay(System.currentTimeMillis())).apply()
            }
        }
        schedule(context, tasks) // the next day's; after a restart, all of them
    }

    private fun notify(context: Context, task: DisinfectionTask) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Routine reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(
            task.id.hashCode(),
            builder.setSmallIcon(R.drawable.ic_tab_tasks)
                .setContentTitle("${task.name} is due")
                .setContentText("Open the app to start it.")
                .setContentIntent(RunService.openApp(context))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val PREFS = "reeman_remote"
        const val KEY_TASKS = "tasks"
        private const val ACTION_DUE = "com.expiation.reemanremote.ROUTINE_DUE"
        private const val CHANNEL = "routines"
        private const val REMINDED = "reminded_" // + routine id: the day its reminder last went up

        /** Sets the alarm of every routine with a time, and clears it from those without. */
        fun schedule(context: Context, tasks: List<DisinfectionTask>) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val now = System.currentTimeMillis()
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            tasks.forEach { t ->
                val at = RoutineSchedule.nextAt(t, now)
                when {
                    at == null -> alarms.cancel(pending(context, t.id))
                    exact -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, t.id))
                    else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, t.id))
                }
            }
        }

        fun cancel(context: Context, id: String) {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
            clearReminder(context, id)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(REMINDED + id).apply()
        }

        /** The day [id]'s reminder last went up, in [RoutineSchedule.epochDay]s; -1: never. */
        fun remindedOn(context: Context, id: String): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(REMINDED + id, -1)

        fun clearReminder(context: Context, id: String) {
            context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        }

        private fun pending(context: Context, id: String) = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, RoutineAlarms::class.java).setAction(ACTION_DUE).setData(Uri.fromParts("routine", id, null)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
