package com.expiation.reemanremote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/**
 * One button of the dock: an icon over a short label. [selected] shows as a tinted icon and a
 * short bar under the label. [prominent] (the Map tile, a tool's tick) is tinted with the accent,
 * never solid: STOP is the dock's only solid fill. [menu] is drawn anchored to the button, for a
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

/** STOP, in the middle of the dock's bar. */
internal class DockStop(val moving: Boolean, val onStop: () -> Unit)

/**
 * The bottom of the main page: the module (the Map page's way in, and on it, its way out) in a
 * container of its own, then the bar, with STOP in its middle. Every button in both is the same
 * size and shape; the module stands apart only by the gap and its tint. STOP is the one solid
 * fill, never narrower than [STOP_MIN_WIDTH].
 */
@Composable
internal fun NavDock(module: DockItem, start: List<DockItem>, end: List<DockItem>, stop: DockStop) {
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = DOCK_GUTTER, end = DOCK_GUTTER, top = 8.dp, bottom = 12.dp)
        ) {
            // One width for every button, the module's included: what's left once the gap and
            // the containers' insets come off, shared by the bar's buttons and the module's.
            val buttons = start.size + 1 + end.size
            val free = maxWidth - MODULE_GAP - DOCK_INSET * (buttons + 3)
            val even = free / (buttons + 1)
            val stopWidth = max(even, STOP_MIN_WIDTH)
            val slot = if (stopWidth > even) (free - stopWidth) / buttons else even
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DOCK_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(MODULE_GAP),
            ) {
                DockContainer(Modifier.width(slot + DOCK_INSET * 2)) {
                    DockButton(module, Modifier.weight(1f))
                }
                DockContainer(Modifier.weight(1f)) {
                    start.forEach { DockButton(it, Modifier.weight(1f)) }
                    StopKey(stop, Modifier.width(stopWidth))
                    end.forEach { DockButton(it, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A flat container: the app's card corner, a hairline, and its buttons inset evenly inside it. */
@Composable
private fun DockContainer(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxHeight()
            .clip(DOCK_SHAPE)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, DOCK_SHAPE)
            .padding(DOCK_INSET),
        horizontalArrangement = Arrangement.spacedBy(DOCK_INSET),
        content = content,
    )
}

@Composable
private fun DockButton(item: DockItem, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val faded = colors.onSurface.copy(alpha = 0.38f)
    val fill = when {
        item.prominent && item.enabled -> colors.primaryContainer
        item.prominent -> colors.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val iconInk = when {
        !item.enabled -> faded
        item.prominent -> colors.onPrimaryContainer
        item.selected -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val labelInk = when {
        !item.enabled -> faded
        item.prominent -> colors.onPrimaryContainer
        item.selected -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
    Box(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(INNER_SHAPE)
                .background(fill)
                .clickable(
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
            // The marker's room is kept when it's hidden, so every icon sits at the same height.
            Spacer(Modifier.height(MARKER_HEIGHT + 4.dp))
            Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = iconInk)
            Spacer(Modifier.height(4.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (item.selected || item.prominent) FontWeight.Bold else FontWeight.Medium,
                color = labelInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(width = 16.dp, height = MARKER_HEIGHT)
                    .background(if (item.selected) colors.primary else Color.Transparent, RoundedCornerShape(2.dp))
            )
        }
        item.menu?.invoke()
    }
}

/**
 * STOP: the dock's one solid fill, in the middle of the bar on every page of the main one. While
 * the robot moves a white outline pulses inside its edge, so it is found fastest then. A firm
 * haptic tick confirms the press.
 */
@Composable
private fun StopKey(stop: DockStop, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    // Only animates while moving, so an idle robot costs no frames.
    val ring = if (stop.moving) {
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
            .fillMaxHeight()
            .clip(INNER_SHAPE)
            .background(RemoteTheme.status.stop)
            .border(2.dp, Color.White.copy(alpha = ring), INNER_SHAPE)
            .clickable(role = Role.Button, onClickLabel = "Stop the robot") {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                stop.onStop()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("STOP", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}

private val DOCK_HEIGHT = 72.dp // buttons 60dp tall inside
private val DOCK_GUTTER = 12.dp
private val MODULE_GAP = 8.dp
private val DOCK_INSET = 6.dp
private val MARKER_HEIGHT = 3.dp
private val STOP_MIN_WIDTH = 72.dp
// The app's card radius outside; inside, the outer radius less the inset, so the corners nest.
private val DOCK_SHAPE = RoundedCornerShape(14.dp)
private val INNER_SHAPE = RoundedCornerShape(8.dp)
