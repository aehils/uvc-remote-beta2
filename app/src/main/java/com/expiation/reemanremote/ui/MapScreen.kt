package com.expiation.reemanremote.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expiation.reemanremote.DriveController
import com.expiation.reemanremote.MapImage
import com.expiation.reemanremote.Maps
import com.expiation.reemanremote.Points
import com.expiation.reemanremote.Pose
import com.expiation.reemanremote.R
import com.expiation.reemanremote.RemoteState
import com.expiation.reemanremote.RobotMode
import com.expiation.reemanremote.SavedMap
import com.expiation.reemanremote.Wall
import com.expiation.reemanremote.Waypoint
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------- Map page

/** What a touch on the map does: look around, or one of the tools in the dock. */
internal enum class MapTool { LOOK, PLACE, WALL, ERASE }

/** Everything drawn over the map image. The pending ones were marked while mapping and aren't saved yet. */
private class MapLayers(
    val points: List<Waypoint>,
    val pile: Waypoint?,
    val walls: List<Wall>,
    val pendingPoints: List<Waypoint>,
    val pendingWalls: List<Wall>,
)

private fun mapLayers(state: RemoteState): MapLayers {
    val newMap = state.mode == RobotMode.MAPPING // a new map has nothing of the old one's
    val loaded = state.points as? Points.Loaded
    return MapLayers(
        points = if (newMap) emptyList() else loaded?.list.orEmpty(),
        pile = if (newMap) null else loaded?.pile,
        walls = if (newMap) emptyList() else state.walls.orEmpty(),
        pendingPoints = state.pendingPoints,
        pendingWalls = state.pendingWalls,
    )
}

/**
 * The Map page's own state: the tool in hand and what it has drawn, and which dialog is open.
 * Kept above the page (see [rememberMapUi]) because the dock carries the page's buttons.
 */
@Stable
internal class MapUi(tool: MapTool = MapTool.LOOK) {
    var tool by mutableStateOf(tool)
        private set
    var placed by mutableStateOf<Pose?>(null)
    var draft by mutableStateOf<Wall?>(null)
    var picked by mutableStateOf<Wall?>(null)
    var menu by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var addingPile by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var discarding by mutableStateOf(false)
    var fit by mutableIntStateOf(0) // bumped to fit the whole map in view again

    fun use(t: MapTool) {
        tool = t
        placed = null
        draft = null
        picked = null
    }

    /** Back to looking, with nothing open: for leaving the page. */
    fun reset() {
        use(MapTool.LOOK)
        menu = false
        adding = false
        addingPile = false
        saving = false
        discarding = false
    }

    companion object {
        val Saver = listSaver<MapUi, Any>(
            save = { listOf(it.tool.name, it.adding, it.addingPile, it.saving, it.discarding) },
            restore = {
                MapUi(MapTool.valueOf(it[0] as String)).apply {
                    adding = it[1] as Boolean
                    addingPile = it[2] as Boolean
                    saving = it[3] as Boolean
                    discarding = it[4] as Boolean
                }
            },
        )
    }
}

@Composable
internal fun rememberMapUi(): MapUi = rememberSaveable(saver = MapUi.Saver) { MapUi() }

/** The Map page's buttons either side of STOP in the dock. */
internal class MapDock(val start: List<DockItem>, val end: List<DockItem>)

/**
 * The dock on the Map page. Looking: Drive (the pad) left of STOP; Add and Position right of it,
 * Save in place of Position while mapping. With a tool in hand: Cancel, and the tool's tick.
 */
