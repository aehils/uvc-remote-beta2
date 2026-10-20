package com.expiation.reemanremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.expiation.reemanremote.Points
import com.expiation.reemanremote.R
import com.expiation.reemanremote.RemoteState
import com.expiation.reemanremote.StepPhase
import com.expiation.reemanremote.StopOutcome
import com.expiation.reemanremote.StopResult
import com.expiation.reemanremote.TaskReport
import com.expiation.reemanremote.TaskRun
import com.expiation.reemanremote.TaskStage
import com.expiation.reemanremote.Uv
import com.expiation.reemanremote.Waypoint
import com.expiation.reemanremote.WhenDone
import java.util.Locale

// ---------------------------------------------------------------------- the Tasks tab

/**
 * What the Tasks tab's list has picked: points in the order they were tapped (the order the robot
 * visits them), and what the robot does after the last. Kept by [RemoteScreen], so it survives
 * switching tabs.
 */
data class TaskPlan(val points: List<String> = emptyList(), val whenDone: WhenDone = WhenDone.CHARGER) {
    fun toggle(point: String) = copy(points = if (point in points) points - point else points + point)
}

internal val TaskPlanSaver = listSaver<TaskPlan, String>(
    save = { listOf(it.whenDone.name) + it.points },
    restore = { TaskPlan(it.drop(1), WhenDone.valueOf(it[0])) },
)

/**
 * The Tasks tab, for whoever has to send the robot out to disinfect, top to bottom:
 *  - what the robot is doing now, in words, with the action that fits (disinfect here, go to
 *    the charger, undock, lamps off); or, while a task runs, that task and nothing else
 *  - saved routines, one tap to start
 *  - the robot's points: tap to pick the ones to disinfect (numbered in visiting order), or
 *    GO to send the robot to one without the lamps
 *  - the UV time, the start delay and what happens at the end, in plain view
 *  - START DISINFECTION, pinned over the nav bar, saying what it will do or why it can't
 */
@Composable
internal fun TasksTab(
    state: RemoteState,
    actions: RemoteActions,
    plan: TaskPlan,
    onPlanChange: (TaskPlan) -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(state.connected, state.points is Points.NotLoaded) {
        if (state.connected && state.points is Points.NotLoaded) actions.onLoadPoints()
    }
    val run = state.taskRun
    var confirming by rememberSaveable { mutableStateOf<String?>(null) } // a routine's id, or PLAN_ID
    val loaded = (state.points as? Points.Loaded)?.list
    // Points no longer on the map (another map in use) drop out of the plan, but stay picked.
    val picked = if (loaded == null) plan.points else plan.points.filter { p -> loaded.any { it.name == p } }
    val planTask = state.plan(picked, plan.whenDone)

    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (run != null) TaskRunCard(state, run, actions)
            else TaskPlanner(state, actions, plan, onPlanChange, loaded, picked, planTask, onStartRoutine = { confirming = it })
        }

        if (run == null) {
            StartBar(state, planTask, onStart = { confirming = DisinfectionTask.PLAN_ID })
        }
    }

    val task = when (val id = confirming) {
        null -> null
        DisinfectionTask.PLAN_ID -> planTask
        else -> state.tasks.firstOrNull { it.id == id }
    }
    if (task != null && run == null) {
        StartTaskDialog(
            task = task,
            onDismiss = { confirming = null },
            onStart = {
                confirming = null
                if (task.id == DisinfectionTask.PLAN_ID) actions.onStartPlan(task.stops.map { it.point }, task.whenDone)
                else actions.onStartTask(task.id)
            },
        )
    }
}

