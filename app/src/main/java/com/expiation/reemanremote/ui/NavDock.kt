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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/**
 * One button of the dock: an icon over a short label. [selected] means "this is what's open":
 * the tab whose page is on screen, or Drive while its pad is. It swaps the outline icon for its
 * filled twin, [selectedIcon], with the label in bold, both in the accent; a tab also gets an
 * accent bar on the dock's top edge above it, the edge toward its page (see [drawEdgeMark]).
 * [prominent] (a tool's tick) is tinted with the accent, never solid: STOP is the dock's only
 * solid fill. [accent] colours the icon and the label, for Exit. [menu] is drawn anchored to the
 * button, for a DropdownMenu it opens.
 */
internal class DockItem(
    val icon: Int,
    val label: String,
    val description: String = label,
    val selectedIcon: Int = icon,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val prominent: Boolean = false,
    val isTab: Boolean = false,
    val accent: Color? = null,
    val menu: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/** STOP, in the middle of the dock's bar. */
internal class DockStop(val moving: Boolean, val onStop: () -> Unit)

/**
 * The bottom of the main page: the module (the Map page's way in, and on it, its way out) in a
 * container of its own, then the bar, with STOP in its middle. Every button in both is the same
 * size and shape; the module stands apart only by the gap. STOP is the one solid fill, never
 * narrower than [STOP_MIN_WIDTH].
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
            val widths = dockWidths(maxWidth, start.size + 1 + end.size)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DOCK_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(MODULE_GAP),
            ) {
                DockContainer(Modifier.width(widths.module)) {
                    DockButton(module, Modifier.weight(1f))
                }
                DockContainer(Modifier.weight(1f)) {
                    BarButtons(start, stop, end, widths)
                }
            }
        }
    }
}

/**
 * The Map page: one piece, the map card on top running straight down into the bar, with the
 * bottom left cut out for the module (Exit), which keeps a container of its own. The bar and
 * Exit sit exactly where the dock sits on the tabs, so opening the map reads as the card coming
 * down over the bar. [content] (the map, and the pad) fills the card, inset like the buttons.
 */
@Composable
internal fun JoinedDock(
    module: DockItem,
    start: List<DockItem>,
    end: List<DockItem>,
    stop: DockStop,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = DOCK_GUTTER, end = DOCK_GUTTER, top = 10.dp, bottom = 12.dp)
    ) {
        val widths = dockWidths(maxWidth, start.size + 1 + end.size)
        // The cut-out: the module and the gap beside and above it.
        val piece = NotchedShape(notchWidth = widths.module + MODULE_GAP, notchHeight = DOCK_HEIGHT + MODULE_GAP)
        Column(
            Modifier
                .fillMaxSize()
                .clip(piece)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, piece)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(DOCK_INSET)
            ) { content() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DOCK_HEIGHT + MODULE_GAP)
            ) {
                Spacer(Modifier.width(widths.module + MODULE_GAP))
                // Inset from the bar's own edges, as in the dock's container: the same buttons, in the same place.
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = DOCK_INSET, end = DOCK_INSET, top = MODULE_GAP + DOCK_INSET, bottom = DOCK_INSET),
                    horizontalArrangement = Arrangement.spacedBy(DOCK_INSET),
                ) {
                    BarButtons(start, stop, end, widths)
                }
            }
        }
        DockContainer(
            Modifier
                .align(Alignment.BottomStart)
                .width(widths.module)
                .height(DOCK_HEIGHT)
        ) {
            DockButton(module, Modifier.weight(1f))
        }
    }
}

/** The bar's buttons, STOP in the middle. */
@Composable
private fun RowScope.BarButtons(start: List<DockItem>, stop: DockStop, end: List<DockItem>, widths: DockWidths) {
    start.forEach { DockButton(it, Modifier.weight(1f)) }
    StopKey(stop, Modifier.width(widths.stop))
    end.forEach { DockButton(it, Modifier.weight(1f)) }
}

/**
 * The open tab's bar: on the dock's top edge above the tab, flush with it, the edge toward the
 * page; nearly the button's width, its lower corners rounded like a tab's.
 */