internal fun mapDock(state: RemoteState, actions: RemoteActions, ui: MapUi, drive: DriveSheetState): MapDock {
    if (ui.tool != MapTool.LOOK) {
        val confirm = when (ui.tool) {
            MapTool.PLACE -> DockItem(
                R.drawable.ic_check, "Set here", "Set the robot's position here",
                enabled = ui.placed != null && state.canStartMapping, prominent = true,
            ) {
                ui.placed?.let(actions.onSetPosition)
                ui.use(MapTool.LOOK)
            }
            MapTool.WALL -> DockItem(
                R.drawable.ic_check, "Add wall", "Add this virtual wall",
                enabled = (ui.draft?.length ?: 0.0) >= DriveController.MIN_WALL_M && (state.mapping || state.canChangeMap),
                prominent = true,
            ) {
                ui.draft?.let(actions.onAddWall)
                ui.use(MapTool.LOOK)
            }
            else -> DockItem(
                R.drawable.ic_delete, "Remove", "Remove this virtual wall",
                enabled = ui.picked.let { it != null && (it in state.pendingWalls || state.canStartMapping) },
                prominent = true,
            ) {
                ui.picked?.let(actions.onRemoveWall)
                ui.use(MapTool.LOOK)
            }
        }
        return MapDock(
            start = listOf(DockItem(R.drawable.ic_close, "Cancel") { ui.use(MapTool.LOOK) }),
            end = listOf(confirm),
        )
    }
    val drawable = state.mapImage?.placeable == true
    val driveItem = DockItem(
        R.drawable.ic_tab_remote, "Drive",
        if (drive.open) "Hide the drive controls" else "Show the drive controls",
        selected = drive.open,
    ) { drive.onOpenChange(!drive.open) }
    val add = DockItem(
        R.drawable.ic_add_point, "Add", "Add to the map",
        selected = ui.menu,
        menu = { AddMenu(state, ui, drawable) },
    ) { ui.menu = true }
    val last = if (state.mapping) {
        DockItem(R.drawable.ic_save, "Save", "Save the map", enabled = state.canChangeMap, prominent = true) { ui.saving = true }
    } else {
        DockItem(
            R.drawable.ic_set_position, "Position", "Set the robot's position",
            enabled = state.canStartMapping && drawable,
        ) { ui.use(MapTool.PLACE) }
    }
    return MapDock(start = listOf(driveItem), end = listOf(add, last))
}

/** The Add button's menu: what to mark on the map. */
@Composable
private fun AddMenu(state: RemoteState, ui: MapUi, drawable: Boolean) {
    val layers = mapLayers(state)
    DropdownMenu(
        expanded = ui.menu,
        onDismissRequest = { ui.menu = false },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        DropdownMenuItem(
            text = { Text("Disinfection point here") },
            enabled = state.canMark,
            onClick = {
                ui.menu = false
                ui.adding = true
            },
        )
        DropdownMenuItem(
            text = { Text("Charging pile here") },
            enabled = state.canMark,
            onClick = {
                ui.menu = false
                ui.addingPile = true
            },
        )
        DropdownMenuItem(
            text = { Text("Virtual wall") },
            enabled = drawable && (state.mapping || state.canStartMapping),
            onClick = {
                ui.menu = false
                ui.use(MapTool.WALL)
            },
        )
        DropdownMenuItem(
            text = { Text("Remove a virtual wall") },
            enabled = drawable && (layers.walls.isNotEmpty() || layers.pendingWalls.isNotEmpty()),
            onClick = {
                ui.menu = false
                ui.use(MapTool.ERASE)
            },
        )
    }
}

/**
 * The Map page: the robot on its map (or, while mapping, the scan as it grows) with the saved
 * points, the charging pile and the virtual walls, and, under it, the drive pad when the dock's
 * Drive button is on. The page's buttons are in the dock ([mapDock]): Add marks a disinfection
 * point or the charging pile where the robot stands, or draws or removes a virtual wall; while
 * mapping these wait in the app and are saved with the map. Mapping pulls the pad up by itself.
 */