/** Everything before START: the last task's report, the robot, routines, the points and the settings. */
@Composable
private fun TaskPlanner(
    state: RemoteState,
    actions: RemoteActions,
    plan: TaskPlan,
    onPlanChange: (TaskPlan) -> Unit,
    loaded: List<Waypoint>?,
    picked: List<String>,
    planTask: DisinfectionTask,
    onStartRoutine: (String) -> Unit,
) {
    state.taskReport?.let { TaskReportCard(it, actions.onDismissTaskReport) }
    RobotCard(state, actions)

    if (state.tasks.isNotEmpty()) {
        SectionLabel("Routines")
        state.tasks.forEach { t ->
            RoutineRow(state, t, onStart = { onStartRoutine(t.id) }, onDelete = { actions.onDeleteTask(t.id) })
        }
    }

    PointsHeader(
        loaded = loaded,
        picked = picked,
        onSelectAll = { onPlanChange(plan.copy(points = loaded.orEmpty().map { it.name })) },
        onClear = { onPlanChange(plan.copy(points = emptyList())) },
    )
    PointList(state, actions, picked, onToggle = { onPlanChange(plan.toggle(it)) })

    SectionLabel("Settings")
    RunSettings(state, actions, plan, onPlanChange)

    if (picked.isNotEmpty()) SaveRoutineButton(state, planTask, actions.onSaveTask)
}

// ---------------------------------------------------------------------- what the robot is doing

private enum class Tone { GOOD, NEUTRAL, WARN, DANGER }

/** The robot's situation in a few plain words, and what it means for whoever holds the phone. */
private data class Situation(val title: String, val detail: String?, val tone: Tone, val working: Boolean = false)

private fun situation(state: RemoteState): Situation {
    val running = state.running
    val battery = state.battery
    val charging = running?.label?.startsWith("Go to charger") == true
    return when {
        !state.connected -> Situation("Not connected", "Check the phone is on the robot's Wi-Fi.", Tone.NEUTRAL)
        state.uv == null && state.lampsOffOwed -> Situation(
            "UV lamps may still be on",
            "Keep away until the robot confirms OFF.",
            Tone.DANGER,
            working = true,
        )
        state.estop == EStop.PRESSED -> Situation("Emergency stop pressed", "Release it on the robot to continue.", Tone.DANGER)
        state.mapping -> Situation("Making a map", "Finish it on the Map tab first.", Tone.WARN)
        state.undocking -> Situation("Leaving the charger", null, Tone.WARN, working = true)
        charging -> Situation(
            "Going to the charger",
            "Press Confirm on the robot's screen if asked.",
            Tone.WARN,
            working = true,
        )
        running?.goal != null -> Situation("Going to ${running.goal.name}", null, Tone.WARN, working = true)
        state.phase is StepPhase.Holding -> Situation("Driving by hand", null, Tone.WARN, working = true)
        state.phase is StepPhase.Stopping -> Situation("Stopping", null, Tone.WARN, working = true)
        running != null -> Situation("Moving", null, Tone.WARN, working = true)
        !state.still -> Situation("Moving", "Not by this app.", Tone.WARN)
        state.docked -> Situation("On the charger" + (battery?.let { " · $it%" } ?: ""), null, Tone.GOOD)
        battery != null && battery < DriveController.TASK_MIN_BATTERY -> Situation("Battery low · $battery%", "Charge before disinfecting.", Tone.WARN)
        state.atPoint != null -> Situation("Ready at ${state.atPoint.name}", null, Tone.GOOD)
        else -> Situation("Ready", null, Tone.GOOD)
    }
}

/**
 * The robot, in words, with the actions that fit: while idle, disinfect where it stands and the
 * charger (UNDOCK while on it); during a UV run started here, the countdown or time left, and
 * the lamps off.
 */
@Composable
private fun RobotCard(state: RemoteState, actions: RemoteActions) {
    val uv = state.uv
    if (uv != null) UvRunCard(state, uv, actions) else RobotStatusCard(state, actions)
}

