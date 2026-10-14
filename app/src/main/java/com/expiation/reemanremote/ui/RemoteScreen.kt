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
)

@Composable
fun RemoteScreen(state: RemoteState, actions: RemoteActions) {
    // Kept here, not in MainPage, so coming back from another page returns to the same tab.
    var tab by rememberSaveable { mutableStateOf(Tab.REMOTE) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    // The drive sheet stays open or closed across tabs and pages.
    var driveOpen by rememberSaveable { mutableStateOf(false) }
    // The controls page only exists in DEMO: leaving DEMO closes it.
    LaunchedEffect(state.demo) { if (!state.demo && page == Page.DEMO_CONTROLS) page = Page.MAIN }
    // Mapping is driving: the pad comes up by itself.
    LaunchedEffect(state.mapping) { if (state.mapping) driveOpen = true }

    val drive = DriveSheetState(driveOpen) { driveOpen = it }
    val demoStatus = state.demoStatus
    when {
        page == Page.DEMO_CONTROLS && demoStatus != null ->
            DemoControlsPage(state, demoStatus, actions, drive, onBack = { page = Page.MAIN })
        page == Page.POINTS -> PointsPage(state, actions, drive, onBack = { page = Page.MAIN })
        page == Page.MAPS -> MapsPage(
            state, actions, drive,
            onBack = { page = Page.MAIN },
            onShowMap = {
                page = Page.MAIN
                tab = Tab.MAP
            },
        )
        else -> MainPage(
            state, actions, tab, drive,
            onTabChange = { tab = it },
            onOpenDemoControls = { page = Page.DEMO_CONTROLS },
            onOpenPoints = { page = Page.POINTS },
            onOpenMaps = { page = Page.MAPS },
        )
    }
}

/** Pages over the main (tabbed) page. Each keeps STOP, the drive sheet and the robot's readings on screen. */
private enum class Page { MAIN, DEMO_CONTROLS, POINTS, MAPS }

/** Whether the drive sheet is pulled up, and how to change that. */
internal class DriveSheetState(val open: Boolean, val onOpenChange: (Boolean) -> Unit)

private enum class Tab(val label: String, val icon: Int) {
    REMOTE("Remote", R.drawable.ic_tab_remote),
    TASKS("Tasks", R.drawable.ic_tab_tasks),
    MAP("Map", R.drawable.ic_tab_map),
    ACTIVITY("Activity", R.drawable.ic_tab_activity),
}

/**
 * Layout, top to bottom: title bar, DEMO strip, the robot's readings (pinned, on every tab),
 * the current tab, and the nav bar with STOP in its centre so STOP is on every tab too.
 */
@Composable
private fun MainPage(
    state: RemoteState,
    actions: RemoteActions,
    tab: Tab,
    drive: DriveSheetState,
    onTabChange: (Tab) -> Unit,
    onOpenDemoControls: () -> Unit,
    onOpenPoints: () -> Unit,
    onOpenMaps: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopBar(state, actions, onOpenDemoControls)
                // On the Map tab the readings show the robot's mode and the map, not its speed.
                StatusHeader(state, onOpenMaps = onOpenMaps.takeIf { tab == Tab.MAP })
            }
        },
        bottomBar = {
            Column {
                // On the Remote tab the pad is the page itself.
                if (tab != Tab.REMOTE) DriveSheet(state, actions, drive)
                NavBar(tab, onTabChange, moving = state.busy || !state.still, actions.onStop)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val content = Modifier
            .padding(padding)
            .fillMaxSize()
        when (tab) {
            Tab.REMOTE -> Column(
                content
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DriveCard(state, actions)
            }
            Tab.TASKS -> TasksTab(state, actions, onOpenPoints, content)
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
            .background(BetaOrange.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
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
 * Under every page's title bar, pinned so it never scrolls away: the DEMO strip, the robot's
 * readings, and the runaway and UV banners.
 */
@Composable
internal fun StatusHeader(state: RemoteState, onOpenMaps: (() -> Unit)? = null) {
    AnimatedVisibility(state.demo) { DemoStrip() }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Telemetry(state, onOpenMaps) }
    AnimatedVisibility(state.runaway) { RunawayBanner() }
    AnimatedVisibility(state.uv != null || state.lampsOffOwed) { UvBanner(state) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DemoStrip() {
    Text(
        "DEMO MODE IS ON",
        modifier = Modifier
            .fillMaxWidth()
            .background(RemoteTheme.status.demoBold)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Light,
        letterSpacing = 1.sp,
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
            .background(RemoteTheme.status.danger)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
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
        colors = CardDefaults.cardColors(containerColor = color),
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
 * E-stop badge, then velocity (the main read). With [onOpenMaps] (the Map tab): the robot's
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
            )
            Spacer(Modifier.width(8.dp))
            MapChip(state, onOpenMaps, Modifier.widthIn(max = 200.dp))
        } else {
            // Velocity is the main read, in fixed-width slots so the figures never shift as they change.
            val motion = if (state.busy) status.warn else MaterialTheme.colorScheme.onSurface
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                Reading("Linear", if (live) reading(state.vx, 1) else "—", "m/s", motion, Modifier.width(104.dp))
                Reading("Angular", if (live) reading(Math.toDegrees(state.vth), 0) else "—", "°/s", motion, Modifier.width(96.dp))
            }
            Spacer(Modifier.width(60.dp)) // keeps the speeds centred under the title bar as before
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
            .background(if (pressed) RemoteTheme.status.danger else Color.Transparent, shape)
            .border(2.dp, if (pressed) RemoteTheme.status.danger else grey, shape)
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

@Composable
private fun Reading(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
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
    Panel {
        BusyStatus(state)
        DrivePad(state, actions)
    }
}

/** While a step runs: a progress bar and what the robot is doing. */
@Composable
internal fun BusyStatus(state: RemoteState) {
    val status = RemoteTheme.status
    AnimatedVisibility(state.busy) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = status.warn,
                trackColor = status.warnContainer,
            )
            Text(
                busyText(state),
                style = MaterialTheme.typography.labelLarge,
                color = status.warn,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun busyText(state: RemoteState) = if (state.undocking && state.phase is StepPhase.Stepping) "Undocking …" else when (state.phase) {
    is StepPhase.Stopping -> "Stopping: "
    is StepPhase.Holding -> "Driving: "
    else -> "Moving: "
} + (state.busyLabel ?: "") + " …"

/**
 * The d-pad, pulled up from the bottom of every page but the Remote tab (where the pad is the
 * page). Only the handle takes the open and close gesture (a tap, or a flick up or down), so
 * the arrows keep their presses to themselves. Closing it mid-press lets go of the arrow, as
 * lifting the finger does. Closed, the handle still says what the robot is doing.
 */
@Composable
internal fun DriveSheet(state: RemoteState, actions: RemoteActions, sheet: DriveSheetState) {
    val status = RemoteTheme.status
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
                        state.busy -> busyText(state)
                        !state.connected -> "Not connected"
                        state.docked -> "Docked"
                        state.estop == EStop.PRESSED -> "E-stop pressed"
                        state.uv != null || state.lampsOffOwed -> "Locked while the UV lamps may be on"
                        else -> ""
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.busy) status.warn else MaterialTheme.colorScheme.onSurfaceVariant,
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

// ---------------------------------------------------------------------- tasks

/** The Tasks tab: GO TO CHARGER (UNDOCK while docked), and a list of tasks, each opening its own page. */
@Composable
private fun TasksTab(state: RemoteState, actions: RemoteActions, onOpenPoints: () -> Unit, modifier: Modifier) {
    val goal = state.running?.goal
    val charging = goal?.name == "charging_pile"
    val count = (state.points as? Points.Loaded)?.list?.size
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(state.busy) { Panel { BusyStatus(state) } }
        if (state.mapping) TasksNote("The robot is mapping. Trips and disinfection wait until the map is saved or discarded on the Map tab.")
        if (state.docked) ChargerButton("UNDOCK", state.canDrive, actions.onUndock)
        else ChargerButton("GO TO CHARGER", state.canNavigate, actions.onGoToCharger)

        SectionLabel("Tasks")
        val uv = state.uv
        TaskItem(
            icon = R.drawable.ic_tab_tasks,
            title = "Disinfection Points",
            detail = when {
                uv != null || state.lampsOffOwed -> uvStatus(state)
                goal != null && !charging -> "On the way to ${goal.name}"
                count != null -> "$count saved. Send the robot to one, or add one."
                else -> "Send the robot to a saved point, or add one where it stands."
            },
            highlight = (goal != null && !charging) || uv != null || state.lampsOffOwed,
            onClick = onOpenPoints,
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
                        BatteryReading(state)
                        TextButton(
                            onClick = actions.onLoadPoints,
                            enabled = state.connected && points != Points.Loading,
                        ) { Text("Refresh") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                StatusHeader(state)
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
                AnimatedVisibility(state.busy) { Panel { BusyStatus(state) } }

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
        val taken = (points as? Points.Loaded)?.list.orEmpty().map { it.name }.toSet() + "charging_pile"
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
private val UV_MINUTE_STEPS = (1..4) + (5..DriveController.MAX_UV_MINUTES step 5)
private val UV_DELAYS = listOf(DriveController.MIN_UV_DELAY_S, 30, 60)

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
private fun DrivePad(state: RemoteState, actions: RemoteActions, buttonHeight: Dp = 92.dp) {
    val gap = Arrangement.spacedBy(if (buttonHeight < 80.dp) 8.dp else 10.dp)
    Column(verticalArrangement = gap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Spacer(Modifier.weight(1f))
            HoldButton(Move.FORWARD, "▲", state, actions, buttonHeight)
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            HoldButton(Move.LEFT, "↺", state, actions, buttonHeight)
            if (state.docked || state.undocking) UndockPadButton(state, actions.onUndock, buttonHeight)
            else PadButton(Move.AROUND, "↩", state.canDriveByHand, actions.onMove, buttonHeight, tonal = true)
            HoldButton(Move.RIGHT, "↻", state, actions, buttonHeight)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, fontSize = 26.sp, color = content)
            Text(move.word, style = MaterialTheme.typography.labelLarge, color = content, textAlign = TextAlign.Center)
        }
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
private fun RowScope.PadButton(move: Move, glyph: String, enabled: Boolean, onMove: (Move) -> Unit, height: Dp, tonal: Boolean = false) {
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
            Text(move.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
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
                StatusHeader(state)
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
    TasksTab(previewState(demo = false, moving = false), RemoteActions(), {}, Modifier.background(MaterialTheme.colorScheme.background))
}
