package com.expiation.reemanremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expiation.reemanremote.DemoStatus
import com.expiation.reemanremote.DisinfectionTask
import com.expiation.reemanremote.DriveController
import com.expiation.reemanremote.EStop
import com.expiation.reemanremote.FoundRobot
import com.expiation.reemanremote.Link
import com.expiation.reemanremote.Move
import com.expiation.reemanremote.Points
import com.expiation.reemanremote.Pose
import com.expiation.reemanremote.R
import com.expiation.reemanremote.RemoteState
import com.expiation.reemanremote.RobotMode
import com.expiation.reemanremote.RobotName
import com.expiation.reemanremote.SavedMap
import com.expiation.reemanremote.Step
import com.expiation.reemanremote.TaskRun
import com.expiation.reemanremote.TaskStage
import com.expiation.reemanremote.TaskStop
import com.expiation.reemanremote.StepPhase
import com.expiation.reemanremote.Tracking
import com.expiation.reemanremote.Uv
import com.expiation.reemanremote.Wall
import com.expiation.reemanremote.UvSettings
import com.expiation.reemanremote.Waypoint
import java.util.Locale

/** Everything the screen can ask for. */
data class RemoteActions(
    val onDemoChange: (Boolean) -> Unit = {},
    val onPing: (String) -> Unit = {},
    val onFindRobot: () -> Unit = {},
    val onPickRobot: (FoundRobot) -> Unit = {},
    val onMove: (Move) -> Unit = {},
    val onHold: (Move) -> Unit = {},
    val onHoldAlive: (Move) -> Unit = {},
    val onRelease: (Move) -> Unit = {},
    val onStop: () -> Unit = {},
    val onLoadPoints: () -> Unit = {},
    val onGoTo: (Waypoint) -> Unit = {},
    val onAddPoint: (String) -> Unit = {},
    val onGoToCharger: () -> Unit = {},
    val onUndock: () -> Unit = {},
    val onStartDisinfection: () -> Unit = {},
    val onStartDisinfectionNow: () -> Unit = {},
    val onStopDisinfection: () -> Unit = {},
    val onSwitchLampsOn: () -> Unit = {},
    val onDismissAlert: () -> Unit = {},
    val onUvSettings: (UvSettings) -> Unit = {},
    val onDemoEstop: () -> Unit = {},
    val onDemoWifi: () -> Unit = {},
    val onDemoBlock: () -> Unit = {},
    val onDemoPerson: () -> Unit = {},
    val onDemoChargeOnItsOwn: () -> Unit = {},
    val onDemoBattery: () -> Unit = {},
    val onDemoReset: () -> Unit = {},
    val onMapVisible: (Boolean) -> Unit = {},
    val onLoadMaps: () -> Unit = {},
    val onStartMapping: (extend: Boolean) -> Unit = {},
    val onSaveMap: (String) -> Unit = {},
    val onDiscardMapping: () -> Unit = {},
    val onUseMap: (SavedMap) -> Unit = {},
    val onRenameMap: (SavedMap, String) -> Unit = { _, _ -> },
    val onDeleteMap: (SavedMap) -> Unit = {},
    val onRestoreMap: () -> Unit = {},
    val onSetPosition: (Pose) -> Unit = {},
    val onAddChargingPile: () -> Unit = {},
    val onAddWall: (Wall) -> Unit = {},
    val onRemoveWall: (Wall) -> Unit = {},
    val onSaveTask: (DisinfectionTask) -> Unit = {},
    val onDeleteTask: (String) -> Unit = {},
    val onStartTask: (String) -> Unit = {},
    val onRetryTaskStop: () -> Unit = {},
    val onSkipTaskStop: () -> Unit = {},
    val onEndTask: () -> Unit = {},
    val onDismissTaskReport: () -> Unit = {},
)

@Composable
fun RemoteScreen(state: RemoteState, actions: RemoteActions) {
    // Kept here, not in MainPage, so coming back from another page returns to the same tab.
    // Tasks first: it is the one-stop page for the staff running the robot.
    var tab by rememberSaveable { mutableStateOf(Tab.TASKS) }
    // The Map page (the map and the drive pad) is a mode over the tabs, not a tab: leaving it
    // goes back to the tab it was opened from.
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    // The task the editor is open on: its id, or "" for a new one.
    var editingTask by rememberSaveable { mutableStateOf("") }
    // The drive sheet stays open or closed across tabs and pages.
    var driveOpen by rememberSaveable { mutableStateOf(false) }
    // The controls page only exists in DEMO: leaving DEMO closes it.
    LaunchedEffect(state.demo) { if (!state.demo && page == Page.DEMO_CONTROLS) page = Page.MAIN }
    // Mapping is driving: the pad comes up by itself.
    LaunchedEffect(state.mapping) { if (state.mapping) driveOpen = true }

    val drive = DriveSheetState(driveOpen) { driveOpen = it }
    val demoStatus = state.demoStatus
    // The connection lives in Settings: the status line, on any page, goes there.
    val openSettings: () -> Unit = remember {
        {
            page = Page.MAIN
            mapOpen = false
            tab = Tab.SETTINGS
        }
    }
    CompositionLocalProvider(LocalOpenSettings provides openSettings) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            StatusLine(state)
            AnimatedVisibility(state.demo) { DemoStrip() }
            Box(Modifier.weight(1f)) {
                when {
                    page == Page.DEMO_CONTROLS && demoStatus != null ->
                        DemoControlsPage(state, demoStatus, actions, drive, onBack = { page = Page.MAIN })
                    page == Page.POINTS -> PointsPage(state, actions, drive, onBack = { page = Page.MAIN })
                    page == Page.ACTIVITY -> SubPage("Activity log", state, actions, drive, onBack = { page = Page.MAIN }) {
                        ActivityLog(state.log, it)
                    }
                    page == Page.ROBOT_INFO -> SubPage("Robot information", state, actions, drive, onBack = { page = Page.MAIN }) {
                        RobotInfo(state, it)
                    }
                    page == Page.TASK_EDIT -> TaskEditorPage(
                        state, actions,
                        original = state.tasks.firstOrNull { it.id == editingTask },
                        drive = drive,
                        onBack = { page = Page.MAIN },
                    )
                    page == Page.MAPS -> MapsPage(
                        state, actions, drive,
                        onBack = { page = Page.MAIN },
                        onShowMap = {
                            page = Page.MAIN
                            mapOpen = true
                        },
                    )
                    else -> MainPage(
                        state, actions, tab, mapOpen, drive,
                        onTabChange = { tab = it },
                        onMapOpenChange = { mapOpen = it },
                        onOpenDemoControls = { page = Page.DEMO_CONTROLS },
                        onOpenPoints = { page = Page.POINTS },
                        onOpenMaps = { page = Page.MAPS },
                        onOpenActivity = { page = Page.ACTIVITY },
                        onOpenRobotInfo = { page = Page.ROBOT_INFO },
                        onEditTask = {
                            editingTask = it
                            page = Page.TASK_EDIT
                        },
                    )
                }
            }
        }
    }
}

