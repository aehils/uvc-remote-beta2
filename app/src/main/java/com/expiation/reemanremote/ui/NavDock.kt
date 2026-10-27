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
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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

/**
 * The bottom of the main page: the module tile on the left, then one bar with STOP in it.
 * STOP sits at the centre of the screen whatever the bar carries, so the thumb finds it in the
 * same place on every page (the other pages' STOP bar is centred too). The bar's buttons
 * either side of it change with the page: a new set animates in; one whose buttons only
 * change state (selected, enabled) doesn't.
 */
@Composable
internal fun NavDock(module: DockModule, start: List<DockItem>, end: List<DockItem>, moving: Boolean, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = DOCK_GUTTER, end = DOCK_GUTTER, top = 8.dp, bottom = 12.dp)
                .height(DOCK_HEIGHT)
        ) {
            // The bar behind everything right of the module: one piece, so STOP sits in it, not on it.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = MODULE_WIDTH + MODULE_GAP)
                    .dockSurface(DOCK_SHAPE)
            )
            Row(Modifier.fillMaxSize()) {
                // Both sides of STOP take the same width, which is what keeps it centred.
                Row(Modifier.weight(1f).fillMaxHeight()) {
                    ModuleTile(module, Modifier.width(MODULE_WIDTH).fillMaxHeight())
                    Spacer(Modifier.width(MODULE_GAP))
                    DockSide(start, Modifier.weight(1f).fillMaxHeight())
                }
                StopKey(moving, onStop, Modifier.width(STOP_WIDTH).fillMaxHeight())
                DockSide(end, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The dock's raised look: a soft shadow (it shows in the light theme), the fill, and a hairline (it shows in the dark). */
@Composable
private fun Modifier.dockSurface(shape: Shape, color: Color = MaterialTheme.colorScheme.surface): Modifier = this
    .shadow(10.dp, shape, ambientColor = DOCK_SHADOW, spotColor = DOCK_SHADOW)
    .background(color, shape)
    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)

private fun List<DockItem>.dockKey() = joinToString("|") { it.label }

/** Buttons on one side of STOP, sharing its width equally. */
@Composable
private fun DockSide(items: List<DockItem>, modifier: Modifier) {
    // Animated by the buttons' labels, not the list: the same buttons in a new state (a tab
    // selected, a button enabled) are the same set, and must not fade in again on every frame
    // the robot's readings change.
    val key = items.dockKey()
    val latest by rememberUpdatedState(items)
    val last = remember { HashMap<String, List<DockItem>>() } // for the set fading out
    last[key] = items
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(260, delayMillis = 70), initialScale = 0.9f))
                .togetherWith(fadeOut(tween(90)))
        },
        label = "dock",
    ) { shown ->
        val set = latest.takeIf { it.dockKey() == shown } ?: last[shown].orEmpty()
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            set.forEach { DockButton(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DockButton(item: DockItem, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
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
    val labelInk = when {
        !item.enabled -> faded
        item.prominent -> colors.onPrimary
        item.selected -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = DOCK_INSET, horizontal = 2.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(INNER_SHAPE)
                // A prominent button is filled whole, like STOP; the others light a pill behind the icon.
                .background(if (item.prominent) fill else Color.Transparent)
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
            Box(
                Modifier
                    .height(30.dp)
                    .width(52.dp)
                    .background(if (item.prominent) Color.Transparent else fill, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (item.selected || item.prominent) FontWeight.SemiBold else FontWeight.Medium,
                color = labelInk,
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
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val container by animateColorAsState(if (module.active) colors.surface else colors.primaryContainer, tween(260), label = "module")
    val ink by animateColorAsState(if (module.active) colors.onSurface else colors.onPrimaryContainer, tween(260), label = "moduleInk")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .dockSurface(DOCK_SHAPE, container)
            .clip(DOCK_SHAPE)
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
            transitionSpec = {
                (fadeIn(tween(200, delayMillis = 60)) + scaleIn(tween(240, delayMillis = 60), initialScale = 0.8f))
                    .togetherWith(fadeOut(tween(90)))
            },
            label = "moduleContent",
        ) { (icon, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * STOP, set into the bar. While the robot moves a ring pulses round it, so it is found fastest
 * then. A firm haptic tick confirms the press.
 */
@Composable
private fun StopKey(moving: Boolean, onStop: () -> Unit, modifier: Modifier) {
    val stop = RemoteTheme.status.stop
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    // Only animates while moving, so an idle robot costs no frames.
    val ring = if (moving) {
        val pulse by rememberInfiniteTransition(label = "stop").animateFloat(
            initialValue = 0.2f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "ring",
        )
        pulse
    } else 0f
    Box(
        modifier
            .padding(vertical = 2.dp)
            .border(2.5.dp, stop.copy(alpha = ring), RING_SHAPE)
            .padding(4.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(4.dp, INNER_SHAPE, ambientColor = stop, spotColor = stop)
            // A touch lighter at the top: the key looks pressable, not painted on.
            .background(Brush.verticalGradient(listOf(lerp(stop, Color.White, 0.12f), stop)), INNER_SHAPE)
            .clip(INNER_SHAPE)
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
        Text("STOP", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}

private val DOCK_HEIGHT = 72.dp
private val DOCK_GUTTER = 12.dp
private val MODULE_WIDTH = 64.dp
private val MODULE_GAP = 6.dp
private val STOP_WIDTH = 80.dp
private val DOCK_INSET = 6.dp
// Nested corners stay concentric: the inner radius is the outer one less the inset.
private val DOCK_SHAPE = RoundedCornerShape(24.dp)
private val RING_SHAPE = RoundedCornerShape(22.dp)
private val INNER_SHAPE = RoundedCornerShape(18.dp)
private val DOCK_SHADOW = Color.Black.copy(alpha = 0.18f)