private fun DrawScope.drawEdgeMark(color: Color) {
    val h = MARK_HEIGHT.toPx()
    val left = MARK_SIDE.toPx()
    val top = -DOCK_INSET.toPx()
    val r = CornerRadius(h, h)
    val mark = RoundRect(
        Rect(left, top, size.width - left, top + h),
        topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero, bottomRight = r, bottomLeft = r,
    )
    drawPath(Path().apply { addRoundRect(mark) }, color)
}

/** The widths of the dock's buttons: each one's ([slot]), STOP's, and the module's container. */
private class DockWidths(val slot: Dp, val stop: Dp) {
    val module: Dp get() = slot + DOCK_INSET * 2
}

/**
 * One width for every button, the module's included: what's left once the gap and the
 * containers' insets come off, shared by the bar's [buttons] and the module's. STOP never goes
 * under [STOP_MIN_WIDTH]; on a phone too narrow for that, the others give it the difference.
 */
private fun dockWidths(maxWidth: Dp, buttons: Int): DockWidths {
    val free = maxWidth - MODULE_GAP - DOCK_INSET * (buttons + 3)
    val even = free / (buttons + 1)
    val stop = max(even, STOP_MIN_WIDTH)
    return DockWidths(slot = if (stop > even) (free - stop) / buttons else even, stop = stop)
}

/**
 * A rounded rectangle with its bottom-left corner cut out, [notchWidth] by [notchHeight]: the
 * card corner on every outer corner, and where the cut-out meets the bar, an inside corner that
 * wraps Exit's own corner ([NOTCH_FILLET]), so the bar flows out of the card around it.
 */
private class NotchedShape(private val notchWidth: Dp, private val notchHeight: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { DOCK_RADIUS.toPx() }
        val f = with(density) { NOTCH_FILLET.toPx() }
        val nw = with(density) { notchWidth.toPx() }
        val ny = size.height - with(density) { notchHeight.toPx() } // the card's bottom, left of the bar
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(nw + r, h)
            arcTo(Rect(nw, h - 2 * r, nw + 2 * r, h), 90f, 90f, false)
            lineTo(nw, ny + f)
            // The inside corner, turning back along the card's bottom.
            arcTo(Rect(nw - 2 * f, ny, nw, ny + 2 * f), 0f, -90f, false)
            lineTo(r, ny)
            arcTo(Rect(0f, ny - 2 * r, 2 * r, ny), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
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
        item.accent != null -> item.accent
        item.prominent -> colors.onPrimaryContainer
        item.selected -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val labelInk = when {
        !item.enabled -> faded
        item.accent != null -> item.accent
        item.prominent -> colors.onPrimaryContainer
        item.selected -> colors.primary
        else -> colors.onSurfaceVariant
    }
    // Tabs only: on the Map page the bar is part of the card, so there is no edge to mark, and
    // Drive's pad, rising above it, says it's open.
    val marked = item.isTab && item.selected && item.enabled
    val markColor = colors.primary
    Box(
        modifier
            .fillMaxHeight()
            // Drawn above the button, in the inset between it and the page: the container clips it there.
            .drawBehind { if (marked) drawEdgeMark(markColor) }
    ) {
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
            Icon(
                painterResource(if (item.selected) item.selectedIcon else item.icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconInk,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (item.selected || item.prominent || item.accent != null) FontWeight.Bold else FontWeight.Medium,
                color = labelInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private val STOP_MIN_WIDTH = 72.dp
// The app's card radius outside; inside, the outer radius less the inset, so the corners nest.
private val DOCK_RADIUS = 14.dp
private val DOCK_SHAPE = RoundedCornerShape(DOCK_RADIUS)
// The cut-out's inside corner wraps Exit's corner at the gap's distance: concentric with it, so
// the gap is the same all the way round and the two pieces read as fitting together.
private val NOTCH_FILLET = DOCK_RADIUS + MODULE_GAP
private val MARK_HEIGHT = 3.dp
private val MARK_SIDE = 6.dp // the bar runs nearly the button's width: a line on the edge, not a puck
private val INNER_SHAPE = RoundedCornerShape(8.dp)
