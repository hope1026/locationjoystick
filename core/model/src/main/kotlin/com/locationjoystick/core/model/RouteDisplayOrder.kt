package com.locationjoystick.core.model

/** Stable route identities make renaming independent of a user-defined display order. */
fun List<Route>.sortedForRoutePicker(
    mode: SavedItemSortMode,
    customIds: List<String>,
): List<Route> {
    if (mode != SavedItemSortMode.CUSTOM) return sortedBySavedItemMode(mode) { it.name }
    val rank = normalizeCustomRouteOrder(customIds).withIndex().associate { it.value to it.index }
    return sortedWith(compareBy<Route> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { it.createdAt }.thenBy { it.id })
}

fun normalizeCustomRouteOrder(ids: List<String>): List<String> = ids.filter { it.isNotBlank() }.distinct()