/** Opens Settings, where the connection is: for the status line at the top of every page. */
private val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Pages over the main (tabbed) page. Each keeps STOP, the drive sheet and the robot's readings on screen. */
private enum class Page { MAIN, DEMO_CONTROLS, POINTS, MAPS, TASK_EDIT, ACTIVITY, ROBOT_INFO }

/** Whether the drive sheet is pulled up, and how to change that. */
internal class DriveSheetState(val open: Boolean, val onOpenChange: (Boolean) -> Unit)

/** The tabs, each with its outline icon and the filled one it takes while it is open. */
private enum class Tab(val label: String, val icon: Int, val selectedIcon: Int) {
    TASKS("Tasks", R.drawable.ic_tab_tasks, R.drawable.ic_tab_tasks_filled),
    SETTINGS("Settings", R.drawable.ic_tab_settings, R.drawable.ic_tab_settings_filled),
}

/**
 * Layout, top to bottom: DEMO strip (from [RemoteScreen]), title bar, the robot's readings (pinned, on every tab),
 * the current tab or the Map page, and the dock with STOP in its centre so STOP is on every tab too.
 *
 * The dock's left button opens the Map page. There it becomes the way out, and either side of
 * STOP the bar carries the map's own buttons (Drive, Edit), so the map itself gets the page.
 */
@Composable
private fun MainPage(
    state: RemoteState,
    actions: RemoteActions,
    tab: Tab,
    mapOpen: Boolean,
    drive: DriveSheetState,
    onTabChange: (Tab) -> Unit,
    onMapOpenChange: (Boolean) -> Unit,
    onOpenDemoControls: () -> Unit,
    onOpenPoints: () -> Unit,
    onOpenMaps: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenRobotInfo: () -> Unit,
    onEditTask: (String) -> Unit,
) {
    // The Map page's tools and dialogs live here, so the dock can carry its buttons.
    val map = rememberMapUi()
    val closeMap = {
        map.reset()
        onMapOpenChange(false)
    }
    BackHandler(enabled = mapOpen, onBack = closeMap)
    fun tabItem(t: Tab) = DockItem(t.icon, t.label, selectedIcon = t.selectedIcon, selected = tab == t, isTab = true) { onTabChange(t) }
    val stop = DockStop(moving = state.busy || !state.still, onStop = actions.onStop)

    Scaffold(
        topBar = {
            // No title bar: the readings are the top of the page. On the Map page they show the
            // map in use, not the speed.
            Column { StatusHeader(state, actions.onDismissAlert, onOpenMaps = onOpenMaps.takeIf { mapOpen }) }
        },
        bottomBar = {
            // The Map page draws its own: the bar is part of its card (below).
            if (!mapOpen) {
                NavDock(
                    module = DockItem(R.drawable.ic_tab_map, "Map", "Open the map and drive the robot") { onMapOpenChange(true) },
                    start = listOf(tabItem(Tab.TASKS)),
                    end = listOf(tabItem(Tab.SETTINGS)),
                    stop = stop,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val content = Modifier
            .padding(padding)
            .fillMaxSize()
        when {
            mapOpen -> {
                val dock = mapDock(state, actions, map, drive)
                // One piece: the map card running down into the bar; Exit, orange, in the cut-out.
                JoinedDock(
                    module = DockItem(R.drawable.ic_exit, "Exit", "Leave the map", accent = RemoteTheme.status.exit, onClick = closeMap),
                    start = dock.start,
                    end = dock.end,
                    stop = stop,
                    modifier = content,
                ) {
                    MapTab(state, actions, map, drive, Modifier.fillMaxSize())
                }
            }
            tab == Tab.TASKS -> TasksTab(state, actions, onOpenPoints, onEditTask, content)
            else -> SettingsTab(state, actions, onOpenActivity, onOpenRobotInfo, onOpenDemoControls, onOpenMaps, content)
        }
    }
}

// ---------------------------------------------------------------------- chrome

/** What the app calls the live robot: its short name once known (see [RobotName]). */
private fun robotLabel(state: RemoteState): String =
    state.robotName?.let(RobotName::short) ?: if (state.connected) "Robot" else "No robot"

private const val DEMO_HELP_URL = "https://github.com/aehils/uvc-remote-beta2#demo-mode"

/**
 * At the top of every page (under the title bar, where a page has one), pinned so it never
 * scrolls away: the robot's readings, and the runaway and UV banners.
 */
@Composable
internal fun StatusHeader(state: RemoteState, onDismissAlert: () -> Unit, onOpenMaps: (() -> Unit)? = null) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Telemetry(state, onOpenMaps) }
    AnimatedVisibility(state.runaway) { RunawayBanner() }
    AnimatedVisibility(state.uv != null || state.lampsOffOwed) { UvBanner(state) }
    // Kept while it hides, so the text doesn't vanish before the banner has slid away.
    var alert by remember { mutableStateOf("") }
    state.robotAlert?.let { alert = it }
    AnimatedVisibility(state.robotAlert != null) { RobotAlertBanner(alert, onDismissAlert) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The top line of the app, on every page, on the page's own background: BETA on the left, and in
 * the middle whether the robot is there, in capitals: a dot, green while the robot answers and
 * orange while it doesn't, and its name (or what the app is doing about it). Tapping it opens
 * Settings, where the robot is.
 */
@Composable
private fun StatusLine(state: RemoteState) {
    val status = RemoteTheme.status
    val open = LocalOpenSettings.current
    val link = state.link
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.clickable(onClickLabel = "Open the robot's settings", onClick = open) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "BETA",
            modifier = Modifier.align(Alignment.CenterStart),
            color = status.beta,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            maxLines = 1,
        )
        Row(
            Modifier
                .align(Alignment.Center)
                // Clear of BETA either side, so a long name stays centred and never runs into it.
                .widthIn(max = 220.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (state.connected) status.good else status.offline, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    state.connected -> if (state.demo) "DEMO robot" else robotLabel(state)
                    state.searching -> "Searching …"
                    link is Link.Disconnected && link.lastError != null -> "Not connected"
                    else -> "Connecting …"
                }.uppercase(Locale.US),
                color = if (state.connected) MaterialTheme.colorScheme.onSurface else status.offline,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A thin band at the top of every page, under the status line, while DEMO is on. */
@Composable
private fun DemoStrip() {
    Text(
        "DEMO MODE · SIMULATED ROBOT",
        modifier = Modifier
            .fillMaxWidth()
            .background(RemoteTheme.status.demoContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = RemoteTheme.status.demo,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/** STOP was sent again and again, but the robot keeps moving. */
@Composable
private fun RunawayBanner() {
    Text(
        "ROBOT STILL MOVING AFTER STOP. USE THE PHYSICAL E-STOP.",
        modifier = Modifier
            .fillMaxWidth()
            .background(RemoteTheme.status.stop)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

/** What the robot is complaining about (a refused or failed trip, a failed dock), until dismissed. */
@Composable
private fun RobotAlertBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RemoteTheme.status.warnContainer)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            color = RemoteTheme.status.warn,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss", tint = RemoteTheme.status.warn, modifier = Modifier.size(20.dp))
        }
    }
}

/** While the UV lamps are on, about to come on, or may still be on. Pinned on every page. */
@Composable
private fun UvBanner(state: RemoteState) {
    val left = state.uvLeftMs?.let { DriveController.clockText(it) } ?: ""
    val text = when (state.uv) {
        is Uv.Countdown -> "UV LAMPS ON IN $left. LEAVE THE ROOM."
        is Uv.Starting -> "UV LAMPS SWITCHING ON"
        is Uv.On -> "UV LAMPS ON · $left LEFT"
        null -> "UV LAMPS MAY STILL BE ON. THE ROBOT HASN'T ACCEPTED OFF."
    }
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .background(RemoteTheme.status.stop)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun StopBar(onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                .height(72.dp),
            // Flat, with the card corner: the dock's STOP, the width of the screen.
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RemoteTheme.status.stop, contentColor = Color.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        ) {
            Text("STOP", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
        }
    }
}

// ---------------------------------------------------------------------- cards

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/**
 * The robot's address, typed in: for a network the app can't search (the robot on another,
 * routed network). Folded away until asked for: staff pick robots by name.
 */
@Composable
private fun AddressEntry(state: RemoteState, onPing: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Enter address")
        }
        return
    }
    val status = RemoteTheme.status
    val focus = LocalFocusManager.current
    // Keyed on the address: the app may find the robot at a new one by itself.
    var hostText by rememberSaveable(state.host) { mutableStateOf(state.host) }
    val ping = {
        onPing(hostText)
        focus.clearFocus()
    }

    // The Ping icon doubles as the connection light: green when connected, orange when not.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AddressField(
            value = hostText,
            onValueChange = { hostText = it },
            enabled = true,
            onDone = ping,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = ping,
            modifier = Modifier.size(ADDRESS_ROW_HEIGHT),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_ping),
                contentDescription = if (state.connected) "Ping. Connected" else "Ping. Not connected",
                modifier = Modifier.size(22.dp),
                tint = if (state.connected) status.good else status.offline,
            )
        }
    }
}

