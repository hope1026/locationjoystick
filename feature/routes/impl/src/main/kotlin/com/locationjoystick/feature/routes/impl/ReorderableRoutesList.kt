package com.locationjoystick.feature.routes.impl

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.matchesSearch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps drag presentation local and commits complete, stable route IDs only after release. */
@Composable
internal fun ReorderableRoutesList(
    routes: List<Route>,
    query: String,
    onOrderChanged: suspend (List<String>) -> Boolean,
    item: @Composable (Route, Modifier, Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latestRoutes by rememberUpdatedState(routes)
    val latestSave by rememberUpdatedState(onOrderChanged)
    var order by remember { mutableStateOf(routes.map { it.id }) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val canReorder = query.isBlank() && !saving && pendingOrder == null
    val currentReorderAllowed by rememberUpdatedState(canReorder)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val scrollStep = with(LocalDensity.current) { 8.dp.toPx() }
    val moveUp = stringResource(R.string.routes_custom_order_move_up)
    val moveDown = stringResource(R.string.routes_custom_order_move_down)

    fun reconcile(ids: List<String>): List<String> {
        val current = latestRoutes.map { it.id }
        val valid = current.toSet()
        return ids.filter { it in valid }.distinct() + current.filter { it !in ids }
    }

    fun cancelDrag() {
        draggedId = null
        order = latestRoutes.map { it.id }
    }

    fun persist(ids: List<String>) {
        val target = reconcile(ids)
        order = target
        pendingOrder = target
        saving = true
        scope.launch {
            var saved = false
            try {
                saved = latestSave(target)
                if (!saved) {
                    pendingOrder = null
                    order = latestRoutes.map { it.id }
                }
            } finally {
                if (!saved) {
                    pendingOrder = null
                    order = latestRoutes.map { it.id }
                }
                saving = false
            }
        }
    }

    fun moveAtPointer() {
        val id = draggedId ?: return
        val from = order.indexOf(id)
        if (from < 0) return
        val center = dragTop + dragHeight / 2f
        val visible = listState.layoutInfo.visibleItemsInfo.filter { it.key != id }
        val below = visible.filter { it.index > from && center > it.offset + it.size / 2f }.maxByOrNull { it.index }
        val above = visible.filter { it.index < from && center < it.offset + it.size / 2f }.minByOrNull { it.index }
        val to = (below ?: above)?.index?.coerceIn(0, order.lastIndex) ?: return
        if (to != from) {
            order = order.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    LaunchedEffect(routes, saving, draggedId) {
        if (draggedId != null && latestRoutes.none { it.id == draggedId }) cancelDrag()
        if (draggedId == null && !saving) {
            val pending = pendingOrder
            if (pending == null) {
                order = routes.map { it.id }
            } else {
                order = reconcile(pending)
                if (routes.map { it.id } == order) pendingOrder = null
            }
        } else {
            order = reconcile(order)
        }
    }

    LaunchedEffect(draggedId) {
        while (isActive && draggedId != null) {
            val viewport = listState.layoutInfo
            val center = dragTop + dragHeight / 2f
            val delta =
                when {
                    center > viewport.viewportEndOffset - edge && listState.canScrollForward -> scrollStep
                    center < viewport.viewportStartOffset + edge && listState.canScrollBackward -> -scrollStep
                    else -> 0f
                }
            if (delta != 0f) {
                listState.scrollBy(delta)
                moveAtPointer()
            }
            withFrameNanos { }
        }
    }

    val byId = routes.associateBy { it.id }
    val displayed = order.mapNotNull(byId::get).filter { it.matchesSearch(query) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("saved_routes_list"),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(displayed, key = { it.id }) { route ->
            val dragging = draggedId == route.id
            val cardModifier =
                (if (dragging) Modifier else Modifier.animateItem())
                    .testTag("saved_route_${route.id}")
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY =
                            if (dragging) {
                                dragTop - (
                                    listState.layoutInfo.visibleItemsInfo
                                        .find { it.key == route.id }
                                        ?.offset ?: 0
                                )
                            } else {
                                0f
                            }
                        shadowElevation = if (dragging) 8.dp.toPx() else 0f
                    }
            val handleModifier =
                Modifier
                    .testTag("route_drag_${route.id}")
                    .semantics {
                        if (!canReorder) disabled()
                        if (canReorder) {
                            val index = order.indexOf(route.id)
                            customActions =
                                listOfNotNull(
                                    if (index > 0) {
                                        CustomAccessibilityAction(moveUp) {
                                            persist(order.toMutableList().apply { add(index - 1, removeAt(index)) })
                                            true
                                        }
                                    } else {
                                        null
                                    },
                                    if (index < order.lastIndex) {
                                        CustomAccessibilityAction(moveDown) {
                                            persist(order.toMutableList().apply { add(index + 1, removeAt(index)) })
                                            true
                                        }
                                    } else {
                                        null
                                    },
                                )
                        }
                    }.pointerInput(route.id, query.isBlank()) {
                        if (query.isBlank()) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    val info = listState.layoutInfo.visibleItemsInfo.find { it.key == route.id }
                                    if (info != null && currentReorderAllowed) {
                                        dragTop = info.offset.toFloat()
                                        dragHeight = info.size.toFloat()
                                        draggedId = route.id
                                    }
                                },
                                onDrag = { change, delta ->
                                    if (draggedId == route.id) {
                                        change.consume()
                                        dragTop += delta.y
                                        moveAtPointer()
                                    }
                                },
                                onDragEnd = {
                                    val moved = order != latestRoutes.map { it.id }
                                    if (draggedId != null && moved) persist(order)
                                    draggedId = null
                                },
                                onDragCancel = { cancelDrag() },
                            )
                        }
                    }
            item(route, cardModifier, handleModifier)
        }
    }
}
