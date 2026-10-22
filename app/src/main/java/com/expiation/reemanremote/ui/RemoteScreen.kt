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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.expiation.reemanremote.Link
import com.expiation.reemanremote.Move
import com.expiation.reemanremote.Points
import com.expiation.reemanremote.Pose
import com.expiation.reemanremote.R
import com.expiation.reemanremote.RemoteState
import com.expiation.reemanremote.RobotMode
import com.expiation.reemanremote.SavedMap
import com.expiation.reemanremote.Step
import com.expiation.reemanremote.TaskRun
import com.expiation.reemanremote.TaskStage
import com.expiation.reemanremote.TaskStop
import com.expiation.reemanremote.StepPhase
import com.expiation.reemanremote.StopOutcome
import com.expiation.reemanremote.StopResult
import com.expiation.reemanremote.WhenDone
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
    val onStartPlan: (points: List<String>, whenDone: WhenDone) -> Unit = { _, _ -> },
    val onRetryTaskStop: () -> Unit = {},
    val onSkipTaskStop: () -> Unit = {},
    val onEndTask: () -> Unit = {},
    val onDismissTaskReport: () -> Unit = {},
)

@Composable
fun RemoteScreen(state: RemoteState, actions: RemoteActions) {
    // Kept here, not in MainPage, so coming back from another page returns to the same tab.
    var tab by rememberSaveable { mutableStateOf(Tab.REMOTE) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    // The drive sheet stays open or closed across tabs and pages.
    var driveOpen by rememberSaveable { mutableStateOf(false) }
    // The points picked on the Tasks tab, kept here so switching tabs doesn't drop them.
    var plan by rememberSaveable(stateSaver = TaskPlanSaver) { mutableStateOf(TaskPlan()) }
    // The controls page only exists in DEMO: leaving DEMO closes it.
    LaunchedEffect(state.demo) { if (!state.demo && page == Page.DEMO_CONTROLS) page = Page.MAIN }
    // Mapping is driving: the pad comes up by itself.
    LaunchedEffect(state.mapping) { if (state.mapping) driveOpen = true }

    val drive = DriveSheetState(driveOpen) { driveOpen = it }
    val demoStatus = state.demoStatus
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedVisibility(state.demo) { DemoStrip() }
        Box(Modifier.weight(1f)) {
            when {
                page == Page.DEMO_CONTROLS && demoStatus != null ->
                    DemoControlsPage(state, demoStatus, actions, drive, onBack = { page = Page.MAIN })
                page == Page.MAPS -> MapsPage(
                    state, actions, drive,
                    onBack = { page = Page.MAIN },
                    onShowMap = {
                        page = Page.MAIN
                        tab = Tab.MAP
                    },
                )
                else -> MainPage(
                    state, actions, tab, drive, plan,
                    onPlanChange = { plan = it },
                    onTabChange = { tab = it },
                    onOpenDemoControls = { page = Page.DEMO_CONTROLS },
                    onOpenMaps = { page = Page.MAPS },
                )
            }
        }
    }
}

/** Pages over the main (tabbed) page. Each keeps STOP, the drive sheet and the robot's readings on screen. */
private enum class Page { MAIN, DEMO_CONTROLS, MAPS }

/** Whether the drive sheet is pulled up, and how to change that. */
internal class DriveSheetState(val open: Boolean, val onOpenChange: (Boolean) -> Unit)

private enum class Tab(val label: String, val icon: Int) {
    REMOTE("Remote", R.drawable.ic_tab_remote),
    TASKS("Tasks", R.drawable.ic_tab_tasks),
    MAP("Map", R.drawable.ic_tab_map),
    ACTIVITY("Activity", R.drawable.ic_tab_activity),
}

/**
 * Layout, top to bottom: DEMO strip (from [RemoteScreen]), title bar, the robot's readings (pinned, on every tab),
 * the current tab, and the nav bar with STOP in its centre so STOP is on every tab too.
 */
