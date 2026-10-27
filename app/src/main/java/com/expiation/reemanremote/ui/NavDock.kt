package com.expiation.reemanremote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One button of the dock: an icon over a short label. [prominent] is the one thing to do next
 * (save, confirm), filled with the accent. [menu] is drawn anchored to the button, for a
 * DropdownMenu it opens.
 */
internal class DockItem(
    val icon: Int,
    val label: String,
    val description: String = label,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val prominent: Boolean = false,
    val isTab: Boolean = false,
    val menu: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/** The dock's left tile, set apart from the rest: the Map page's way in, and, [active], its way out. */
internal class DockModule(
    val icon: Int,
    val label: String,
    val description: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

/** STOP in the dock's bar, between its [start] and [end] buttons. */
internal class DockStop(val moving: Boolean, val onStop: () -> Unit)

/** What the bar carries: buttons, with STOP between them or not. */
private class DockBar(val start: List<DockItem>, val stop: DockStop?, val end: List<DockItem>) {
    // Names the set, so a new set animates in and the same buttons in a new state don't.
    val key get() = (start.map { it.label } + listOfNotNull(stop?.let { "STOP" }) + end.map { it.label }).joinToString("|")
}

/**
 * The bottom of the main page: the module tile on the left, then the bar. Every slot of the bar
 * is the same width, STOP's too, so with STOP it sits in the middle of the bar. On the Map page
 * STOP is in the middle of the drive pad instead, and the bar has [stop] null.
 */
@Composable
internal fun NavDock(module: DockModule, start: List<DockItem>, end: List<DockItem>, stop: DockStop?) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = DOCK_GUTTER, end = DOCK_GUTTER, top = 8.dp, bottom = 12.dp)
                .height(DOCK_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(MODULE_GAP),
        ) {
            ModuleTile(module, Modifier.width(MODULE_WIDTH).fillMaxHeight())
            DockSlots(
                DockBar(start, stop, end),
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .dockSurface(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

/** The dock's look: flat, a fill and a hairline, nothing raised. */
@Composable
private fun Modifier.dockSurface(color: Color): Modifier = this
    .clip(DOCK_SHAPE)
    .background(color)
    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, DOCK_SHAPE)

/** The bar's slots, all one width. */
@Composable
private fun DockSlots(bar: DockBar, modifier: Modifier) {
    // Animated by the set's name, not the bar itself: the same buttons in a new state (a tab
    // selected, a button enabled) must not fade in again on every frame the readings change.
    val key = bar.key
    val latest by rememberUpdatedState(bar)
    val last = remember { HashMap<String, DockBar>() } // for the set fading out
    last[key] = bar
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(200, delayMillis = 60)).togetherWith(fadeOut(tween(90))) },
        label = "dock",
    ) { shown ->
        val b = latest.takeIf { it.key == shown } ?: last[shown] ?: return@AnimatedContent
        Row(
            Modifier
                .fillMaxSize()
                .padding(DOCK_INSET),
            horizontalArrangement = Arrangement.spacedBy(DOCK_INSET),
        ) {
            b.start.forEach { DockButton(it, Modifier.weight(1f)) }
            b.stop?.let { StopKey(it.moving, it.onStop, Modifier.weight(1f).fillMaxHeight()) }
            b.end.forEach { DockButton(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DockButton(item: DockItem, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val faded = colors.onSurface.copy(alpha = 0.38f)
    val fill by animateColorAsState(
        when {
            item.prominent && item.enabled -> colors.primary
            item.prominent -> colors.onSurface.copy(alpha = 0.08f)
            item.selected -> colors.secondaryContainer
            else -> Color.Transparent
        },
        label = "fill",
    )
    val ink by animateColorAsState(
        when {
            !item.enabled -> faded
            item.prominent -> colors.onPrimary
            item.selected -> colors.onSecondaryContainer
            else -> colors.onSurfaceVariant
        },
        label = "ink",
    )
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        // Selected or prominent, the whole slot fills, the same shape as STOP beside it.
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(INNER_SHAPE)
                .background(fill)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = item.enabled,
                    role = if (item.isTab) Role.Tab else Role.Button,
                    onClick = item.onClick,
                )
                .semantics {
                    // The label is read anyway; say more only where it needs it.
                    if (item.description != item.label) contentDescription = item.description
                    if (item.isTab) selected = item.selected
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.height(4.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (item.selected || item.prominent) FontWeight.SemiBold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.menu?.invoke()
    }
}

/**
 * The left tile. Closed, it is the way into the Map page, tinted with the accent so it reads as
 * a place to go, not one tab among others. Open, it turns plain and becomes the way out.
 */
@Composable
private fun ModuleTile(module: DockModule, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val container by animateColorAsState(if (module.active) colors.surface else colors.primaryContainer, tween(220), label = "module")
    val ink by animateColorAsState(if (module.active) colors.onSurface else colors.onPrimaryContainer, tween(220), label = "moduleInk")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .dockSurface(container)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = module.onClick,
            )
            .semantics { contentDescription = module.description },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = module.icon to module.label,
            transitionSpec = { fadeIn(tween(200, delayMillis = 60)).togetherWith(fadeOut(tween(90))) },
            label = "moduleContent",
        ) { (icon, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * STOP: in the dock's bar, or in the middle of the drive pad on the Map page. Flat red, filling
 * its slot exactly, so it lines up with the buttons beside it. While the robot moves a white
 * outline pulses inside its edge, so it is found fastest then. A firm haptic tick confirms the press.
 */
@Composable
internal fun StopKey(
    moving: Boolean,
    onStop: () -> Unit,
    modifier: Modifier,
    shape: Shape = INNER_SHAPE,
    fontSize: TextUnit = 15.sp,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    // Only animates while moving, so an idle robot costs no frames.
    val ring = if (moving) {
        val pulse by rememberInfiniteTransition(label = "stop").animateFloat(
            initialValue = 0.15f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "ring",
        )
        pulse
    } else 0f
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(RemoteTheme.status.stop)
            .border(2.dp, Color.White.copy(alpha = ring), shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "Stop the robot",
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStop()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("STOP", color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}

private val DOCK_HEIGHT = 72.dp
private val DOCK_GUTTER = 12.dp
private val MODULE_WIDTH = 64.dp
private val MODULE_GAP = 8.dp
private val DOCK_INSET = 6.dp
// The app's card radius outside; inside, the outer radius less the inset, so the corners nest.
private val DOCK_SHAPE = RoundedCornerShape(14.dp)
private val INNER_SHAPE = RoundedCornerShape(8.dp)