@Composable
private fun RobotStatusCard(state: RemoteState, actions: RemoteActions) {
    val status = RemoteTheme.status
    val s = situation(state)
    var confirmingHere by rememberSaveable { mutableStateOf(false) }
    Panel(color = toneContainer(s.tone)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneDot(s.tone)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                s.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (s.working) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = toneInk(s.tone))
            }
        }
        // Only offered when the robot could act on them; otherwise the words above say why not.
        if (state.connected && state.estop != EStop.PRESSED && !state.mapping && !state.busy && state.still && !state.lampsOffOwed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.docked) OutlinedButton(
                    onClick = { confirmingHere = true },
                    enabled = state.canDisinfect,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = status.danger),
                ) { Text("Disinfect here", fontWeight = FontWeight.Bold) }
                FilledTonalButton(
                    onClick = if (state.docked) actions.onUndock else actions.onGoToCharger,
                    enabled = state.canNavigate,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_dock), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.docked) "Undock" else "Go to charger", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }

    if (confirmingHere) {
        val set = state.uvSettings
        AlertDialog(
            onDismissRequest = { confirmingHere = false },
            title = { Text("Disinfect " + (state.atPoint?.let { "at ${it.name}" } ?: "where the robot is") + "?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Lamps on in ${set.delaySeconds} s, for ${set.minutes} min.", style = MaterialTheme.typography.bodyMedium)
                    UvWarning("Keep everyone clear of the robot.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingHere = false
                        actions.onStartDisinfection()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = status.danger),
                ) { Text("START", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmingHere = false }) { Text("Cancel") } },
        )
    }
}