/** While the robot isn't answering: what the last search found, the robots to pick, and searching again. */
@Composable
private fun RobotSearch(state: RemoteState, actions: RemoteActions) {
    if (!state.searching && state.searchNote != null) {
        Text(state.searchNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.foundRobots.forEach { robot ->
        OutlinedButton(
            onClick = { actions.onPickRobot(robot) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(robot.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (!state.searching) {
        TextButton(onClick = actions.onFindRobot, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Find robots")
        }
    }
}

private val ADDRESS_ROW_HEIGHT = 46.dp

/**
 * An outlined field at [ADDRESS_ROW_HEIGHT], so it lines up with the Ping button.
 * (OutlinedTextField has a 56dp minimum plus 8dp above it for the label, so it is built from parts here.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(ADDRESS_ROW_HEIGHT),
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = interaction,
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = enabled,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            label = { Text("Address") },
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 0.dp),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = enabled,
                    isError = false,
                    interactionSource = interaction,
                    colors = colors,
                    shape = shape,
                )
            },
        )
    }
}

/**
 * The e-stop badge (which also shows motion) and the battery ring, then velocity (the main read), on the right. With
 * [onOpenMaps] (the Map page): the map in use instead, its name opening the Maps page. Readings
 * fade when not connected, since they are stale.
 */
@Composable
private fun Telemetry(state: RemoteState, onOpenMaps: (() -> Unit)?) {
    val live = state.connected
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (live) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EStopBadge(state)
        Spacer(Modifier.width(8.dp))
        BatteryRing(state.battery)
        Spacer(Modifier.width(16.dp))

        if (onOpenMaps != null) {
            // Mapping shows on the map itself (its Scanning badge), so the map's name has the room.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                MapChip(state, onOpenMaps)
            }
        } else {
            // Velocity is the main read, in fixed-width slots so the figures never shift as they change.
            val ink = MaterialTheme.colorScheme.onSurface
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                Reading("Linear", if (live) reading(state.vx, 1) else "—", "m/s", ink, Modifier.width(96.dp))
                Reading("Angular", if (live) reading(Math.toDegrees(state.vth), 0) else "—", "°/s", ink, Modifier.width(88.dp))
            }
        }
    }
}

/**
 * The battery as a ring beside the e-stop badge, its size: the figure in the middle, and an arc
 * from the top, clockwise, that drains to nothing with the charge. Green; orange at 20% and
 * below, red at 10%. Unknown, just the empty track and a dash.
 */
@Composable
private fun BatteryRing(battery: Int?) {
    val status = RemoteTheme.status
    val color = when {
        battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
        battery <= 10 -> status.danger
        battery <= 20 -> status.offline
        else -> status.good
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = battery?.let { "Battery $it percent" } ?: "Battery unknown" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke))
            if (battery != null && battery > 0) {
                drawArc(
                    color, -90f, 360f * battery.coerceIn(0, 100) / 100f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            battery?.toString() ?: "—",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (battery != null && battery <= 20) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Rounds before formatting so sensor noise at rest reads 0, never "-0.00". */
private fun reading(value: Double, decimals: Int): String {
    val scale = Math.pow(10.0, decimals.toDouble())
    val rounded = Math.round(value * scale) / scale + 0.0 // + 0.0 turns -0.0 into 0.0
    return String.format(Locale.US, "%.${decimals}f", rounded)
}

/**
 * A bounded "E": solid red when the e-stop is pressed, grey otherwise. Never green: released is
 * not a "go" signal. It also shows motion, since a pressed e-stop and a moving robot can't
 * happen together: while a command runs (a step, a held arrow, a trip, docking) its border
 * pulses quickly in amber; if the robot moves with none running (pushed, or a task of its own),
 * the border stays amber. The letter never flashes.
 */
@Composable
private fun EStopBadge(state: RemoteState) {
    val shape = RoundedCornerShape(8.dp)
    val estop = state.estop
    val pressed = estop == EStop.PRESSED
    val warn = RemoteTheme.status.warn
    val grey = MaterialTheme.colorScheme.outline
    // Only animates while a command runs, so an idle robot costs no frames.
    val edge = when {
        pressed -> RemoteTheme.status.stop
        state.busy -> {
            val t by rememberInfiniteTransition(label = "motion").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse),
                label = "pulse",
            )
            lerp(warn, grey, t)
        }
        !state.still -> warn
        else -> grey
    }
    Box(
        Modifier
            .size(44.dp)
            .background(if (pressed) RemoteTheme.status.stop else Color.Transparent, shape)
            .border(if (!pressed && (state.busy || !state.still)) 3.dp else 2.dp, edge, shape)
            .semantics {
                contentDescription = when (estop) {
                    EStop.PRESSED -> "E-stop pressed"
                    EStop.RELEASED -> "E-stop released"
                    null -> "E-stop unknown"
                } + when {
                    pressed -> ""
                    state.busy -> ". Running: ${state.busyLabel ?: "a command"}"
                    !state.still -> ". Robot moving"
                    else -> ". Robot still"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "E",
            color = if (pressed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun Reading(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = valueColor)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/**
 * The d-pad, pulled up from the bottom of the pages over the main one (the Map page has its own
 * Drive button, the Tasks and Settings tabs none). Only the handle takes the open and close
 * gesture (a tap, or a flick up or down), so the arrows keep their presses to themselves.
 * Closing it mid-press lets go of the arrow, as lifting the finger does. Closed, the handle still says why the pad is locked.
 */
@Composable
internal fun DriveSheet(state: RemoteState, actions: RemoteActions, sheet: DriveSheetState) {
    val open = sheet.open
    val change by rememberUpdatedState(sheet.onOpenChange)
    val flick = with(LocalDensity.current) { 24.dp.toPx() }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onVerticalDrag = { pointer, dy ->
                                pointer.consume()
                                dragged += dy
                            },
                            onDragEnd = {
                                if (dragged < -flick) change(true) else if (dragged > flick) change(false)
                            },
                        )
                    }
                    .clickable(onClickLabel = if (open) "Hide the drive controls" else "Show the drive controls") {
                        sheet.onOpenChange(!open)
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (open) "▾" else "▴", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "DRIVE",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    driveLock(state) ?: "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    DrivePad(state, actions, buttonHeight = 64.dp)
                }
            }
        }
    }
}

/** Why the pad is locked, in a few words, or null. */
private fun driveLock(state: RemoteState): String? = when {
    !state.connected -> "Not connected"
    state.docked -> "Docked"
    state.estop == EStop.PRESSED -> "E-stop pressed"
    state.uv != null || state.lampsOffOwed -> "Locked while the UV lamps may be on"
    else -> null
}

/**
 * The d-pad on the Map page, in the card under the map: opened and closed by the Drive button.
 * Spaced like the bar below it, so its buttons line up with the bar's.
 */
@Composable
internal fun DrivePanel(state: RemoteState, actions: RemoteActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        driveLock(state)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DrivePad(state, actions, buttonHeight = 60.dp, spacing = 6.dp)
    }
}

// ---------------------------------------------------------------------- tasks

/**
 * The Tasks tab: the task under way (or how the last one ended), GO TO CHARGER (UNDOCK while
 * docked), the UV switch, the saved disinfection tasks, and Disinfection Points.
 */
@Composable
private fun TasksTab(state: RemoteState, actions: RemoteActions, onOpenPoints: () -> Unit, onEditTask: (String) -> Unit, modifier: Modifier) {
    val goal = state.running?.goal
    val charging = state.running?.label?.startsWith("Go to charger") == true
    val count = (state.points as? Points.Loaded)?.list?.size
    // Tasks are checked against the points on the robot's map.
    LaunchedEffect(state.connected, state.points is Points.NotLoaded) {
        if (state.connected && state.points is Points.NotLoaded) actions.onLoadPoints()
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.mapping) TasksNote("The robot is mapping. Trips and disinfection wait until the map is saved or discarded on the Map page.")
        val run = state.taskRun
        val report = state.taskReport
        if (run != null) TaskRunCard(state, run, actions)
        else if (report != null) TaskReportCard(report, actions.onDismissTaskReport)
        if (state.docked) ChargerButton("UNDOCK", state.canDrive, actions.onUndock)
        else ChargerButton("GO TO CHARGER", state.canNavigate, actions.onGoToCharger)
        UvSwitch(state, actions)

        SectionLabel("Disinfection tasks")
        SavedTasks(state, actions, onEditTask)

        SectionLabel("Points")
        val uv = state.uv
        val trip = goal != null && !charging && run == null
        TaskItem(
            icon = R.drawable.ic_tab_tasks,
            title = "Disinfection Points",
            detail = when {
                run == null && (uv != null || state.lampsOffOwed) -> uvStatus(state)
                trip -> "On the way to ${goal!!.name}"
                count != null -> "$count saved. Send the robot to one, or add one."
                else -> "Send the robot to a saved point, or add one where it stands."
            },
            highlight = trip || (run == null && (uv != null || state.lampsOffOwed)),
            onClick = onOpenPoints,
        )
    }
}

/**
 * The UV lamps, on or off by hand. ON asks first, then switches them on at once (no countdown);
 * OFF needs no asking. It shows any run, the Points page's included, so it also ends those.
 */
@Composable
private fun UvSwitch(state: RemoteState, actions: RemoteActions) {
    val status = RemoteTheme.status
    var confirming by rememberSaveable { mutableStateOf(false) }
    val owed = state.uv == null && state.lampsOffOwed
    val on = state.uv != null || owed
    Panel(color = if (on) status.dangerContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UV Lamps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (on) uvStatus(state) else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) status.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on,
                onCheckedChange = { if (it) confirming = true else actions.onStopDisinfection() },
                // An owed OFF is already being resent; nothing for the switch to do until it lands.
                enabled = if (on) !owed else state.canDisinfect,
                colors = SwitchDefaults.colors(checkedTrackColor = status.danger),
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Switch the UV lamps on?") },
            text = {
                Text(
                    "UV-C light burns skin and eyes. Make sure nobody is in the room and the door is closed.\n\n" +
                        "The lamps come on now and go off after ${DriveController.MAX_UV_MINUTES} min at the latest. " +
                        "The switch, STOP, the e-stop, or leaving the app switches them off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        actions.onSwitchLampsOn()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = status.danger),
                ) { Text("TURN ON", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** One row of the Tasks list. The whole card opens the task's page. */
@Composable
private fun TaskItem(icon: Int, title: String, detail: String, highlight: Boolean, onClick: () -> Unit) {
    val status = RemoteTheme.status
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) status.warnContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) status.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Disinfection Points: disinfect where the robot stands, send it to a point, or save where it
 * stands as a new one.
 * Like the DEMO controls page, it keeps STOP and the robot's readings on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointsPage(state: RemoteState, actions: RemoteActions, drive: DriveSheetState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val points = state.points
    var adding by rememberSaveable { mutableStateOf(false) }
    // Read the points once connected (again after a robot switch, which forgets them).
    LaunchedEffect(state.connected, points is Points.NotLoaded) {
        if (state.connected && points is Points.NotLoaded) actions.onLoadPoints()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                    title = { Text("Disinfection Points", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        TextButton(
                            onClick = actions.onLoadPoints,
                            enabled = state.connected && points != Points.Loading,
                        ) { Text("Refresh") }
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
        ) {
            // The disinfection card stays put, so START (and LAMPS OFF) never scroll out of view.
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { DisinfectCard(state, actions) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel("Send the robot to")
                when {
                    points is Points.Loaded && points.list.isNotEmpty() -> {
                        val goal = state.running?.goal
                        points.list.forEach {
                            PointRow(it, goingHere = it == goal, here = it == state.atPoint, state.canNavigate, actions.onGoTo)
                        }
                    }
                    points is Points.Loaded -> TasksNote("No disinfection points are saved on this robot's map yet. Add one below.")
                    points is Points.Failed -> TasksNote("Couldn't read the points: ${points.error}")
                    points == Points.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    !state.connected -> TasksNote("Connect to the robot to see its points.")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel("Add a point")
                OutlinedButton(
                    onClick = { adding = true },
                    enabled = state.canAddPoint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (state.addingPoint) "Saving …" else "+  Add point where the robot is",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                (state.pointMessage ?: when {
                    !state.connected -> "Connect to the robot first."
                    state.mapping -> "The robot is mapping. Save the map first."
                    state.busy || !state.still -> "The robot must be standing still."
                    else -> null
                })?.let { TasksNote(it) }
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

/** "where" text for the disinfection card and dialogs. */
private fun uvWhere(point: String?) = point?.let { "at $it" } ?: "where the robot stands"

/** One line on the run, for the Tasks list. */
private fun uvStatus(state: RemoteState): String {
    val left = state.uvLeftMs?.let { DriveController.clockText(it) } ?: ""
    return when (val uv = state.uv) {
        is Uv.Countdown -> "UV lamps on in $left, ${uvWhere(uv.point)}"
        is Uv.Starting -> "UV lamps switching on ${uvWhere(uv.point)}"
        is Uv.On -> "UV on ${uvWhere(uv.point)}: $left left"
        null -> "The UV lamps may still be on: the robot hasn't accepted OFF"
    }
}

/**
 * Top of the Disinfection Points page: switches the UV lamps on where the robot stands, after
 * the start delay, for the set time. The tune button opens the settings.
 */
@Composable
private fun DisinfectCard(state: RemoteState, actions: RemoteActions) {
    val status = RemoteTheme.status
    var confirming by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val uv = state.uv
    val set = state.uvSettings
    val left = state.uvLeftMs?.let { DriveController.clockText(it) } ?: ""
    val delayText = "${set.delaySeconds} s after you start"

    Panel(color = if (uv != null || state.lampsOffOwed) status.dangerContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val point = if (uv != null) uv.point else state.atPoint?.name
            Text(
                point?.let { "At $it" } ?: "Not at a saved point",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { editing = true }, enabled = uv == null) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Disinfection settings", modifier = Modifier.size(22.dp))
            }
        }

        val off = @Composable { label: String ->
            Button(
                onClick = actions.onStopDisinfection,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = status.stop, contentColor = Color.White),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
        }
        when (uv) {
            null -> {
                Button(
                    onClick = { confirming = true },
                    enabled = state.canDisinfect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("START DISINFECTION", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
            }
            is Uv.Countdown -> {
                Text(
                    "Lamps on in $left. Leave the room and close the door.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = status.danger,
                )
                // START NOW skips the rest of the countdown, for when the room is already clear.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
            is Uv.Starting -> {
                Text("Switching the lamps on …", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = status.danger)
                off("LAMPS OFF")
            }
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = status.danger,
                )
                off("LAMPS OFF")
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Start UV disinfection?") },
            text = {
                Text(
                    "UV-C light burns skin and eyes. Make sure nobody is in the room and the door is closed.\n\n" +
                        "The lamps come on ${uvWhere(state.atPoint?.name)} $delayText and stay on for ${set.minutes} min. " +
                        "STOP, LAMPS OFF, the e-stop, or leaving the app switches them off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onStartDisinfection()
                }) { Text("START", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
    if (editing) {
        UvSettingsDialog(
            current = set,
            onDismiss = { editing = false },
            onSave = {
                editing = false
                actions.onUvSettings(it)
            },
        )
    }
}

/** Durations offered by the − / + buttons: every minute up to 5, then every 5. */
internal val UV_MINUTE_STEPS = (1..4) + (5..DriveController.MAX_UV_MINUTES step 5)
internal val UV_DELAYS = listOf(DriveController.MIN_UV_DELAY_S, 30, 60)

@Composable
private fun UvSettingsDialog(current: UvSettings, onDismiss: () -> Unit, onSave: (UvSettings) -> Unit) {
    var minutes by rememberSaveable { mutableStateOf(current.minutes) }
    var delay by rememberSaveable { mutableStateOf(current.delaySeconds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disinfection settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("UV lamps on for")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { minutes = UV_MINUTE_STEPS.last { it < minutes } },
                        enabled = minutes > UV_MINUTE_STEPS.first(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("−", fontSize = 20.sp) }
                    Text(
                        "$minutes min",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { minutes = UV_MINUTE_STEPS.first { it > minutes } },
                        enabled = minutes < UV_MINUTE_STEPS.last(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("+", fontSize = 20.sp) }
                }
                Spacer(Modifier.height(6.dp))
                SectionLabel("Lamps come on after")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UV_DELAYS.forEach { d ->
                        FilterChip(selected = delay == d, onClick = { delay = d }, label = { Text("$d s") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(UvSettings(minutes, delay)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AddPointDialog(taken: Set<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable {
        mutableStateOf(generateSequence(taken.size) { it + 1 }.map { "Point $it" }.first { it !in taken })
    }
    val clean = name.trim()
    val problem = when {
        clean.isEmpty() -> "Enter a name."
        clean.length > DriveController.MAX_POINT_NAME -> "Use ${DriveController.MAX_POINT_NAME} characters or fewer."
        clean in taken -> "A point with this name already exists."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add point here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Saves where the robot is standing, and the way it faces, as a disinfection point on its map.",
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
                    keyboardActions = KeyboardActions(onDone = { if (problem == null) onAdd(clean) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(clean) }, enabled = problem == null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PointRow(point: Waypoint, goingHere: Boolean, here: Boolean, canDrive: Boolean, onGoTo: (Waypoint) -> Unit) {
    val status = RemoteTheme.status
    Panel(color = if (goingHere) status.warnContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(point.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (here) {
                    Text("Robot is here", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = status.good)
                }
                point.pose?.let {
                    Text(
                        String.format(Locale.US, "x %.2f   y %.2f", it.x, it.y),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (goingHere) {
                Text(
                    "ON THE WAY",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = status.warn,
                )
            } else {
                Button(
                    onClick = { onGoTo(point) },
                    enabled = canDrive,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("GO", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@Composable
internal fun TasksNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** GO TO CHARGER, or UNDOCK. Either moves the robot, so it is held to the drive pad's interlock. */
@Composable
private fun ChargerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_dock), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

/**
 * The arrows drive while held; Turn 180° in the middle is a fixed step. On the charging pile the
 * arrows are locked and the middle is UNDOCK instead: driving by hand starts with leaving the pile.
 */
@Composable
private fun DrivePad(state: RemoteState, actions: RemoteActions, buttonHeight: Dp, spacing: Dp = 8.dp) {
    val gap = Arrangement.spacedBy(spacing)
    Column(verticalArrangement = gap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Spacer(Modifier.weight(1f))
            HoldButton(Move.FORWARD, "▲", state, actions, buttonHeight)
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            HoldButton(Move.LEFT, "\u25C0\uFE0E", state, actions, buttonHeight)
            // ↺: the 180° turn goes left (counter-clockwise), the same /cmd/turn direction as LEFT.
            if (state.docked || state.undocking) UndockPadButton(state, actions.onUndock, buttonHeight)
            else PadButton(Move.AROUND, "↺", "180°", state.canDriveByHand, actions.onMove, buttonHeight, tonal = true)
            HoldButton(Move.RIGHT, "\u25B6\uFE0E", state, actions, buttonHeight)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Spacer(Modifier.weight(1f))
            HoldButton(Move.BACK, "▼", state, actions, buttonHeight)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The pad's buttons: squared off, to sit with the cards and the dock. */
private val PAD_SHAPE = RoundedCornerShape(8.dp)

/**
 * An arrow that drives while held (see [DriveController.hold]). It reports the press, then a sign
 * of life every [DriveController.HOLD_ALIVE_MS] while it lasts, then the release.
 *
 * Only lifting the finger lets go. A resting thumb always drifts, and the page around the pad
 * scrolls: left alone, the scroll takes the touch over after a few millimetres, which used to
 * count as letting go (2026-10-08, first real test: the robot only ever nudged). So the press
 * keeps every move to itself, and drifting, even off the button, keeps driving. The button
 * leaving the screen mid-press, or the system cancelling the touch, still lets go; and if the
 * screen stops reporting at all, the controller lets go by itself.
 */
@Composable
private fun RowScope.HoldButton(move: Move, glyph: String, state: RemoteState, actions: RemoteActions, height: Dp) {
    val enabled = state.canHold(move)
    val held = state.holding == move
    // Read inside the gesture, which must not restart (and so let go) when these change mid-press.
    val canStart by rememberUpdatedState(enabled)
    val act by rememberUpdatedState(actions)
    val colors = MaterialTheme.colorScheme
    val shape = PAD_SHAPE
    val content = when {
        held -> colors.onPrimaryContainer
        enabled -> colors.onPrimary
        else -> colors.onSurface.copy(alpha = 0.38f)
    }
    Box(
        Modifier
            .weight(1f)
            .height(height)
            .clip(shape)
            .background(
                when {
                    held -> colors.primaryContainer
                    enabled -> colors.primary
                    else -> colors.onSurface.copy(alpha = 0.12f)
                }
            )
            .then(if (held) Modifier.border(3.dp, colors.primary, shape) else Modifier)
            .semantics { contentDescription = "${move.word}: hold to drive" }
            .pointerInput(move) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!canStart) return@awaitEachGesture
                    down.consume()
                    act.onHold(move)
                    try {
                        while (true) {
                            // A still thumb sends no events: the timeout keeps the sign of life going.
                            val event = withTimeoutOrNull(DriveController.HOLD_ALIVE_MS) { awaitPointerEvent() }
                            val finger = event?.changes?.firstOrNull { it.id == down.id }
                            finger?.consume() // so the scrolling page never takes the press over
                            if (event != null && (finger == null || !finger.pressed)) break // lifted, or cancelled
                            act.onHoldAlive(move)
                        }
                    } finally {
                        act.onRelease(move)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 30.sp, color = content)
    }
}

/** UNDOCK in the d-pad's middle while docked; a spinner while it runs. */
@Composable
private fun RowScope.UndockPadButton(state: RemoteState, onUndock: () -> Unit, height: Dp) {
    Button(
        onClick = onUndock,
        enabled = state.docked && state.canDrive,
        modifier = Modifier
            .weight(1f)
            .height(height),
        shape = PAD_SHAPE,
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.undocking) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = RemoteTheme.status.warn)
            } else {
                Icon(painterResource(R.drawable.ic_dock), contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("UNDOCK", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun RowScope.PadButton(move: Move, glyph: String, label: String, enabled: Boolean, onMove: (Move) -> Unit, height: Dp, tonal: Boolean = false) {
    Button(
        onClick = { onMove(move) },
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(height),
        shape = PAD_SHAPE,
        colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, fontSize = 26.sp)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * DEMO controls: simulate robot events that are hard to set up on the real robot.
 * A page of its own, but STOP and the robot readings stay on screen so each event's effect is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoControlsPage(state: RemoteState, demo: DemoStatus, actions: RemoteActions, drive: DriveSheetState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                    title = { Text("DEMO controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("Faults")
            DemoToggle("E-stop pressed", "As if the physical e-stop were pressed. Driving is blocked; pressing mid-step stops the robot.", demo.estopPressed, actions.onDemoEstop)
            DemoToggle("Wi-Fi dropped", "The robot stops answering. The Ping icon turns orange after about 3 missed polls.", demo.offline, actions.onDemoWifi)
            DemoToggle("Obstacle ahead", "The next step is accepted but never starts, so the app reports no motion.", demo.blockPending, actions.onDemoBlock)
            OutlinedButton(
                actions.onDemoChargeOnItsOwn,
                Modifier.fillMaxWidth(),
                enabled = !demo.robotBusy,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Robot goes to charge by itself") }
            OutlinedButton(
                actions.onDemoPerson,
                Modifier.fillMaxWidth(),
                enabled = demo.navigating,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Person in the way") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Robot")
            Text(
                "Simulated UV lamps (${DriveController.UV_SUPPLY.uppercase(Locale.US).replace("V", " V")} supply): " +
                    if (demo.uvOn) "ON" else "off",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (demo.uvOn) FontWeight.Bold else FontWeight.Normal,
                color = if (demo.uvOn) RemoteTheme.status.danger else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(actions.onDemoBattery, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Battery −10%") }
                OutlinedButton(actions.onDemoReset, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Reset robot") }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DemoToggle(title: String, detail: String, on: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = RemoteTheme.status.demoBold),
        )
    }
}

/**
 * Settings: what isn't running the robot, grouped by subject, each row opening a page or a
 * dialog. The activity log comes first: it is what's looked at most, after something odd.
 */
@Composable
private fun SettingsTab(
    state: RemoteState,
    actions: RemoteActions,
    onOpenActivity: () -> Unit,
    onOpenRobotInfo: () -> Unit,
    onOpenDemoControls: () -> Unit,
    onOpenMaps: () -> Unit,
    modifier: Modifier,
) {
    var editingUv by rememberSaveable { mutableStateOf(false) }
    val uv = state.uvSettings
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Records")
        // The newest line, without its time: what happened last.
        SettingsItem(
            R.drawable.ic_tab_activity, "Activity log",
            state.log.firstOrNull()?.substringAfter("  ")?.trim() ?: "Nothing yet",
            onOpenActivity,
        )
        SectionLabel("Disinfection")
        SettingsItem(R.drawable.ic_tune, "Disinfection timing", "${uv.minutes} min UV · ${uv.delaySeconds} s exit delay") { editingUv = true }
        SectionLabel("Robot")
        RobotConnection(state, actions)
        SettingsItem(
            R.drawable.ic_ping, "Robot information",
            "ID, firmware, power and navigation",
            onOpenRobotInfo,
        )
        SectionLabel("DEMO")
        Panel {
            // Never switch robots mid-step: the controller refuses, so the switch is held too.
            DemoToggle(
                "DEMO mode",
                if (state.demo) "Operating a simulated robot. No data is sent."
                else "Robot operation is live. Obey safety precautions.",
                state.demo,
                onToggle = { actions.onDemoChange(!state.demo) },
                enabled = !state.busy,
            )
            if (state.busy) TasksNote("Wait for the current step to finish to switch.")
            Text(
                buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Url(
                            DEMO_HELP_URL,
                            TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)),
                        )
                    ) { append("Learn more about DEMO") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Controls simulate robot events, so they only exist while DEMO is on.
        if (state.demo) SettingsItem(R.drawable.ic_tune, "DEMO controls", "Simulate faults: e-stop, Wi-Fi, obstacles", onOpenDemoControls)
        SectionLabel("Mapping")
        SettingsItem(R.drawable.ic_tab_map, "Maps", state.currentMap?.let { "In use: ${it.label}" } ?: "The maps saved on the robot", onOpenMaps)
    }
    if (editingUv) {
        UvSettingsDialog(
            current = uv,
            onDismiss = { editingUv = false },
            onSave = {
                editingUv = false
                actions.onUvSettings(it)
            },
        )
    }
}

/**
 * The robot the app talks to, at the top of Settings' Robot section: its name and a connection
 * light (green answering, orange not). While it isn't answering: what the last search found, the
 * robots to pick, Find robots, and the address entry (folded away: staff pick robots by name).
 */
@Composable
private fun RobotConnection(state: RemoteState, actions: RemoteActions) {
    val status = RemoteTheme.status
    val link = state.link
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (state.connected) status.good else status.offline, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.demo) "DEMO robot" else robotLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        state.connected -> "Connected"
                        state.searching -> "Looking for it on this Wi-Fi …"
                        link is Link.Disconnected && link.lastError != null -> "Not answering: ${link.lastError}"
                        else -> "Not connected yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.connected) status.good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!state.demo) {
            if (!state.connected) RobotSearch(state, actions)
            AddressEntry(state, actions.onPing)
        }
    }
}

/** A Settings row: what it is, and its current value. The whole card opens it. */
@Composable
private fun SettingsItem(icon: Int, title: String, value: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A page opened from Settings. Like the other pages, it keeps STOP, the drive sheet and the robot's readings on screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(
    title: String,
    state: RemoteState,
    actions: RemoteActions,
    drive: DriveSheetState,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                    title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
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
        content(
            Modifier
                .padding(padding)
                .fillMaxSize()
        )
    }
}

/** What the app knows about the robot: who it is, its software, power and navigation. Read only. */
@Composable
private fun RobotInfo(state: RemoteState, modifier: Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Robot")
        Panel {
            InfoRow("Robot", if (state.demo) "DEMO robot" else robotLabel(state))
            if (!state.demo) {
                InfoRow("Robot ID", state.robotName ?: "—", mono = true)
                InfoRow("Address", state.host, mono = true)
            }
            InfoRow("Navigation firmware", state.version ?: "—", mono = true)
        }
        SectionLabel("Power")
        Panel {
            InfoRow("Battery", state.battery?.let { "$it%" } ?: "—", mono = true)
            InfoRow("On the charger", if (state.docked) "Yes" else "No")
            InfoRow(
                "E-stop",
                when (state.estop) {
                    EStop.PRESSED -> "Pressed"
                    EStop.RELEASED -> "Released"
                    null -> "—"
                },
            )
        }
        SectionLabel("Navigation")
        Panel {
            InfoRow(
                "Mode",
                when (state.mode) {
                    RobotMode.MAPPING -> "Mapping"
                    RobotMode.EXTENDING -> "Extending the map"
                    RobotMode.NAVIGATION -> "Navigating"
                    null -> "—"
                },
            )
            InfoRow("Map in use", state.currentMap?.label ?: "—")
            InfoRow("Saved points", (state.points as? Points.Loaded)?.list?.size?.toString() ?: "—", mono = true)
        }
    }
}

/** A label and its value on one line. The value is selectable, for copying into a report. */
@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = if (mono) FontFamily.Monospace else null,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** Newest first. Selectable, so a log can be copied into a bug report. */
@Composable
private fun ActivityLog(lines: List<String>, modifier: Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Newest first")
        SelectionContainer {
            Text(
                lines.joinToString("\n").ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------- previews

private val PREVIEW_POINTS = listOf(
    Waypoint("Bed 1", "normal", Pose(1.6, -1.2, -1.57)),
    Waypoint("Bed 2", "normal", Pose(-1.4, 1.2, 1.57)),
    Waypoint("Sink area", "normal", Pose(-1.4, -1.3, 3.14)),
)

private fun previewState(demo: Boolean, moving: Boolean) = RemoteState(
    demo = demo,
    host = "192.168.1.228",
    link = Link.Connected(EStop.RELEASED),
    version = "RSNF1-v5.1.20_01",
    battery = 18,
    vx = if (moving) 0.27 else 0.0,
    vth = 0.0,
    phase = if (moving) StepPhase.Stepping(Step("Forward 0.5 m", "/cmd/move", "{}", false, 4000), Tracking(0, 0))
    else StepPhase.Idle,
    points = Points.Loaded(PREVIEW_POINTS),
    addingPoint = false,
    pointMessage = null,
    runaway = false,
    atPoint = null,
    uv = null,
    uvLeftMs = null,
    uvSettings = UvSettings(),
    lampsOffOwed = false,
    demoStatus = if (demo) DemoStatus(estopPressed = false, offline = false, blockPending = true, navigating = false, robotBusy = moving, uvOn = false) else null,
    log = listOf(
        "12:04:10  [DEMO] Forward 0.5 m: accepted {}",
        "12:04:10  [DEMO] Forward 0.5 m: sending /cmd/move {\"distance\":50,\"direction\":1,\"speed\":0.30}",
    ),
)

@Preview(name = "Demo, moving", heightDp = 1500)
@Composable
private fun PreviewDemo() = RemoteTheme(dark = false) { RemoteScreen(previewState(demo = true, moving = true), RemoteActions()) }

@Preview(name = "Live, dark", heightDp = 1300)
@Composable
private fun PreviewLiveDark() = RemoteTheme(dark = true) { RemoteScreen(previewState(demo = false, moving = false), RemoteActions()) }

@Preview(name = "Disinfection Points, going to one", heightDp = 1100)
@Composable
private fun PreviewPoints() = RemoteTheme(dark = false) {
    val goal = PREVIEW_POINTS[1]
    val state = previewState(demo = true, moving = true).copy(
        phase = StepPhase.Stepping(Step("Go to ${goal.name}", "/cmd/nav_name", "{}", false, 60_000, goal = goal), Tracking(0, 0)),
    )
    PointsPage(state, RemoteActions(), DriveSheetState(false) {}, onBack = {})
}

@Preview(name = "Disinfection Points, UV on", heightDp = 1100)
@Composable
private fun PreviewPointsUv() = RemoteTheme(dark = true) {
    val state = previewState(demo = false, moving = false).copy(
        atPoint = PREVIEW_POINTS[0],
        uv = Uv.On("Bed 1", since = 0, until = 300_000),
        uvLeftMs = 192_000,
    )
    PointsPage(state, RemoteActions(), DriveSheetState(true) {}, onBack = {})
}

@Preview(name = "Tasks, dark", heightDp = 500)
@Composable
private fun PreviewTasks() = RemoteTheme(dark = true) {
    TasksTab(previewState(demo = false, moving = false), RemoteActions(), {}, {}, Modifier.background(MaterialTheme.colorScheme.background))
}

private val PREVIEW_TASK = DisinfectionTask(
    "a", "Ward A", listOf(TaskStop("Bed 1", 5), TaskStop("Bed 2", 5), TaskStop("Sink area", 2)), rounds = 2,
)

@Preview(name = "Tasks, a task under way", heightDp = 1100)
@Composable
private fun PreviewTaskRunning() = RemoteTheme(dark = false) {
    val state = previewState(demo = false, moving = false).copy(
        tasks = listOf(PREVIEW_TASK, PREVIEW_TASK.copy(id = "b", name = "Sink only", stops = listOf(TaskStop("Sink area", 10)), rounds = 1)),
        taskRun = TaskRun(PREVIEW_TASK, round = 1, index = 1, stage = TaskStage.Disinfecting, results = emptyList()),
        atPoint = PREVIEW_POINTS[1],
        uv = Uv.On("Bed 2", since = 0, until = 300_000),
        uvLeftMs = 192_000,
    )
    TasksTab(state, RemoteActions(), {}, {}, Modifier.background(MaterialTheme.colorScheme.background))
}

@Preview(name = "Task editor", heightDp = 1300)
@Composable
private fun PreviewTaskEditor() = RemoteTheme(dark = true) {
    val state = previewState(demo = false, moving = false).copy(tasks = listOf(PREVIEW_TASK))
    TaskEditorPage(state, RemoteActions(), PREVIEW_TASK.copy(stops = PREVIEW_TASK.stops + TaskStop("Bed 9", 5)), DriveSheetState(false) {}, onBack = {})
}