@Composable
internal fun MapTab(state: RemoteState, actions: RemoteActions, ui: MapUi, drive: DriveSheetState, modifier: Modifier) {
    // The robot's pose and the map image are only read while a map is on screen.
    val onVisible by rememberUpdatedState(actions.onMapVisible)
    DisposableEffect(Unit) {
        onVisible(true)
        onDispose { onVisible(false) }
    }
    val points = state.points
    LaunchedEffect(state.connected, points is Points.NotLoaded, state.mapping) {
        if (state.connected && points is Points.NotLoaded && !state.mapping) actions.onLoadPoints()
    }
    // Setting the position is for a saved map only.
    LaunchedEffect(state.mapping) { if (state.mapping && ui.tool == MapTool.PLACE) ui.use(MapTool.LOOK) }

    // The outcome of the last map or point command, shown on the map for a while (tap to hide
    // it sooner; the Activity tab keeps it). Not the ones from before the page was opened.
    var note by remember { mutableStateOf<String?>(null) }
    val seenMap = remember { state.mapMessage }
    val seenPoint = remember { state.pointMessage }
    LaunchedEffect(state.mapMessage) {
        val m = state.mapMessage
        if (m != null && m != seenMap) {
            note = m
            delay(NOTE_MS)
            if (note == m) note = null
        }
    }
    LaunchedEffect(state.pointMessage) {
        val m = state.pointMessage
        if (m != null && m != seenPoint) {
            note = m
            delay(NOTE_MS)
            if (note == m) note = null
        }
    }

    val image = state.mapImage
    val layers = mapLayers(state)
    val drawable = image?.placeable == true
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null && drawable) {
                MapView(
                    image = image,
                    robot = state.robotPose,
                    layers = layers,
                    tool = ui.tool,
                    ghost = ui.placed,
                    draft = ui.draft,
                    picked = ui.picked,
                    onPlace = { ui.placed = it },
                    onDraft = { ui.draft = it },
                    onPick = { ui.picked = it },
                    fitKey = ui.fit,
                )
            } else {
                Text(
                    when {
                        !state.connected -> "Connect to the robot to see its map."
                        state.mapProblem != null -> state.mapProblem
                        image != null -> "This map came without a scale, so the robot can't be placed on it."
                        state.mapping -> "Waiting for the first scan …"
                        else -> "Loading the map …"
                    },
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.mapping) {
                MappingBadge(
                    extending = state.mode == RobotMode.EXTENDING,
                    canDiscard = state.canChangeMap,
                    onDiscard = { ui.discarding = true },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
            if (image != null) {
                MapChipButton(
                    "Fit",
                    onClickLabel = "Fit the whole map in view",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) { ui.fit++ }
            }
            if (state.mapStep != null) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
            val hint = when (ui.tool) {
                MapTool.PLACE -> "Tap where the robot really is, then drag the way it faces."
                MapTool.WALL -> "Drag from one end of the wall to the other. The robot won't cross it."
                MapTool.ERASE -> "Tap the wall to remove."
                MapTool.LOOK -> state.mapStep ?: note
            }
            hint?.let {
                Text(
                    it,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Hide") { note = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AnimatedVisibility(
            drive.open,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            DrivePanel(state, actions)
        }
    }

    if (ui.adding) {
        val taken = (layers.points + layers.pendingPoints).map { it.name }.toSet() + "charging_pile" + DriveController.APPROACH_POINT
        AddPointDialog(
            taken = taken,
            onDismiss = { ui.adding = false },
            onAdd = {
                ui.adding = false
                actions.onAddPoint(it)
            },
        )
    }
    if (ui.addingPile) {
        val replaces = layers.pile != null || layers.pendingPoints.any { it.type == "charge" }
        AlertDialog(
            onDismissRequest = { ui.addingPile = false },
            title = { Text("Mark the charging pile here?") },
            text = {
                Text(
                    "Do this with the robot docked on its charging pile: the spot it stands on becomes the pile's " +
                        "position, which GO TO CHARGER drives to." +
                        (if (replaces) " It replaces the pile already marked." else "") +
                        (if (state.mapping) " It's saved with the map." else ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.addingPile = false
                    actions.onAddChargingPile()
                }) { Text("Mark pile", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { ui.addingPile = false }) { Text("Cancel") } },
        )
    }
    if (ui.saving) {
        val extending = state.mode == RobotMode.EXTENDING
        val marks = state.pendingPoints.size + state.pendingWalls.size
        MapNameDialog(
            title = if (extending) "Save the extended map?" else "Save the new map?",
            body = (if (extending) "Saves what was added to ${state.currentMap?.label ?: "the map"}, then the robot goes back to navigating on it."
            else "Saves the scan as a new map, and the robot starts navigating on it.") +
                if (marks > 0) " The $marks spot(s) you marked are saved onto it." else "",
            initial = if (extending) state.currentMap?.alias.orEmpty() else "",
            required = !extending,
            confirm = "Save",
            onDismiss = { ui.saving = false },
            onConfirm = {
                ui.saving = false
                actions.onSaveMap(it)
            },
        )
    }
    if (ui.discarding) {
        val marks = state.pendingPoints.size + state.pendingWalls.size
        AlertDialog(
            onDismissRequest = { ui.discarding = false },
            title = { Text("Discard the scan?") },
            text = {
                Text(
                    "The robot goes back to navigating on ${state.currentMap?.label ?: "its map"}, unchanged. What was scanned" +
                        (if (marks > 0) ", and the $marks spot(s) you marked," else "") + " is lost.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.discarding = false
                    actions.onDiscardMapping()
                }) { Text("Discard", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { ui.discarding = false }) { Text("Keep mapping") } },
        )
    }
}

/**
 * Over the map's top left while mapping: a live dot, what the robot is doing, and Discard. Save
 * is the dock's job; Discard is the quieter way out, so it sits here rather than beside it.
 */
@Composable
private fun MappingBadge(extending: Boolean, canDiscard: Boolean, onDiscard: () -> Unit, modifier: Modifier) {
    val good = RemoteTheme.status.good
    val pulse by rememberInfiniteTransition(label = "mapping").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot",
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(pulse)
                .background(good, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (extending) "Extending" else "Scanning",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = good,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onDiscard, enabled = canDiscard) { Text("Discard") }
    }
}

/** A small floating button over the map. */
@Composable
private fun MapChipButton(label: String, onClickLabel: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

private const val NOTE_MS = 8_000L

/** The map's name, in the Map page's readings; tapping it opens the Maps page. */
@Composable
internal fun MapChip(state: RemoteState, onOpenMaps: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClickLabel = "Open your saved maps", onClick = onOpenMaps)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_tab_map),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                state.mode == RobotMode.MAPPING -> "New map"
                else -> state.currentMap?.label ?: "Map"
            },
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Fits the map image in the view, then the user's zoom and pan on top:
 * screen = view centre + [pan] + (pixel - image centre) * scale.
 */
private class MapViewport(w: Float, h: Float, val image: MapImage, zoom: Float, pan: Offset) {
    val scale = min(w / image.width, h / image.height) * zoom
    val left = w / 2 + pan.x - image.width * scale / 2
    val top = h / 2 + pan.y - image.height * scale / 2

    fun toScreen(x: Double, y: Double) = Offset(left + image.pixelX(x).toFloat() * scale, top + image.pixelY(y).toFloat() * scale)

    fun toMap(o: Offset): Pair<Double, Double> =
        image.mapX(((o.x - left) / scale).toDouble()) to image.mapY(((o.y - top) / scale).toDouble())
}

/** How far a point on screen is from the segment a-b, in pixels. */
private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

/**
 * The map image with the points, the charging pile, the virtual walls and the robot on it.
 * [MapTool.LOOK]: pinch to zoom, drag to pan. [MapTool.PLACE]: a tap puts [ghost] (the position
 * to set) there, and dragging from it turns it. [MapTool.WALL]: a drag draws [draft].
 * [MapTool.ERASE]: a tap picks the nearest wall.
 */
@Composable
private fun MapView(
    image: MapImage,
    robot: Pose?,
    layers: MapLayers,
    tool: MapTool,
    ghost: Pose?,
    draft: Wall?,
    picked: Wall?,
    onPlace: (Pose) -> Unit,
    onDraft: (Wall) -> Unit,
    onPick: (Wall?) -> Unit,
    fitKey: Int,
) {
    val bitmap = remember(image.png) { decodeMap(image.png) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(fitKey) {
        zoom = 1f
        pan = Offset.Zero
    }
    val current by rememberUpdatedState(image)
    val place by rememberUpdatedState(onPlace)
    val drawWall by rememberUpdatedState(onDraft)
    val pick by rememberUpdatedState(onPick)
    val walls by rememberUpdatedState(layers.walls + layers.pendingWalls)
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val status = RemoteTheme.status
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurface, fontWeight = FontWeight.Medium)

    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Map of the robot's surroundings" }
            .pointerInput(tool) {
                fun view() = MapViewport(size.width.toFloat(), size.height.toFloat(), current, zoom, pan)
                when (tool) {
                    MapTool.LOOK -> detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val z = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                        val k = z / zoom
                        val c = centroid - Offset(size.width / 2f, size.height / 2f)
                        // Keep the spot under the fingers where it is while zooming.
                        pan = c * (1 - k) + pan * k + panChange
                        zoom = z
                    }
                    MapTool.PLACE -> awaitEachGesture {
                        val turnAfter = 12.dp.toPx()
                        val down = awaitFirstDown()
                        down.consume()
                        val (x, y) = view().toMap(down.position)
                        place(Pose(x, y, 0.0))
                        while (true) {
                            val event = awaitPointerEvent()
                            val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                            finger.consume()
                            if (!finger.pressed) break
                            val d = finger.position - down.position
                            if (d.getDistance() > turnAfter) {
                                place(Pose(x, y, atan2(-d.y, d.x).toDouble())) // screen y points down; the map's y up
                            }
                        }
                    }
                    MapTool.WALL -> awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val (x1, y1) = view().toMap(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                            finger.consume()
                            if (!finger.pressed) break
                            val (x2, y2) = view().toMap(finger.position)
                            drawWall(Wall(x1, y1, x2, y2))
                        }
                    }
                    MapTool.ERASE -> awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val v = view()
                        val reach = 24.dp.toPx()
                        val nearest = walls.minByOrNull {
                            distanceToSegment(down.position, v.toScreen(it.x1, it.y1), v.toScreen(it.x2, it.y2))
                        }
                        pick(nearest?.takeIf {
                            distanceToSegment(down.position, v.toScreen(it.x1, it.y1), v.toScreen(it.x2, it.y2)) <= reach
                        })
                    }
                }
            }
    ) {
        val view = MapViewport(size.width, size.height, image, zoom, pan)
        bitmap?.let {
            drawImage(
                it,
                dstOffset = IntOffset(view.left.roundToInt(), view.top.roundToInt()),
                dstSize = IntSize((image.width * view.scale).roundToInt(), (image.height * view.scale).roundToInt()),
                filterQuality = FilterQuality.None, // crisp cells, not a blur
            )
        }
        val dashed = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
        fun wall(w: Wall, color: Color, width: Float, effect: PathEffect? = null) = drawLine(
            color, view.toScreen(w.x1, w.y1), view.toScreen(w.x2, w.y2), width, StrokeCap.Round, effect,
        )
        picked?.let { wall(it, status.warn, 9.dp.toPx()) }
        layers.walls.forEach { wall(it, status.danger, 3.dp.toPx()) }
        layers.pendingWalls.forEach { wall(it, status.danger, 3.dp.toPx(), dashed) }
        draft?.let { wall(it, status.danger.copy(alpha = 0.7f), 3.dp.toPx(), dashed) }

        fun label(text: String, at: Offset) {
            val t = measurer.measure(text, labelStyle)
            val topLeft = at + Offset(10.dp.toPx(), -t.size.height / 2f)
            drawRoundRect(
                colors.surface.copy(alpha = 0.85f),
                topLeft = topLeft - Offset(3.dp.toPx(), 1.dp.toPx()),
                size = Size(t.size.width + 6.dp.toPx(), t.size.height + 2.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(t, topLeft = topLeft)
        }
        // The charging pile: a square, so it is never taken for a disinfection point.
        fun pile(p: Waypoint, saved: Boolean) {
            val pose = p.pose ?: return
            val at = view.toScreen(pose.x, pose.y)
            val half = 6.dp.toPx()
            val box = Size(half * 2, half * 2)
            if (saved) drawRect(status.good, at - Offset(half, half), box)
            drawRect(if (saved) colors.surface else status.good, at - Offset(half, half), box, style = Stroke(2.dp.toPx(), pathEffect = if (saved) null else dashed))
            label("Charging pile", at)
        }
        fun point(p: Waypoint, saved: Boolean) {
            val pose = p.pose ?: return
            val at = view.toScreen(pose.x, pose.y)
            if (saved) drawCircle(status.warn, 5.dp.toPx(), at)
            drawCircle(if (saved) colors.surface else status.warn, 5.dp.toPx(), at, style = Stroke(if (saved) 1.5.dp.toPx() else 2.dp.toPx()))
            label(p.name, at)
        }
        layers.pile?.let { pile(it, saved = true) }
        layers.points.forEach { point(it, saved = true) }
        layers.pendingPoints.forEach { if (it.type == "charge") pile(it, saved = false) else point(it, saved = false) }

        val robotRadius = max((ROBOT_RADIUS_M / image.resolution).toFloat() * view.scale, 7.dp.toPx())
        robot?.let { drawRobot(it, view, colors.primary, robotRadius) }
        ghost?.let { drawRobot(it, view, status.warn, robotRadius) }
    }
}

/** A disc the robot's size, with a pointer the way it faces. */
private fun DrawScope.drawRobot(p: Pose, view: MapViewport, color: Color, radius: Float) {
    val c = view.toScreen(p.x, p.y)
    val a = -p.theta.toFloat() // screen angles turn clockwise
    drawCircle(color.copy(alpha = 0.25f), radius, c)
    drawCircle(color, radius, c, style = Stroke(2.dp.toPx()))
    val reach = radius + 8.dp.toPx()
    val tip = c + Offset(cos(a) * reach, sin(a) * reach)
    val side = 0.45f
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(c.x + cos(a + side) * radius * 0.7f, c.y + sin(a + side) * radius * 0.7f)
        lineTo(c.x + cos(a - side) * radius * 0.7f, c.y + sin(a - side) * radius * 0.7f)
        close()
    }
    drawPath(path, color)
}