@Composable
private fun MainPage(
    state: RemoteState,
    actions: RemoteActions,
    tab: Tab,
    drive: DriveSheetState,
    plan: TaskPlan,
    onPlanChange: (TaskPlan) -> Unit,
    onTabChange: (Tab) -> Unit,
    onOpenDemoControls: () -> Unit,
    onOpenMaps: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopBar(state, actions, onOpenDemoControls)
                // On the Map tab the readings show the robot's mode and the map, not its speed.
                StatusHeader(state, actions.onDismissAlert, onOpenMaps = onOpenMaps.takeIf { tab == Tab.MAP })
            }
        },
        bottomBar = {
            Column {
                // On the Remote tab the pad is the page itself. The Tasks tab has START over the nav
                // bar instead, except at a point a task couldn't reach: then the pad, to free the robot.
                val held = state.taskRun?.stage is TaskStage.Held
                if (tab != Tab.REMOTE && (tab != Tab.TASKS || held)) DriveSheet(state, actions, drive)
                NavBar(tab, onTabChange, moving = state.busy || !state.still, actions.onStop)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val content = Modifier
            .padding(padding)
            .fillMaxSize()
        when (tab) {
            // The pad sits at the bottom, by the thumb and next to STOP. Portrait only, so it always fits.
            Tab.REMOTE -> Column(
                content.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                DriveCard(state, actions)
            }
            Tab.TASKS -> TasksTab(state, actions, plan, onPlanChange, content)
            Tab.MAP -> MapTab(state, actions, onOpenMaps, content)
            Tab.ACTIVITY -> ActivityLog(state.log, content)
        }
    }
}

