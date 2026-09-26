package com.expiation.reemanremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expiation.reemanremote.DemoStatus
import com.expiation.reemanremote.EStop
import com.expiation.reemanremote.Link
import com.expiation.reemanremote.Move
import com.expiation.reemanremote.R
import com.expiation.reemanremote.RemoteState
import com.expiation.reemanremote.Step
import com.expiation.reemanremote.StepPhase
import com.expiation.reemanremote.Tracking
import java.util.Locale

/** Everything the screen can ask for. */
data class RemoteActions(
    val onDemoChange: (Boolean) -> Unit = {},
    val onPing: (String) -> Unit = {},
    val onArmChange: (Boolean) -> Unit = {},
    val onMove: (Move) -> Unit = {},
    val onStop: () -> Unit = {},
    val onDemoEstop: () -> Unit = {},
    val onDemoWifi: () -> Unit = {},
    val onDemoBlock: () -> Unit = {},
    val onDemoBattery: () -> Unit = {},
    val onDemoReset: () -> Unit = {},
)

@Composable
fun RemoteScreen(state: RemoteState, actions: RemoteActions) {
    var showDemoControls by rememberSaveable { mutableStateOf(false) }
    // The controls page only exists in DEMO: leaving DEMO closes it.
    LaunchedEffect(state.demo) { if (!state.demo) showDemoControls = false }

    val demoStatus = state.demoStatus
    if (showDemoControls && demoStatus != null) {
        DemoControlsPage(state, demoStatus, actions, onBack = { showDemoControls = false })
    } else {
        MainPage(state, actions, onOpenDemoControls = { showDemoControls = true })
    }
}

@Composable
private fun MainPage(state: RemoteState, actions: RemoteActions, onOpenDemoControls: () -> Unit) {
    Scaffold(
        topBar = {
            Column {
                TopBar(state, actions.onDemoChange, onOpenDemoControls)
                // Pinned above the scrolling content so it can never be scrolled away.
                AnimatedVisibility(state.demo) { DemoStrip() }
            }
        },
        // STOP lives outside the scroll area so it is always on screen.
        bottomBar = { StopBar(actions.onStop) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RobotSection(state, actions.onPing)
            DriveCard(state, actions)
            ActivityLog(state.log)
            Spacer(Modifier.height(4.dp))
        }
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
private fun RobotSection(state: RemoteState, onPing: (String) -> Unit) {
    val status = RemoteTheme.status
    val focus = LocalFocusManager.current
    var hostText by rememberSaveable { mutableStateOf(state.host) }
    val ping = {
        onPing(hostText)
        focus.clearFocus()
    }

    // Sits on the page background (no card): this is the header of the screen, not one panel among many.
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                Icon(painterResource(R.drawable.ic_ping), contentDescription = "Ping", modifier = Modifier.size(22.dp))
            }
        }
        Text(
            if (state.connected) "CONNECTED" else "NOT CONNECTED",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            color = if (state.connected) status.good else status.danger,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Telemetry(state)
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
    val status = RemoteTheme.status
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Drive enabled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.armed) "Buttons are live. One step at a time." else "Clear the area, then switch on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.armed, onCheckedChange = actions.onArmChange)
        }

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

        DrivePad(state.canDrive, actions.onMove)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Faults")
            DemoToggle("E-stop pressed", "As if the physical e-stop were pressed. Driving is blocked; pressing mid-step stops the robot.", demo.estopPressed, actions.onDemoEstop)
            DemoToggle("Wi-Fi dropped", "The robot stops answering. NOT CONNECTED shows after about 3 missed polls.", demo.offline, actions.onDemoWifi)
            DemoToggle("Obstacle ahead", "The next step is accepted but never starts, so the app reports no motion.", demo.blockPending, actions.onDemoBlock)

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

@Composable
private fun ActivityLog(lines: List<String>) {
    Panel {
        Text("Activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(
                lines.joinToString("\n").ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------- helpers

/** The banner under the status tiles: why driving is blocked, most important first. */
// ---------------------------------------------------------------------- previews

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
    demoStatus = if (demo) DemoStatus(estopPressed = false, offline = false, blockPending = true) else null,
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
