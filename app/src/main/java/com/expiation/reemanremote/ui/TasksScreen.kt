package com.expiation.reemanremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expiation.reemanremote.DisinfectionTask
import com.expiation.reemanremote.DriveController
import com.expiation.reemanremote.EStop
import com.expiation.reemanremote.Link
import com.expiation.reemanremote.Points
import com.expiation.reemanremote.R
import com.expiation.reemanremote.RemoteState
import com.expiation.reemanremote.StopOutcome
import com.expiation.reemanremote.TaskReport
import com.expiation.reemanremote.TaskRun
import com.expiation.reemanremote.TaskStage
import com.expiation.reemanremote.TaskStop
import com.expiation.reemanremote.Uv
import com.expiation.reemanremote.WhenDone

// ---------------------------------------------------------------------- Tasks tab: Right Now

/** How Right Now is coloured: the card's fill and the colour of its label and headline. */
private enum class Tone { READY, BUSY, DANGER, OFFLINE }

private data class Now(val tone: Tone, val headline: String, val detail: String?)

/**
 * What the robot is doing, in a few words, most urgent first: not connected, the e-stop, the UV
 * lamps (they may burn), a task, mapping, a trip or a move, then at rest (charging, or ready).
 */
private fun rightNow(state: RemoteState): Now {
    val left = state.uvLeftMs?.let { DriveController.clockText(it) } ?: ""
    val run = state.taskRun
    val uv = state.uv
    val step = state.running
    val goal = step?.goal
    val battery = state.battery
    val link = state.link
    return when {
        !state.connected -> Now(
            Tone.OFFLINE,
            when {
                state.searching -> "Looking for the robot"
                link is Link.Disconnected && link.lastError != null -> "Not connected"
                else -> "Connecting …"
            },
            "Tap to find the robot in Settings.",
        )
        state.estop == EStop.PRESSED -> Now(Tone.DANGER, "E-stop pressed", "Release the e-stop on the robot to drive or disinfect.")
        uv is Uv.Countdown -> Now(
            Tone.DANGER,
            "UV lamps on in $left",
            (uv.point?.let { "At $it. " } ?: "") + "Leave the room and close the door.",
        )
        uv is Uv.Starting -> Now(Tone.DANGER, "UV lamps switching on", "Stay out of the room.")
        uv is Uv.On -> Now(Tone.DANGER, uv.point?.let { "Disinfecting $it" } ?: "Disinfecting", "UV lamps on · $left left")
        state.lampsOffOwed -> Now(Tone.DANGER, "UV lamps may still be on", "The robot hasn't accepted OFF yet. The app keeps sending it.")
        run != null -> {
            val where = "${run.task.name} · point ${run.number} of ${run.total}"
            when (val stage = run.stage) {
                is TaskStage.Held -> Now(Tone.BUSY, "Stopped before ${run.stop.point}", stage.problem)
                TaskStage.Travelling -> Now(Tone.BUSY, if (state.undocking) "Leaving the charger" else "On the way to ${run.stop.point}", where)
                TaskStage.LampsOff, TaskStage.Disinfecting -> Now(Tone.BUSY, "Switching the lamps off", where)
                else -> Now(Tone.BUSY, "Getting ready", where)
            }
        }
        state.mapping -> Now(Tone.BUSY, "Mapping", "Drive it round on the Map page, then save the map.")
        state.undocking -> Now(Tone.BUSY, "Leaving the charger", null)
        step?.label?.startsWith("Go to charger") == true -> Now(Tone.BUSY, "Going to the charger", "It docks by itself when it gets there.")
        goal != null -> Now(Tone.BUSY, "On the way to ${goal.name}", null)
        step != null -> Now(Tone.BUSY, "Moving", step.label)
        !state.still -> Now(Tone.BUSY, "Moving", "Not on a command from this app.")
        state.docked -> Now(Tone.READY, "Charging at the dock", "Ready. Start a disinfection, or undock to drive.")
        battery != null && battery < DriveController.TASK_MIN_BATTERY -> Now(
            Tone.BUSY, "Battery low", "Send it to the charger: a disinfection needs ${DriveController.TASK_MIN_BATTERY}%.",
        )
        else -> Now(Tone.READY, state.atPoint?.let { "Ready at ${it.name}" } ?: "Ready", "Start a disinfection or a routine below.")
    }
}

/**
 * Right Now, pinned at the top of the Tasks tab: a label with the battery beside it, what the
 * robot is doing, and a line on what that means. Green at rest, amber while it works, red for
 * the e-stop and the UV lamps; orange words while it isn't connected, and then it opens Settings.
 */