/** Four tabs with STOP in the centre: always on screen, always under the thumb, and never a tab. */
@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit, moving: Boolean, onStop: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        @Composable
        fun RowScope.item(t: Tab) = NavigationBarItem(
            selected = current == t,
            onClick = { onSelect(t) },
            icon = { Icon(painterResource(t.icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
            label = { Text(t.label) },
        )
        item(Tab.REMOTE)
        item(Tab.TASKS)
        StopNavButton(moving, onStop)
        item(Tab.MAP)
        item(Tab.ACTIVITY)
    }
}

/** STOP as the nav bar's centre. While the robot is moving it gets a pulsing ring, so it is found fastest then. */
@Composable
private fun RowScope.StopNavButton(moving: Boolean, onStop: () -> Unit) {
    val stop = RemoteTheme.status.stop
    val pulse by rememberInfiniteTransition(label = "stop").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "ring",
    )
    Box(
        Modifier
            .weight(1.2f)
            .height(80.dp), // the bar's height; fillMaxHeight would grow the bar to the whole screen
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .border(3.dp, if (moving) stop.copy(alpha = pulse) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onStop,
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = stop, contentColor = Color.White),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
            ) {
                Text("STOP", fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------- chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(state: RemoteState, actions: RemoteActions, onOpenDemoControls: () -> Unit) {
    TopAppBar(
        title = { BetaPill() },
        actions = {
            BatteryReading(state)
            Spacer(Modifier.width(12.dp))
            RobotMenu(state, actions, onOpenDemoControls)
            Spacer(Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun BetaPill() {
    Text(
        "BETA",
        modifier = Modifier
            .background(BetaOrange, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

/**
 * The robot pill: which robot the app talks to (DEMO, or its address) and a connection light
 * (green answering, orange not). Its menu sets the address and switches DEMO on and off.
 */
@Composable
private fun RobotMenu(state: RemoteState, actions: RemoteActions, onOpenDemoControls: () -> Unit) {
    val status = RemoteTheme.status
    val onDemoChange = actions.onDemoChange
    var open by rememberSaveable { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (state.demo) status.demoContainer else Color.Transparent)
                .border(1.dp, if (state.demo) status.demo else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Robot and DEMO options") { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics {
                    contentDescription = (if (state.demo) "DEMO robot" else "Robot ${state.host}") +
                        if (state.connected) ", connected" else ", not connected"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (state.connected) status.good else status.offline, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.demo) "DEMO" else state.host,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (state.demo) FontWeight.Bold else FontWeight.Medium,
                fontFamily = if (state.demo) null else FontFamily.Monospace,
                color = if (state.demo) status.demo else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .width(272.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Robot")
                    ConnectionSection(state, actions.onPing)
                    val link = state.link
                    Text(
                        when {
                            state.connected -> "Connected"
                            link is Link.Disconnected && link.lastError != null -> "Not answering: ${link.lastError}"
                            else -> "Not connected yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.connected) status.good else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.demo) "DEMO is active" else "DEMO is deactivated",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Controls simulate robot events, so they only exist while DEMO is active.
                    if (state.demo) {
                        FilledIconButton(
                            onClick = {
                                open = false
                                onOpenDemoControls()
                            },
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = status.demoBold,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(painterResource(R.drawable.ic_tune), contentDescription = "DEMO controls", modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (state.demo) "Now operating a simulated robot. No data is sent."
                        else "Robot operation is live. Obey safety precautions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        buildAnnotatedString {
                            withLink(
                                LinkAnnotation.Url(
                                    DEMO_HELP_URL,
                                    TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)),
                                )
                            ) { append("Learn More") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { onDemoChange(!state.demo) },
                    enabled = !state.busy, // never switch robots mid-step
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (state.demo) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = status.demoBold, contentColor = Color.White),
                ) {
                    Text(if (state.demo) "Turn off DEMO" else "Turn on DEMO")
                }
                if (state.busy) {
                    Text(
                        "Wait for the current step to finish to switch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column {
                    Text(
                        "FIRMWARE",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.version ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private const val DEMO_HELP_URL = "https://github.com/aehils/uvc-remote-beta2#demo-mode"

/**
 * Under every page's title bar, pinned so it never scrolls away: the robot's readings, and the
 * runaway and UV banners.
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

/** A thin band above every page's title bar while DEMO is on. */
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
        is Uv.Countdown -> "UV LAMPS ON IN $left. MOVE AWAY FROM THE ROBOT."
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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                .height(76.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RemoteTheme.status.stop, contentColor = Color.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
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
        colors = CardDefaults.cardColors(containerColor = color, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ConnectionSection(state: RemoteState, onPing: (String) -> Unit) {
    val status = RemoteTheme.status
    val focus = LocalFocusManager.current
    var hostText by rememberSaveable { mutableStateOf(state.host) }
    val ping = {
        onPing(hostText)
        focus.clearFocus()
    }

    // Sits on the page background (no card): the Remote tab's header, not one panel among many.
    // The Ping icon doubles as the connection light: green when connected, orange when not.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AddressField(
            value = if (state.demo) "DEMO" else hostText,
            onValueChange = { hostText = it },
            enabled = !state.demo,
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
 * E-stop and motion badges, then velocity (the main read), on the right. With [onOpenMaps] (the Map tab): the robot's
 * mode and the map in use instead, the map's name opening the Maps page. Readings fade when not
 * connected, since they are stale. The battery is in the title bar.
 */
@Composable
private fun Telemetry(state: RemoteState, onOpenMaps: (() -> Unit)?) {
    val status = RemoteTheme.status
    val live = state.connected
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (live) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EStopBadge(state.estop)
        Spacer(Modifier.width(8.dp))
        MotionBadge(state)
        Spacer(Modifier.width(16.dp))

        if (onOpenMaps != null) {
            Text(
                when (state.mode) {
                    RobotMode.MAPPING -> "MAPPING"
                    RobotMode.EXTENDING -> "EXTENDING"
                    RobotMode.NAVIGATION -> "NAVIGATING"
                    null -> "—"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = if (state.mapping) status.good else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            MapChip(state, onOpenMaps, Modifier.widthIn(max = 200.dp))
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

/** The battery, for the title bar of every page. Quiet unless it needs attention; faded when stale. */
@Composable
internal fun BatteryReading(state: RemoteState) {
    val status = RemoteTheme.status
    val battery = state.battery
    val color = when {
        battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
        battery <= 10 -> status.danger
        battery <= 20 -> status.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.alpha(if (state.connected) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            battery?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
        Spacer(Modifier.width(5.dp))
        // Mirrored: the positive terminal on the left.
        Box(Modifier.scale(scaleX = -1f, scaleY = 1f)) { BatteryIcon(battery, color) }
    }
}

/** A small battery outline whose fill tracks the charge level. */
@Composable
private fun BatteryIcon(percent: Int?, color: Color) {
    Canvas(
        Modifier
            .size(width = 22.dp, height = 11.dp)
            .semantics { contentDescription = percent?.let { "Battery $it percent" } ?: "Battery unknown" }
    ) {
        val stroke = 1.3.dp.toPx()
        val tip = 2.dp.toPx()
        val body = Size(size.width - tip, size.height)
        val r = CornerRadius(2.dp.toPx())
        drawRoundRect(color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(body.width - stroke, body.height - stroke), cornerRadius = r, style = Stroke(stroke))
        drawRoundRect(color, topLeft = Offset(body.width, size.height * 0.3f), size = Size(tip, size.height * 0.4f), cornerRadius = CornerRadius(1.dp.toPx()))
        val inset = stroke + 1.dp.toPx()
        val level = (percent ?: 0).coerceIn(0, 100) / 100f
        drawRoundRect(color, topLeft = Offset(inset, inset), size = Size((body.width - inset * 2) * level, body.height - inset * 2), cornerRadius = CornerRadius(1.dp.toPx()))
    }
}

/** Rounds before formatting so sensor noise at rest reads 0, never "-0.00". */
private fun reading(value: Double, decimals: Int): String {
    val scale = Math.pow(10.0, decimals.toDouble())
    val rounded = Math.round(value * scale) / scale + 0.0 // + 0.0 turns -0.0 into 0.0
    return String.format(Locale.US, "%.${decimals}f", rounded)
}

/** A bounded "E": solid red when the e-stop is pressed, grey otherwise. Never green: released is not a "go" signal. */
@Composable
private fun EStopBadge(estop: EStop?) {
    val shape = RoundedCornerShape(8.dp)
    val pressed = estop == EStop.PRESSED
    val grey = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(44.dp)
            .background(if (pressed) RemoteTheme.status.stop else Color.Transparent, shape)
            .border(2.dp, if (pressed) RemoteTheme.status.stop else grey, shape)
            .semantics {
                contentDescription = when (estop) {
                    EStop.PRESSED -> "E-stop pressed"
                    EStop.RELEASED -> "E-stop released"
                    null -> "E-stop unknown"
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

/**
 * Beside the e-stop, a bare icon: flashes between amber and grey while a command runs (a step, a
 * held arrow, a trip, docking), and stays amber if the robot moves with none running (pushed, or
 * a task of its own). Grey when still.
 */
@Composable
private fun MotionBadge(state: RemoteState) {
    val warn = RemoteTheme.status.warn
    val grey = MaterialTheme.colorScheme.outline
    // Only animates while a command runs, so an idle robot costs no frames.
    val tint = if (state.busy) {
        val t by rememberInfiniteTransition(label = "motion").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
            label = "flash",
        )
        lerp(warn, grey, t)
    } else if (!state.still) warn else grey
    Icon(
        painterResource(R.drawable.ic_motion),
        contentDescription = when {
            state.busy -> "Running: ${state.busyLabel ?: "a command"}"
            !state.still -> "Robot moving"
            else -> "Robot still"
        },
        modifier = Modifier.size(34.dp),
        tint = tint,
    )
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

@Composable
private fun DriveCard(state: RemoteState, actions: RemoteActions) {
    Panel { DrivePad(state, actions) }
}

/**
 * The d-pad, pulled up from the bottom of every page but the Remote tab (where the pad is the
 * page). Only the handle takes the open and close gesture (a tap, or a flick up or down), so
 * the arrows keep their presses to themselves. Closing it mid-press lets go of the arrow, as
 * lifting the finger does. Closed, the handle still says why the pad is locked.
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
                    when {
                        !state.connected -> "Not connected"
                        state.docked -> "Docked"
                        state.estop == EStop.PRESSED -> "E-stop pressed"
                        state.uv != null || state.lampsOffOwed -> "Locked while the UV lamps may be on"
                        else -> ""
                    },
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

// ---------------------------------------------------------------------- points

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
                // Staff pick points by name alone, so the name has to say where the point is.
                Text(
                    "Name it so anyone can tell where this point is.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
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
internal fun TasksNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The arrows drive while held; Turn 180° in the middle is a fixed step. On the charging pile the
 * arrows are locked and the middle is UNDOCK instead: driving by hand starts with leaving the pile.
 */
@Composable
private fun DrivePad(state: RemoteState, actions: RemoteActions, buttonHeight: Dp = 92.dp) {
    val gap = Arrangement.spacedBy(if (buttonHeight < 80.dp) 8.dp else 10.dp)
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
    val shape = RoundedCornerShape(24.dp)
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
        shape = RoundedCornerShape(24.dp),
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
        shape = RoundedCornerShape(24.dp),
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
                    actions = {
                        BatteryReading(state)
                        Spacer(Modifier.width(16.dp))
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
private fun DemoToggle(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = RemoteTheme.status.demoBold),
        )
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
        SectionLabel("Activity · newest first")
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

@Preview(name = "Tasks, dark", heightDp = 1400)
@Composable
private fun PreviewTasks() = RemoteTheme(dark = true) {
    val state = previewState(demo = false, moving = false).copy(atPoint = PREVIEW_POINTS[0], tasks = listOf(PREVIEW_TASK))
    TasksTab(state, RemoteActions(), TaskPlan(listOf("Sink area", "Bed 2")), {}, Modifier.background(MaterialTheme.colorScheme.background))
}

private val PREVIEW_TASK = DisinfectionTask(
    "a", "Morning round", listOf(TaskStop("Bed 1", 5), TaskStop("Bed 2", 5), TaskStop("Sink area", 2)),
)

@Preview(name = "Tasks, a task under way", heightDp = 1100)
@Composable
private fun PreviewTaskRunning() = RemoteTheme(dark = false) {
    val state = previewState(demo = false, moving = false).copy(
        tasks = listOf(PREVIEW_TASK),
        taskRun = TaskRun(
            PREVIEW_TASK, round = 1, index = 1, stage = TaskStage.Disinfecting,
            results = listOf(StopResult(1, "Bed 1", StopOutcome.DONE, 300_000)),
        ),
        atPoint = PREVIEW_POINTS[1],
        uv = Uv.On("Bed 2", since = 0, until = 300_000),
        uvLeftMs = 192_000,
    )
    TasksTab(state, RemoteActions(), TaskPlan(), {}, Modifier.background(MaterialTheme.colorScheme.background))
}
