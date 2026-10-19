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

// ---------------------------------------------------------------------- Tasks tab: START DISINFECTION

/**
 * START DISINFECTION: the robot goes round every point on the map in turn (see
 * [DisinfectionTask.route]). It asks first. Why it can't start goes under it.
 */
@Composable
internal fun StartDisinfection(state: RemoteState, actions: RemoteActions) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val problem = state.routeProblem
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

// ---------------------------------------------------------------------- Tasks tab: the task under way

/**
 * The task under way: where it is, what the robot is doing at this stop, and END TASK. At a
 * stop the robot couldn't reach, TRY AGAIN and SKIP.
 */
@Composable
internal fun TaskRunCard(state: RemoteState, run: TaskRun, actions: RemoteActions) {
    val status = RemoteTheme.status
    val lampsMayBeOn = state.uv != null || state.lampsOffOwed
    Panel(color = if (lampsMayBeOn) status.dangerContainer else status.warnContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                run.task.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${run.number} of ${run.total}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        Text(
            run.stop.point + if (run.task.rounds > 1) " · round ${run.round} of ${run.task.rounds}" else "",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        val left = state.uvLeftMs?.let { DriveController.clockText(it) } ?: ""
        val stage = run.stage
        when {
            stage is TaskStage.Held -> {
                Text(stage.problem, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = status.warn)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
            stage == TaskStage.Disinfecting -> when (val uv = state.uv) {
                is Uv.Countdown -> {
                    Text("Lamps on in $left. Leave the room.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = status.danger)
                    Button(
                        onClick = actions.onStartDisinfectionNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("START NOW", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                }
                is Uv.Starting -> TaskActivity("Switching the lamps on …", status.danger)
                is Uv.On -> {
                    val total = (uv.until - uv.since).coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { 1f - (state.uvLeftMs ?: 0L).toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                        color = status.danger,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                    Text(
                        "UV ON · $left left of ${DriveController.clockText(total)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = status.danger,
                    )
                }
                null -> TaskActivity("Switching the lamps off …", status.danger)
            }
            stage == TaskStage.LampsOff -> TaskActivity("Switching the lamps off …", status.danger)
            stage == TaskStage.Travelling -> TaskActivity(
                if (state.undocking) "Leaving the charger" else "On the way",
                MaterialTheme.colorScheme.onSurface,
            )
            else -> TaskActivity("Getting ready …", MaterialTheme.colorScheme.onSurface)
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

@Composable
private fun TaskActivity(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
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
 * A new task ([original] null) or a saved one: its name, its points in order with the UV time
 * at each, the countdown, the rounds and what happens at the end. Points are the ones saved on
 * the robot's map (the Disinfection Points page adds them).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditorPage(
    state: RemoteState,
    actions: RemoteActions,
    original: DisinfectionTask?,
    drive: DriveSheetState,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val status = RemoteTheme.status
    val others = state.tasks.filter { it.id != original?.id }.map { it.name }
    var name by rememberSaveable {
        mutableStateOf(original?.name ?: generateSequence(1) { it + 1 }.map { "Task $it" }.first { it !in others })
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
        clean in others -> "A task with this name already exists."
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
                            if (original == null) "New task" else "Edit task",
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
        bottomBar = {
            Column {
                DriveSheet(state, actions, drive)
                StopBar(actions.onStop)
            }
        },
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
                    Text("Delete task", fontWeight = FontWeight.Bold)
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