/** A UV run where the robot stands (Disinfect here): the countdown or the time left, and how to end it. */
@Composable
private fun UvRunCard(state: RemoteState, uv: Uv, actions: RemoteActions) {
    val status = RemoteTheme.status
    Panel(color = status.dangerContainer) {
        Text(
            uv.point?.let { "Disinfecting at $it" } ?: "Disinfecting where the robot is",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        UvProgress(state, uv)
        when (uv) {
            is Uv.Countdown -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = actions.onStopDisinfection,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("CANCEL", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                Button(
                    onClick = actions.onStartDisinfectionNow,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("START NOW", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
            }
            else -> RedButton("LAMPS OFF", actions.onStopDisinfection)
        }
    }
}

/**
 * The run's big read: "Lamps on in 0:12" with what to do meanwhile, or "3:12 left" over a bar
 * filling up. The same for a run started here and a task's.
 */
@Composable
private fun UvProgress(state: RemoteState, uv: Uv) {
    val status = RemoteTheme.status
    val left = state.uvLeftMs?.let { DriveController.clockText(it) } ?: ""
    when (uv) {
        is Uv.Countdown -> {
            BigRead("Lamps on in $left", status.danger)
            Text("Move away from the robot.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        is Uv.Starting -> BigRead("Switching the lamps on …", status.danger)
        is Uv.On -> {
            val total = (uv.until - uv.since).coerceAtLeast(1)
            BigRead("UV on · $left left", status.danger)
            LinearProgressIndicator(
                progress = { 1f - (state.uvLeftMs ?: 0L).toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = status.danger,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
private fun BigRead(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
}

// ---------------------------------------------------------------------- routines

/** A saved routine: its points in order and settings at a glance, and START. */
@Composable
private fun RoutineRow(state: RemoteState, task: DisinfectionTask, onStart: () -> Unit, onDelete: () -> Unit) {
    val status = RemoteTheme.status
    val fault = state.taskFault(task)
    var menu by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    task.stops.joinToString(" → ") { it.point },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    fault ?: routineSummary(task),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (fault != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (fault != null) status.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Text("⋮", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete routine") },
                        onClick = {
                            menu = false
                            deleting = true
                        },
                    )
                }
            }
            Button(
                onClick = onStart,
                enabled = fault == null && state.taskBlocker == null,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("START", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${task.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = status.danger),
                ) { Text("DELETE", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

private fun routineSummary(task: DisinfectionTask): String {
    val minutes = task.stops.map { it.minutes }.distinct()
    val each = if (minutes.size == 1) "${minutes[0]} min each" else "${task.uvMinutes / task.rounds} min in all"
    val rounds = if (task.rounds > 1) " · ${task.rounds} rounds" else ""
    return "$each$rounds · ${whenDoneText(task.whenDone)}"
}

private fun whenDoneText(w: WhenDone) = when (w) {
    WhenDone.CHARGER -> "then back to the charger"
    WhenDone.STAY -> "then stays at the last point"
}

/** Saves the points picked now, with the settings now, as a routine for next time. */
@Composable
private fun SaveRoutineButton(state: RemoteState, plan: DisinfectionTask, onSave: (DisinfectionTask) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Save these points as a routine", fontWeight = FontWeight.Bold)
    }
    if (naming) {
        val taken = state.tasks.map { it.name }.toSet()
        var name by rememberSaveable { mutableStateOf("") }
        val clean = name.trim()
        val problem = when {
            clean.isEmpty() -> null // nothing typed yet: no complaint, just no Save
            clean.length > DisinfectionTask.MAX_NAME -> "Use ${DisinfectionTask.MAX_NAME} characters or fewer."
            clean in taken -> "A routine with this name already exists."
            else -> null
        }
        val ok = clean.isNotEmpty() && problem == null
        val save = {
            naming = false
            onSave(DisinfectionTask("", clean, plan.stops, plan.delaySeconds, plan.rounds, plan.whenDone))
        }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save as a routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        plan.stops.joinToString(" → ") { it.point } + "\n" + routineSummary(plan),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = problem != null,
                        supportingText = problem?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (ok) save() }),
                    )
                }
            },
            confirmButton = { TextButton(onClick = save, enabled = ok) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------- the points

@Composable
private fun PointsHeader(loaded: List<Waypoint>?, picked: List<String>, onSelectAll: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel("Disinfection points") }
        when {
            loaded.isNullOrEmpty() -> Unit
            picked.size == loaded.size -> TextButton(onClick = onClear) { Text("Clear", fontWeight = FontWeight.Bold) }
            picked.isNotEmpty() -> {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onSelectAll) { Text("All", fontWeight = FontWeight.Bold) }
            }
            else -> TextButton(onClick = onSelectAll) { Text("Select all", fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * The robot's points, in one card. Tapping a row picks it (or drops it); its number is its place
 * in the visit order. GO sends the robot there with the lamps off. The last row saves where the
 * robot stands as a new point.
 */
@Composable
private fun PointList(state: RemoteState, actions: RemoteActions, picked: List<String>, onToggle: (String) -> Unit) {
    val points = state.points
    var adding by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            when {
                points is Points.Loaded && points.list.isNotEmpty() -> {
                    val goal = state.running?.goal
                    points.list.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PointRow(
                            point = p,
                            order = picked.indexOf(p.name).takeIf { it >= 0 }?.plus(1),
                            here = p == state.atPoint && !state.busy,
                            goingHere = p == goal,
                            canGo = state.canNavigate,
                            onToggle = { onToggle(p.name) },
                            onGo = { actions.onGoTo(p) },
                        )
                    }
                }
                points is Points.Loaded -> ListNote("No disinfection points on this map yet.")
                points is Points.Failed -> ListNote("Couldn't read the disinfection points: ${points.error}", retry = actions.onLoadPoints.takeIf { state.connected })
                points == Points.Loading -> Box(Modifier.padding(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> ListNote("Not connected.")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { adding = true },
                enabled = state.canAddPoint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add_point), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.addingPoint) "Saving …" else "Add a disinfection point here", fontWeight = FontWeight.Bold)
            }
            state.pointMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (adding) {
        val taken = (points as? Points.Loaded)?.list.orEmpty().map { it.name }.toSet() + "charging_pile" + DriveController.APPROACH_POINT
        AddPointDialog(
            taken = taken,
            onDismiss = { adding = false },
            onAdd = {
                adding = false
                actions.onAddPoint(it)
            },
        )
    }
}

@Composable
private fun PointRow(
    point: Waypoint,
    order: Int?,
    here: Boolean,
    goingHere: Boolean,
    canGo: Boolean,
    onToggle: () -> Unit,
    onGo: () -> Unit,
) {
    val status = RemoteTheme.status
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (order != null) colors.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClickLabel = if (order != null) "Don't disinfect ${point.name}" else "Disinfect ${point.name}", onClick = onToggle)
            .semantics {
                contentDescription = point.name + if (order != null) ", picked, number $order" else ", not picked"
            }
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Picked: a filled disc with its place in the order. Not picked: an empty ring.
        Box(
            Modifier
                .size(32.dp)
                .then(
                    if (order != null) Modifier.background(colors.primary, CircleShape)
                    else Modifier.border(2.dp, colors.outline, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (order != null) Text("$order", color = colors.onPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(point.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                goingHere -> Text("On the way", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = status.warn)
                here -> Text("Robot is here", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = status.good)
            }
        }
        if (!here && !goingHere) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onGo,
                enabled = canGo,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text("GO", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        }
    }
}

@Composable
private fun ListNote(text: String, retry: (() -> Unit)? = null) {
    Column(Modifier.padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retry?.let { TextButton(onClick = it) { Text("Try again", fontWeight = FontWeight.Bold) } }
    }
}

// ---------------------------------------------------------------------- the settings

/** Durations offered by the − / + buttons: every minute up to 5, then every 5. */
internal val UV_MINUTE_STEPS = (1..4) + (5..DriveController.MAX_UV_MINUTES step 5)
internal val UV_DELAYS = listOf(DriveController.MIN_UV_DELAY_S, 30, 60)

/** UV time and start delay (also used by Disinfect here, and saved on the phone), and the end of the run. */
@Composable
private fun RunSettings(state: RemoteState, actions: RemoteActions, plan: TaskPlan, onPlanChange: (TaskPlan) -> Unit) {
    val set = state.uvSettings
    Panel {
        SettingLabel("UV lamps on for")
        Stepper(
            text = "${set.minutes} min",
            onDown = { actions.onUvSettings(set.copy(minutes = UV_MINUTE_STEPS.last { it < set.minutes })) },
            downEnabled = set.minutes > UV_MINUTE_STEPS.first(),
            onUp = { actions.onUvSettings(set.copy(minutes = UV_MINUTE_STEPS.first { it > set.minutes })) },
            upEnabled = set.minutes < UV_MINUTE_STEPS.last(),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingLabel("Lamps on after arriving")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UV_DELAYS.forEach { d ->
                FilterChip(
                    selected = set.delaySeconds == d,
                    onClick = { actions.onUvSettings(set.copy(delaySeconds = d)) },
                    label = { Text("$d s") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingLabel("After the last point")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = plan.whenDone == WhenDone.CHARGER,
                onClick = { onPlanChange(plan.copy(whenDone = WhenDone.CHARGER)) },
                label = { Text("Back to charger") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = plan.whenDone == WhenDone.STAY,
                onClick = { onPlanChange(plan.copy(whenDone = WhenDone.STAY)) },
                label = { Text("Stay there") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Stepper(text: String, onDown: () -> Unit, downEnabled: Boolean, onUp: () -> Unit, upEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onDown, enabled = downEnabled, modifier = Modifier.size(56.dp, 48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−", fontSize = 22.sp)
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onUp, enabled = upEnabled, modifier = Modifier.size(56.dp, 48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+", fontSize = 22.sp)
        }
    }
}

// ---------------------------------------------------------------------- START

/** START DISINFECTION, over the nav bar so it never scrolls away, with what it will do or why it can't. */
@Composable
private fun StartBar(state: RemoteState, plan: DisinfectionTask, onStart: () -> Unit) {
    val status = RemoteTheme.status
    val count = plan.stops.size
    val problem = state.taskBlocker ?: if (count == 0) "Pick a disinfection point." else state.taskFault(plan)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            HorizontalDivider(Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                problem ?: "${pointsText(count)} · ${plan.uvMinutes} min UV",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (problem != null) MaterialTheme.colorScheme.onSurfaceVariant else status.good,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onStart,
                enabled = problem == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    "START DISINFECTION",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

private fun pointsText(n: Int) = if (n == 1) "1 point" else "$n points"

/** Asks first: where the robot will go, for how long, and the UV warning. */
@Composable
private fun StartTaskDialog(task: DisinfectionTask, onDismiss: () -> Unit, onStart: () -> Unit) {
    val status = RemoteTheme.status
    val minutes = task.stops.map { it.minutes }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task.id == DisinfectionTask.PLAN_ID) "Start disinfection?" else "Start ${task.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    task.stops.forEachIndexed { i, s ->
                        Text(
                            "${i + 1}.  ${s.point}" + if (minutes.size > 1) "  ·  ${s.minutes} min" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    "Lamps on ${task.delaySeconds} s after arriving" +
                        (if (minutes.size == 1) ", for ${minutes[0]} min" else "") +
                        (if (task.rounds > 1) ". ${task.rounds} rounds" else "") +
                        ". " + whenDoneText(task.whenDone).replaceFirstChar { it.uppercase() } + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UvWarning("Keep everyone clear of the robot and its points.")
            }
        },
        confirmButton = {
            TextButton(onClick = onStart, colors = ButtonDefaults.textButtonColors(contentColor = status.danger)) {
                Text("START", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UvWarning(what: String) {
    Text(
        "UV-C light burns skin and eyes. $what",
        modifier = Modifier
            .fillMaxWidth()
            .background(RemoteTheme.status.dangerContainer, RoundedCornerShape(10.dp))
            .padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = RemoteTheme.status.danger,
    )
}

// ---------------------------------------------------------------------- the task under way

/**
 * The task under way is the whole tab: what it is doing now in big type, every point with how
 * it went, and END TASK. At a point it couldn't reach, TRY AGAIN and SKIP (the drive pad comes
 * up below, to free the robot).
 */
@Composable
internal fun TaskRunCard(state: RemoteState, run: TaskRun, actions: RemoteActions) {
    val status = RemoteTheme.status
    val stage = run.stage
    val uv = state.uv
    val lampsMayBeOn = uv != null || state.lampsOffOwed
    Panel(color = if (lampsMayBeOn) status.dangerContainer else if (stage is TaskStage.Held) status.warnContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                run.task.name.uppercase(Locale.US),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Point ${run.number} of ${run.total}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            stage is TaskStage.Held -> {
                BigRead(stage.problem, status.warn)
                Text("Clear the way or drive it free, then try again.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = actions.onSkipTaskStop,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("SKIP", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                    Button(
                        onClick = actions.onRetryTaskStop,
                        enabled = state.canTravel,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("TRY AGAIN", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                }
            }
            stage == TaskStage.Disinfecting && uv != null -> {
                Text("At ${run.stop.point}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                UvProgress(state, uv)
                if (uv is Uv.Countdown) {
                    Button(
                        onClick = actions.onStartDisinfectionNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("START NOW", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                }
            }
            stage == TaskStage.Disinfecting || stage == TaskStage.LampsOff -> {
                BigRead("Switching the lamps off …", status.danger)
            }
            stage == TaskStage.Travelling -> {
                BigRead(if (state.undocking) "Leaving the charger" else "Going to ${run.stop.point}", MaterialTheme.colorScheme.onSurface)
            }
            else -> BigRead("Getting ready …", MaterialTheme.colorScheme.onSurface)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (run.task.rounds > 1) SectionLabel("Round ${run.round} of ${run.task.rounds}")
        val thisRound = run.results.filter { it.round == run.round }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            run.task.stops.forEachIndexed { i, stop ->
                val result = thisRound.getOrNull(i)
                StopLine(
                    number = i + 1,
                    point = stop.point,
                    mark = when {
                        result != null -> markOf(result)
                        i == run.index -> Mark.NOW
                        else -> Mark.NEXT
                    },
                    detail = when {
                        result != null -> resultText(result)
                        i == run.index -> "now"
                        else -> "${stop.minutes} min"
                    },
                )
            }
        }
        RedButton("END TASK", actions.onEndTask)
    }
}

// ---------------------------------------------------------------------- how the last one went

/** How the last task ended, point by point, until dismissed. "Disinfected" means the planned time ran, nothing more. */
@Composable
internal fun TaskReportCard(report: TaskReport, onDismiss: () -> Unit) {
    val status = RemoteTheme.status
    val good = report.completed && report.done == report.total
    Panel(color = if (good) status.goodContainer else status.warnContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${report.task.name} ${if (report.completed) "finished" else "ended early"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${report.done} of ${report.total} disinfected" + (report.reason?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (good) status.good else status.warn,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss", modifier = Modifier.size(20.dp))
            }
        }
        val rounds = report.task.rounds > 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            report.results.forEachIndexed { i, r ->
                StopLine(
                    number = i + 1,
                    point = (if (rounds) "R${r.round}  " else "") + r.point,
                    mark = markOf(r),
                    detail = resultText(r),
                )
            }
        }
    }
}

private enum class Mark { DONE, NOW, NEXT, MISSED }

private fun markOf(r: StopResult) = if (r.outcome == StopOutcome.DONE) Mark.DONE else Mark.MISSED

private fun resultText(r: StopResult) = when (r.outcome) {
    StopOutcome.DONE -> "done · ${DriveController.clockText(r.uvMs)}"
    StopOutcome.INTERRUPTED -> "stopped at ${DriveController.clockText(r.uvMs)}"
    StopOutcome.SKIPPED -> "skipped"
    StopOutcome.NOT_REACHED -> "not reached"
}

/** One point of a task: a marker (tick, now, number to come, or a miss), its name, and how it went. */
@Composable
private fun StopLine(number: Int, point: String, mark: Mark, detail: String) {
    val status = RemoteTheme.status
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .then(
                    when (mark) {
                        Mark.DONE -> Modifier.background(status.good, CircleShape)
                        Mark.NOW -> Modifier.background(colors.primary, CircleShape)
                        Mark.NEXT -> Modifier.border(2.dp, colors.outline, CircleShape)
                        Mark.MISSED -> Modifier.background(status.warn, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (mark) {
                Mark.DONE -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Mark.MISSED -> Text("!", color = Color.White, fontWeight = FontWeight.Black)
                Mark.NOW -> Text("$number", color = colors.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Mark.NEXT -> Text("$number", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            point,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (mark == Mark.NOW) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mark == Mark.DONE) FontFamily.Monospace else null,
            fontWeight = if (mark == Mark.NOW) FontWeight.Bold else FontWeight.Normal,
            color = when (mark) {
                Mark.DONE -> status.good
                Mark.MISSED -> status.warn
                Mark.NOW -> colors.primary
                Mark.NEXT -> colors.onSurfaceVariant
            },
        )
    }
}

// ---------------------------------------------------------------------- bits

@Composable
private fun RedButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RemoteTheme.status.stop, contentColor = Color.White),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun toneContainer(t: Tone): Color {
    val status = RemoteTheme.status
    return when (t) {
        Tone.GOOD -> status.goodContainer
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surface
        Tone.WARN -> status.warnContainer
        Tone.DANGER -> status.dangerContainer
    }
}

@Composable
private fun toneInk(t: Tone): Color {
    val status = RemoteTheme.status
    return when (t) {
        Tone.GOOD -> status.good
        Tone.NEUTRAL -> MaterialTheme.colorScheme.outline
        Tone.WARN -> status.warn
        Tone.DANGER -> status.danger
    }
}

@Composable
private fun ToneDot(t: Tone) {
    Box(
        Modifier
            .size(14.dp)
            .background(toneInk(t), CircleShape)
    )
}
