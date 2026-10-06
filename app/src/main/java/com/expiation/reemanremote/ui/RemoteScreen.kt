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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import com.expiation.reemanremote.Step
import com.expiation.reemanremote.StepPhase
import com.expiation.reemanremote.Tracking
import com.expiation.reemanremote.Waypoint
import java.util.Locale

/** Everything the screen can ask for. */
data class RemoteActions(
    val onDemoChange: (Boolean) -> Unit = {},
    val onPing: (String) -> Unit = {},
    val onArmChange: (Boolean) -> Unit = {},
    val onMove: (Move) -> Unit = {},
    val onStop: () -> Unit = {},
    val onLoadPoints: () -> Unit = {},
    val onGoTo: (Waypoint) -> Unit = {},
    val onAddPoint: (String) -> Unit = {},
    val onGoToCharger: () -> Unit = {},
    val onDemoEstop: () -> Unit = {},
    val onDemoWifi: () -> Unit = {},
    val onDemoBlock: () -> Unit = {},
    val onDemoPerson: () -> Unit = {},
    val onDemoChargeOnItsOwn: () -> Unit = {},
    val onDemoBattery: () -> Unit = {},
    val onDemoReset: () -> Unit = {},
)

@Composable
fun RemoteScreen(state: RemoteState, actions: RemoteActions) {
    // Kept here, not in MainPage, so coming back from another page returns to the same tab.
    var tab by rememberSaveable { mutableStateOf(Tab.REMOTE) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    // The controls page only exists in DEMO: leaving DEMO closes it.
    LaunchedEffect(state.demo) { if (!state.demo && page == Page.DEMO_CONTROLS) page = Page.MAIN }

    val demoStatus = state.demoStatus
    when {
        page == Page.DEMO_CONTROLS && demoStatus != null ->
            DemoControlsPage(state, demoStatus, actions, onBack = { page = Page.MAIN })
        page == Page.POINTS -> PointsPage(state, actions, onBack = {
            // Safety: the points page has its own DRIVE ENABLED switch; leaving it disarms.
            if (state.armed) actions.onArmChange(false)
            page = Page.MAIN
        })
        else -> MainPage(
            state, actions, tab,
            onTabChange = { tab = it },
            onOpenDemoControls = { page = Page.DEMO_CONTROLS },
            onOpenPoints = {
                // Safety: the points page has its own DRIVE ENABLED switch, like a tab change.
                if (state.armed) actions.onArmChange(false)
                page = Page.POINTS
            },
        )
    }
}

/** Pages over the main (tabbed) page. Each keeps STOP and the robot's readings on screen. */
private enum class Page { MAIN, DEMO_CONTROLS, POINTS }

private enum class Tab(val label: String, val icon: Int) {
    REMOTE("Remote", R.drawable.ic_tab_remote),
    TASKS("Tasks", R.drawable.ic_tab_tasks),
    MAP("Map", R.drawable.ic_tab_map),
    ACTIVITY("Activity", R.drawable.ic_tab_activity),
}

/** Tabs that can move the robot. Each has its own DRIVE ENABLED switch (as do pages that can). */
private val DRIVE_TABS = setOf(Tab.REMOTE, Tab.TASKS)

/**
 * Layout, top to bottom: title bar, DEMO strip, the robot's readings (pinned, on every tab),
 * the current tab, and the nav bar with STOP in its centre so STOP is on every tab too.
 */
@Composable
private fun MainPage(
    state: RemoteState,
    actions: RemoteActions,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    onOpenDemoControls: () -> Unit,
    onOpenPoints: () -> Unit,
) {
    // Safety: every place that can move the robot has its own DRIVE ENABLED switch, and changing
    // tab always disarms (like leaving the app), so the switch that was turned on is on screen.
    val selectTab = { t: Tab ->
        if (t != tab && state.armed) actions.onArmChange(false)
        onTabChange(t)
    }
    LaunchedEffect(tab) { if (tab !in DRIVE_TABS && state.armed) actions.onArmChange(false) }

    Scaffold(
        topBar = {
            Column {
                TopBar(state, actions.onDemoChange, onOpenDemoControls)
                // Pinned above the scrolling content so it can never be scrolled away.
                AnimatedVisibility(state.demo) { DemoStrip() }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Telemetry(state) }
                AnimatedVisibility(state.runaway) { RunawayBanner() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = { NavBar(tab, selectTab, moving = state.busy || !state.still, actions.onStop) },
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
                ConnectionSection(state, actions.onPing)
                DriveCard(state, actions)
            }
            Tab.TASKS -> TasksTab(state, actions, onOpenPoints, content)
            Tab.MAP -> ComingSoon(
                content, R.drawable.ic_tab_map, "Map",
                "See the robot on its map, build and switch maps, and set its position. Not in this build yet.",
            )
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

@Composable
private fun ComingSoon(modifier: Modifier, icon: Int, title: String, body: String) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "COMING SOON",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------- chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(state: RemoteState, onDemoChange: (Boolean) -> Unit, onOpenDemoControls: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Robot Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                BetaPill()
            }
        },
        actions = {
            DemoMenu(state, onDemoChange, onOpenDemoControls)
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

/** DEMO pill with a status dot; tapping it opens a menu that explains and toggles demo mode. */
@Composable
private fun DemoMenu(state: RemoteState, onDemoChange: (Boolean) -> Unit, onOpenDemoControls: () -> Unit) {
    val status = RemoteTheme.status
    var open by rememberSaveable { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (state.demo) status.demoContainer else Color.Transparent)
                .border(1.dp, if (state.demo) status.demo else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "DEMO options") { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (state.demo) status.demo else MaterialTheme.colorScheme.outline, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "DEMO",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (state.demo) status.demo else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .width(208.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
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
                                    DEMO_LIMITS_URL,
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

private const val DEMO_LIMITS_URL = "https://github.com/aehils/uvc-remote-beta2#demo-mode-limitations"

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

@Composable
private fun StopBar(onStop: () -> Unit) {
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
private fun Panel(
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

/** E-stop badge, then velocity (the main read), then a quiet battery figure. Readings fade when not connected, since they are stale. */
@Composable
private fun Telemetry(state: RemoteState) {
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

        // Velocity is the main read: centred between the badge and the battery, in fixed-width
        // slots so the figures never shift as they change.
        val motion = if (state.busy) status.warn else MaterialTheme.colorScheme.onSurface
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            Reading("Linear", if (live) reading(state.vx, 1) else "—", "m/s", motion, Modifier.width(104.dp))
            Reading("Angular", if (live) reading(Math.toDegrees(state.vth), 0) else "—", "°/s", motion, Modifier.width(96.dp))
        }

        val battery = state.battery
        // Quiet unless it needs attention.
        val batteryColor = when {
            battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
            battery <= 10 -> status.danger
            battery <= 20 -> status.warn
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BatteryIcon(battery, batteryColor)
            Spacer(Modifier.height(3.dp))
            Text(
                battery?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = batteryColor,
            )
        }
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
        ArmRow(state.armed, actions.onArmChange)
        BusyStatus(state)
        DrivePad(state.canDrive, actions.onMove)
    }
}

@Composable
private fun ArmRow(armed: Boolean, onArmChange: (Boolean) -> Unit) {
    // The whole row is the touch target, so the switch itself can be drawn smaller.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = armed, role = Role.Switch, onValueChange = onArmChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "DRIVE ENABLED",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        // A Switch is a fixed 52 x 32 dp; scaled down here and boxed at the scaled size.
        Box(Modifier.size(width = 42.dp, height = 26.dp), contentAlignment = Alignment.Center) {
            Switch(checked = armed, onCheckedChange = null, modifier = Modifier.scale(0.8f))
        }
    }
}

/** While a step runs: a progress bar and what the robot is doing. */
@Composable
private fun BusyStatus(state: RemoteState) {
    val status = RemoteTheme.status
    AnimatedVisibility(state.busy) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = status.warn,
                trackColor = status.warnContainer,
            )
            Text(
                (if (state.phase is StepPhase.Stopping) "Stopping: " else "Moving: ") + (state.busyLabel ?: "") + " …",
                style = MaterialTheme.typography.labelLarge,
                color = status.warn,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------------------------------------------------------------------- tasks

/**
 * The Tasks tab: GO TO CHARGER, which moves the robot (so the tab has its own DRIVE ENABLED
 * switch), and a list of tasks, each opening its own page.
 */
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
        Panel {
            ArmRow(state.armed, actions.onArmChange)
            BusyStatus(state)
        }
        ChargerButton(state.canDrive, actions.onGoToCharger)
        TasksNote(
            "Docks straight away if the charging pile is close (within ${DriveController.DOCK_NEAR_M} m on the map); " +
                "otherwise drives there on the robot's own route first, then docks."
        )

        SectionLabel("Tasks")
        TaskItem(
            icon = R.drawable.ic_tab_tasks,
            title = "Disinfection points",
            detail = when {
                charging && count != null -> "$count saved. Send the robot to one, or add one."
                charging -> "Send the robot to a saved point, or add one where it stands."
                goal != null -> "On the way to ${goal.name}"
                count != null -> "$count saved. Send the robot to one, or add one."
                else -> "Send the robot to a saved point, or add one where it stands."
            },
            highlight = goal != null && !charging,
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
 * Disinfection points: send the robot to one, or save where it stands as a new one.
 * GO moves the robot, so this page has its own DRIVE ENABLED switch (switched off on leaving),
 * and, like the DEMO controls page, keeps STOP and the robot's readings on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointsPage(state: RemoteState, actions: RemoteActions, onBack: () -> Unit) {
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
                    title = { Text("Disinfection points", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        TextButton(
                            onClick = actions.onLoadPoints,
                            enabled = state.connected && points != Points.Loading,
                        ) { Text("Refresh") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                AnimatedVisibility(state.demo) { DemoStrip() }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Telemetry(state) }
                AnimatedVisibility(state.runaway) { RunawayBanner() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            Panel {
                ArmRow(state.armed, actions.onArmChange)
                BusyStatus(state)
            }

            SectionLabel("Send the robot to")
            when {
                points is Points.Loaded && points.list.isNotEmpty() -> {
                    val goal = state.running?.goal
                    points.list.forEach { PointRow(it, goingHere = it == goal, state.canDrive, actions.onGoTo) }
                }
                points is Points.Loaded -> TasksNote("No disinfection points are saved on this robot's map yet. Add one below.")
                points is Points.Failed -> TasksNote("Couldn't read the points: ${points.error}")
                points == Points.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                !state.connected -> TasksNote("Connect to the robot to see its points.")
            }
            TasksNote(
                "GO sends the robot to the point on its own: it plans its route and speed. " +
                    "STOP cancels the trip. This only drives there; it does not switch on the UV lamps."
            )

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
            TasksNote(
                state.pointMessage ?: when {
                    !state.connected -> "Connect to the robot first."
                    state.busy || !state.still -> "The robot must be standing still."
                    else -> "Saves the spot the robot is on, and the way it faces, as a new point on its map."
                }
            )
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

@Composable
private fun AddPointDialog(taken: Set<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
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
private fun PointRow(point: Waypoint, goingHere: Boolean, canDrive: Boolean, onGoTo: (Waypoint) -> Unit) {
    val status = RemoteTheme.status
    Panel(color = if (goingHere) status.warnContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(point.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
private fun TasksNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** GO TO CHARGER. It moves the robot, so it is held to the drive pad's interlock. */
@Composable
private fun ChargerButton(enabled: Boolean, onDock: () -> Unit) {
    FilledTonalButton(
        onClick = onDock,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_dock), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("GO TO CHARGER", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun DrivePad(enabled: Boolean, onMove: (Move) -> Unit) {
    val gap = Arrangement.spacedBy(10.dp)
    Column(verticalArrangement = gap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Spacer(Modifier.weight(1f))
            PadButton(Move.FORWARD, "▲", enabled, onMove)
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            PadButton(Move.LEFT, "↺", enabled, onMove)
            PadButton(Move.AROUND, "↩", enabled, onMove, tonal = true)
            PadButton(Move.RIGHT, "↻", enabled, onMove)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Spacer(Modifier.weight(1f))
            PadButton(Move.BACK, "▼", enabled, onMove)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.PadButton(move: Move, glyph: String, enabled: Boolean, onMove: (Move) -> Unit, tonal: Boolean = false) {
    Button(
        onClick = { onMove(move) },
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(92.dp),
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
private fun DemoControlsPage(state: RemoteState, demo: DemoStatus, actions: RemoteActions, onBack: () -> Unit) {
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
                DemoStrip()
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Telemetry(state)
            AnimatedVisibility(state.runaway) { RunawayBanner() }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
            Text(
                "As if the robot started a trip to the pile on its own. STOP must end it for good, not just pause it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                actions.onDemoPerson,
                Modifier.fillMaxWidth(),
                enabled = demo.navigating,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Person in the way") }
            Text(
                "During a trip to a point (Tasks tab): the robot waits 5 s, then carries on. The trip stays locked while it waits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Robot")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(actions.onDemoBattery, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Battery −10%") }
                OutlinedButton(actions.onDemoReset, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Reset robot") }
            }
            Text(
                "Battery wraps back to 100% below zero. Reset puts the robot back in the room centre and clears all faults.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
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

// ---------------------------------------------------------------------- helpers

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
    version = "RSNF1-v5.1.12_01",
    battery = 18,
    vx = if (moving) 0.27 else 0.0,
    vth = 0.0,
    armed = true,
    phase = if (moving) StepPhase.Stepping(Step("Forward 0.5 m", "/cmd/move", "{}", false, 4000), Tracking(0, 0))
    else StepPhase.Idle,
    points = Points.Loaded(PREVIEW_POINTS),
    addingPoint = false,
    pointMessage = null,
    runaway = false,
    demoStatus = if (demo) DemoStatus(estopPressed = false, offline = false, blockPending = true, navigating = false, robotBusy = moving) else null,
    log = listOf(
        "12:04:10  [DEMO] Forward 0.5 m: accepted {}",
        "12:04:10  [DEMO] Forward 0.5 m: sending /cmd/move {\"distance\":50,\"direction\":1,\"speed\":0.30}",
        "12:04:02  [DEMO] Drive enabled",
    ),
)

@Preview(name = "Demo, moving", heightDp = 1500)
@Composable
private fun PreviewDemo() = RemoteTheme(dark = false) { RemoteScreen(previewState(demo = true, moving = true), RemoteActions()) }

@Preview(name = "Live, dark", heightDp = 1300)
@Composable
private fun PreviewLiveDark() = RemoteTheme(dark = true) { RemoteScreen(previewState(demo = false, moving = false), RemoteActions()) }

@Preview(name = "Disinfection points, going to one", heightDp = 1100)
@Composable
private fun PreviewPoints() = RemoteTheme(dark = false) {
    val goal = PREVIEW_POINTS[1]
    val state = previewState(demo = true, moving = true).copy(
        phase = StepPhase.Stepping(Step("Go to ${goal.name}", "/cmd/nav_name", "{}", false, 60_000, goal = goal), Tracking(0, 0)),
    )
    PointsPage(state, RemoteActions(), onBack = {})
}

@Preview(name = "Tasks, dark", heightDp = 500)
@Composable
private fun PreviewTasks() = RemoteTheme(dark = true) {
    TasksTab(previewState(demo = false, moving = false), RemoteActions(), {}, Modifier.background(MaterialTheme.colorScheme.background))
}