@Composable
internal fun RightNowCard(state: RemoteState) {
    val status = RemoteTheme.status
    val now = rightNow(state)
    val (fill, accent) = when (now.tone) {
        Tone.READY -> status.goodContainer to status.goodText
        Tone.BUSY -> status.warnContainer to status.warn
        Tone.DANGER -> status.dangerContainer to status.danger
        Tone.OFFLINE -> MaterialTheme.colorScheme.surface to status.offlineText
    }
    val open = LocalOpenSettings.current
    Panel(
        modifier = if (now.tone == Tone.OFFLINE && open != null) {
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "Open the robot's settings", onClick = open)
        } else Modifier,
        color = fill,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RIGHT NOW",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = accent,
            )
            // Stale while not connected, so it fades.
            Box(Modifier.alpha(if (state.connected) 1f else 0.45f)) { BatteryIcon(state.battery) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                now.headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            now.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ---------------------------------------------------------------------- Tasks tab: START DISINFECTION

/**
 * START DISINFECTION: the robot goes round every point on the map in turn (see
 * [DisinfectionTask.route]), then to the charger. It asks first. Why it can't start goes under it.
 */
@Composable
internal fun StartDisinfection(state: RemoteState, actions: RemoteActions) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val problem = state.routeProblem
    val points = state.route.stops.size
    Panel {
        Column {
            Text("All points", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (points > 0) "$points point${if (points == 1) "" else "s"} · ${state.uvSettings.minutes} min UV each · then the charger"
                else "Every point on the map, then the charger",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { confirming = true },
            enabled = problem == null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("START DISINFECTION", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        problem?.let { TasksNote(it) }
    }

    if (confirming) {
        val route = state.route
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Start disinfection?") },
            text = {
                Text(
                    "UV-C light burns skin and eyes. Make sure nobody is in the rooms on the route and the doors are closed.\n\n" +
                        "The robot goes to all ${route.stops.size} points in turn. At each, the lamps come on " +
                        "${route.delaySeconds} s after it arrives and stay on for ${state.uvSettings.minutes} min. " +
                        "STOP, the e-stop, or leaving the app ends it and switches them off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onStartRoute()
                }) { Text("START", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------- Tasks tab: routines

/** "3 points · 2 rounds · 30 min UV" */
private fun taskSummary(task: DisinfectionTask): String =
    listOfNotNull(
        "${task.stops.size} point${if (task.stops.size == 1) "" else "s"}",
        if (task.rounds > 1) "${task.rounds} rounds" else null,
        "${task.uvMinutes} min UV",
    ).joinToString(" · ")

/**
 * The saved routines (the robot's own app calls them fixed-point tasks), each with START (it
 * asks first), and New routine. Tapping one opens the editor. Why none can start goes once under
 * the list; what is wrong with one, on its row.
 */
@Composable
internal fun SavedTasks(state: RemoteState, actions: RemoteActions, onEditTask: (String) -> Unit) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    if (state.tasks.isEmpty()) TasksNote("A routine is your own list of points, each with its UV time, run in order.")
    state.tasks.forEach { task ->
        TaskRow(
            task = task,
            fault = state.taskFault(task),
            canStart = state.taskProblem(task) == null,
            onStart = { confirming = task.id },
            onEdit = { onEditTask(task.id) },
        )
    }
    if (state.tasks.isNotEmpty() && state.taskRun == null) state.taskBlocker?.let { TasksNote(it) }
    OutlinedButton(
        onClick = { onEditTask("") },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("+  New routine", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }

    val task = state.tasks.firstOrNull { it.id == confirming }
    if (task != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Start ${task.name}?") },
            text = {
                Text(
                    "UV-C light burns skin and eyes. Make sure nobody is in the rooms on the route and the doors are closed.\n\n" +
                        "At each point the lamps come on ${task.delaySeconds} s after the robot arrives. " +
                        "STOP, the e-stop, or leaving the app ends it and switches them off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    actions.onStartTask(task.id)
                }) { Text("START", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TaskRow(task: DisinfectionTask, fault: String?, canStart: Boolean, onStart: () -> Unit, onEdit: () -> Unit) {
    val status = RemoteTheme.status
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Edit ${task.name}", onClick = onEdit)
            ) {
                Text(task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(taskSummary(task), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                fault?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = status.warn) }
            }
            IconButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Edit ${task.name}", modifier = Modifier.size(22.dp))
            }
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("START", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------- Tasks tab: the task under way

/**
 * The task under way: its name and how far along it is, the UV time at this stop, and its
 * controls: START NOW in a countdown, TRY AGAIN and SKIP at a stop the robot couldn't reach,
 * and END TASK. What the robot is doing is Right Now's, above, so it isn't said again here.
 */
@Composable
internal fun TaskRunCard(state: RemoteState, run: TaskRun, actions: RemoteActions) {
    val status = RemoteTheme.status
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    run.task.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    run.stop.point + if (run.task.rounds > 1) " · round ${run.round} of ${run.task.rounds}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${run.number} of ${run.total}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        // How far along the whole task is, a step per stop.
        LinearProgressIndicator(
            progress = { (run.number - 1).toFloat() / run.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        val stage = run.stage
        when {
            stage is TaskStage.Held -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = actions.onSkipTaskStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("SKIP", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                Button(
                    onClick = actions.onRetryTaskStop,
                    enabled = state.canTravel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("TRY AGAIN", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
            }
            stage == TaskStage.Disinfecting -> when (val uv = state.uv) {
                // Skips the rest of the countdown, for when the room is already clear.
                is Uv.Countdown -> Button(
                    onClick = actions.onStartDisinfectionNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("START NOW", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                is Uv.On -> {
                    val total = (uv.until - uv.since).coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { 1f - (state.uvLeftMs ?: 0L).toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                        color = status.danger,
                        trackColor = status.dangerContainer,
                    )
                }
                else -> {}
            }
            else -> {}
        }

        Button(
            onClick = actions.onEndTask,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = status.stop, contentColor = Color.White),
        ) {
            Text("END TASK", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
    }
}

// ---------------------------------------------------------------------- Tasks tab: how the last one ended

/** How the last task ended, stop by stop, until dismissed. "Disinfected" means the planned time ran, nothing more. */
@Composable
internal fun TaskReportCard(report: TaskReport, onDismiss: () -> Unit) {
    val status = RemoteTheme.status
    val good = report.completed && report.done == report.total
    Panel(color = if (good) status.goodContainer else status.warnContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${report.task.name} ${if (report.completed) "finished" else "ended"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${report.done} of ${report.total} disinfected" + (report.reason?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (good) status.good else status.warn,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss", modifier = Modifier.size(20.dp))
            }
        }
        val rounds = report.task.rounds > 1
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            report.results.forEach { r ->
                Row {
                    Text(
                        (if (rounds) "R${r.round}  " else "") + r.point,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        when (r.outcome) {
                            StopOutcome.DONE -> DriveController.clockText(r.uvMs)
                            StopOutcome.INTERRUPTED -> "stopped at ${DriveController.clockText(r.uvMs)}"
                            StopOutcome.SKIPPED -> "skipped"
                            StopOutcome.NOT_REACHED -> "not reached"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (r.outcome == StopOutcome.DONE) status.good else status.warn,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- the task editor

/**
 * A new routine ([original] null) or a saved one: its name, its points in order with the UV time
 * at each, the countdown, the rounds and what happens at the end. Points are the ones saved on
 * the robot's map (the Disinfection Points page adds them).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditorPage(
    state: RemoteState,
    actions: RemoteActions,
    original: DisinfectionTask?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val status = RemoteTheme.status
    val others = state.tasks.filter { it.id != original?.id }.map { it.name }
    var name by rememberSaveable {
        mutableStateOf(original?.name ?: generateSequence(1) { it + 1 }.map { "Routine $it" }.first { it !in others })
    }
    var stops by rememberSaveable(stateSaver = StopsSaver) { mutableStateOf(original?.stops.orEmpty()) }
    var delay by rememberSaveable { mutableStateOf(original?.delaySeconds ?: state.uvSettings.delaySeconds) }
    var rounds by rememberSaveable { mutableStateOf(original?.rounds ?: 1) }
    var whenDone by rememberSaveable { mutableStateOf(original?.whenDone ?: WhenDone.CHARGER) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    val points = state.points
    val loaded = (points as? Points.Loaded)?.list
    val clean = name.trim()
    val nameProblem = when {
        clean.isEmpty() -> "Enter a name."
        clean.length > DisinfectionTask.MAX_NAME -> "Use ${DisinfectionTask.MAX_NAME} characters or fewer."
        clean in others -> "A routine with this name already exists."
        else -> null
    }
    val canSave = nameProblem == null && stops.isNotEmpty()
    val running = original != null && state.taskRun?.task?.id == original.id

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                    title = {
                        Text(
                            if (original == null) "New routine" else "Edit routine",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                actions.onSaveTask(DisinfectionTask(original?.id ?: "", clean, stops, delay, rounds, whenDone))
                                onBack()
                            },
                            enabled = canSave,
                        ) { Text("Save", fontWeight = FontWeight.Bold) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                StatusHeader(state, actions.onDismissAlert)
            }
        },
        bottomBar = { StopBar(actions.onStop) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = nameProblem != null,
                supportingText = nameProblem?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Points, in order")
            stops.forEachIndexed { i, stop ->
                StopRow(
                    number = i + 1,
                    stop = stop,
                    missing = loaded != null && loaded.none { it.name == stop.point },
                    onUp = if (i > 0) {
                        { stops = stops.toMutableList().also { it.add(i - 1, it.removeAt(i)) } }
                    } else null,
                    onRemove = { stops = stops.toMutableList().also { it.removeAt(i) } },
                    onMinutes = { m -> stops = stops.toMutableList().also { it[i] = stop.copy(minutes = m) } },
                )
            }

            SectionLabel("Add a point")
            when {
                loaded != null && loaded.isNotEmpty() -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    loaded.forEach { p ->
                        AssistChip(
                            onClick = { stops = stops + TaskStop(p.name, state.uvSettings.minutes) },
                            enabled = stops.size < DisinfectionTask.MAX_STOPS,
                            label = { Text("+ ${p.name}") },
                        )
                    }
                }
                loaded != null -> TasksNote("No points are saved on this robot's map yet.")
                points is Points.Failed -> TasksNote("Couldn't read the points: ${points.error}")
                !state.connected -> TasksNote("Connect to the robot to see its points.")
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            SectionLabel("Lamps come on after")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UV_DELAYS.forEach { d -> FilterChip(selected = delay == d, onClick = { delay = d }, label = { Text("$d s") }) }
            }

            SectionLabel("Rounds")
            Stepper(
                text = "$rounds",
                onDown = { rounds-- },
                downEnabled = rounds > 1,
                onUp = { rounds++ },
                upEnabled = rounds < DisinfectionTask.MAX_ROUNDS,
            )

            SectionLabel("When done")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhenDone.entries.forEach { w -> FilterChip(selected = whenDone == w, onClick = { whenDone = w }, label = { Text(w.label) }) }
            }

            if (original != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { deleting = true },
                    enabled = !running,
                    colors = ButtonDefaults.textButtonColors(contentColor = status.danger),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete routine", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (deleting && original != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${original.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        actions.onDeleteTask(original.id)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = status.danger),
                ) { Text("DELETE", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** Keeps the editor's unsaved stops across a dark-mode switch (the activity is recreated). */
private val StopsSaver = listSaver<List<TaskStop>, Any>(
    save = { list -> list.flatMap { listOf(it.point, it.minutes) } },
    restore = { flat -> flat.chunked(2).map { (point, minutes) -> TaskStop(point as String, minutes as Int) } },
)

/** One stop in the editor: its place, the point (flagged if the map lacks it), and its UV time. */
@Composable
private fun StopRow(
    number: Int,
    stop: TaskStop,
    missing: Boolean,
    onUp: (() -> Unit)?,
    onRemove: () -> Unit,
    onMinutes: (Int) -> Unit,
) {
    val status = RemoteTheme.status
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stop.point, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (missing) Text("Not on this map", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = status.warn)
            }
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) {
                Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove ${stop.point}", modifier = Modifier.size(20.dp))
            }
        }
        Stepper(
            text = "${stop.minutes} min UV",
            onDown = { onMinutes(UV_MINUTE_STEPS.last { it < stop.minutes }) },
            downEnabled = stop.minutes > UV_MINUTE_STEPS.first(),
            onUp = { onMinutes(UV_MINUTE_STEPS.first { it > stop.minutes }) },
            upEnabled = stop.minutes < UV_MINUTE_STEPS.last(),
        )
    }
}

@Composable
private fun Stepper(text: String, onDown: () -> Unit, downEnabled: Boolean, onUp: () -> Unit, upEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onDown, enabled = downEnabled, shape = RoundedCornerShape(10.dp)) { Text("−", fontSize = 20.sp) }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onUp, enabled = upEnabled, shape = RoundedCornerShape(10.dp)) { Text("+", fontSize = 20.sp) }
    }
}