/** The PNG inside a map's data URL. */
internal fun decodeMap(base64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

private const val MAX_ZOOM = 8f
private const val ROBOT_RADIUS_M = 0.3 // GUESS, as in the simulator: only for drawing

// ---------------------------------------------------------------------- Maps page

/**
 * Maps: the maps saved on the robot. Start a new map or extend the one in use (both continue on
 * the Map page, driving with its drive pad), switch maps, rename and delete them.
 * Like the other pages, it keeps STOP, the drive sheet and the robot's readings on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapsPage(
    state: RemoteState,
    actions: RemoteActions,
    drive: DriveSheetState,
    onBack: () -> Unit,
    onShowMap: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val maps = state.maps
    LaunchedEffect(state.connected, maps is Maps.NotLoaded) {
        if (state.connected && maps is Maps.NotLoaded) actions.onLoadMaps()
    }
    // null: no dialog; false: start a new map; true: extend the map in use.
    var starting by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var using by remember { mutableStateOf<SavedMap?>(null) }
    var renaming by remember { mutableStateOf<SavedMap?>(null) }
    var deleting by remember { mutableStateOf<SavedMap?>(null) }
    val inUse = state.currentMap

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                    },
                    title = { Text("Maps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        BatteryReading(state)
                        TextButton(onClick = actions.onLoadMaps, enabled = state.connected && maps != Maps.Loading) { Text("Refresh") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.mapping) {
                Panel(color = RemoteTheme.status.goodContainer) {
                    Text("The robot is mapping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onShowMap, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Open the map")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { starting = false },
                        enabled = state.canStartMapping,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("+ New map", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { starting = true },
                        enabled = state.canStartMapping && inUse != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Extend this map", fontWeight = FontWeight.Bold) }
                }
            }
            (state.mapStep ?: state.mapMessage ?: when {
                !state.connected -> "Connect to the robot first."
                state.busy || !state.still -> "The robot must be standing still to change maps."
                state.uv != null || state.lampsOffOwed -> "Switch the UV lamps off first."
                else -> null
            })?.let { TasksNote(it) }
            state.trashed?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${t.label} is in the robot's bin.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = actions.onRestoreMap, enabled = state.canChangeMap) { Text("Undo") }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Saved on the robot")
            when (maps) {
                is Maps.Loaded -> if (maps.list.isEmpty()) {
                    TasksNote("No maps saved on this robot yet. Start one above.")
                } else {
                    // The map in use first.
                    maps.list.sortedByDescending { it.id == inUse?.id }.forEach { m ->
                        MapRow(
                            map = m,
                            inUse = m.id == inUse?.id,
                            canChange = state.canChangeMap,
                            canSwitch = state.canStartMapping,
                            onUse = { using = m },
                            onRename = { renaming = m },
                            onDelete = { deleting = m },
                        )
                    }
                }
                Maps.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Maps.Failed -> TasksNote("Couldn't read the maps: ${maps.error}")
                Maps.NotLoaded -> if (!state.connected) TasksNote("Connect to the robot to see its maps.")
            }
        }
    }

    starting?.let { extend ->
        AlertDialog(
            onDismissRequest = { starting = null },
            title = { Text(if (extend) "Extend ${inUse?.label ?: "this map"}?" else "Start a new map?") },
            text = {
                Text(
                    if (extend) "The robot adds to the map as you drive it into new areas. Check first that it's in " +
                        "the right place on the map (Map page), or the new part won't line up. Trips and " +
                        "disinfection wait until you save or discard."
                    else "The robot starts a fresh scan; your saved maps stay as they are. Drive it slowly round " +
                        "the whole area with DRIVE, coming back past where you started, then save it on the Map " +
                        "page. Trips and disinfection wait until you save or discard.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    starting = null
                    actions.onStartMapping(extend)
                    onShowMap()
                }) { Text(if (extend) "Extend" else "Start mapping", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { starting = null }) { Text("Cancel") } },
        )
    }
    using?.let { m ->
        AlertDialog(
            onDismissRequest = { using = null },
            title = { Text("Use ${m.label}?") },
            text = {
                Text(
                    "The robot switches to this map and its points, and assumes it's standing on the map's " +
                        "charging pile. If it isn't, set its position on the Map page afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    using = null
                    actions.onUseMap(m)
                }) { Text("Use map", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { using = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { m ->
        MapNameDialog(
            title = "Rename ${m.label}",
            body = null,
            initial = m.alias,
            required = true,
            confirm = "Rename",
            onDismiss = { renaming = null },
            onConfirm = {
                renaming = null
                actions.onRenameMap(m, it)
            },
        )
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${m.label}?") },
            text = {
                Text(
                    "It goes to the robot's bin, with its points, and the robot deletes it for good after 30 days. " +
                        "Undo brings it back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.onDeleteMap(m)
                }) { Text("Delete", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** One saved map: its picture, its name, and a menu to use, rename or delete it. */
@Composable
private fun MapRow(
    map: SavedMap,
    inUse: Boolean,
    canChange: Boolean,
    canSwitch: Boolean,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = RemoteTheme.status
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = map.thumbnail?.let { remember(it.png) { decodeMap(it.png) } }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(thumb, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(map.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (inUse) "In use" else "Saved") + if (map.alias.isBlank()) " · ${map.id.take(8)}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (inUse) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (inUse) status.good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Text(
                        "⋮",
                        fontSize = 22.sp,
                        modifier = Modifier.semantics { contentDescription = "Options for ${map.label}" },
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Use this map") },
                        enabled = !inUse && canSwitch,
                        onClick = {
                            menu = false
                            onUse()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        enabled = canChange,
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (inUse) "Delete (switch maps first)" else "Delete") },
                        enabled = !inUse && canChange,
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Asks for a map's name. [required]: an empty name isn't accepted (otherwise it keeps the old one). */
@Composable
private fun MapNameDialog(
    title: String,
    body: String?,
    initial: String,
    required: Boolean,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val clean = name.trim()
    val problem = when {
        required && clean.isEmpty() -> "Enter a name."
        clean.length > DriveController.MAX_MAP_NAME -> "Use ${DriveController.MAX_MAP_NAME} characters or fewer."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Map name") },
                    placeholder = { Text("Ward 3") },
                    singleLine = true,
                    isError = problem != null && name.isNotEmpty(),
                    supportingText = problem?.takeIf { name.isNotEmpty() }?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (problem == null) onConfirm(clean) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(clean) }, enabled = problem == null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
