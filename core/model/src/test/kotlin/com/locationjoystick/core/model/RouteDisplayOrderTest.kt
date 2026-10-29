package com.locationjoystick.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteDisplayOrderTest {
    private val routes =
        listOf(
            Route("a", "Zulu", createdAt = 20L),
            Route("b", "Alpha", createdAt = 10L),
            Route("c", "Charlie", createdAt = 30L),
        )

    @Test
    fun `manual order follows identities and ignores duplicate or deleted ids`() {
        val sorted = routes.sortedForRoutePicker(SavedItemSortMode.CUSTOM, listOf("c", "gone", "c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), sorted.map { it.id })
        assertEquals(routes.toSet(), sorted.toSet())
    }

    @Test
    fun `new routes append deterministically and renaming does not change custom order`() {
        val order = listOf("c", "a")
        val newRoutes = routes + Route("new-2", "First", createdAt = 40L) + Route("new-1", "Last", createdAt = 40L)
        val expected = listOf("c", "a", "b", "new-1", "new-2")
        assertEquals(expected, newRoutes.sortedForRoutePicker(SavedItemSortMode.CUSTOM, order).map { it.id })
        assertEquals(
            expected,
            newRoutes.map { it.copy(name = "renamed ${it.id}") }.sortedForRoutePicker(SavedItemSortMode.CUSTOM, order).map { it.id },
        )
    }

    @Test
    fun `automatic sort modes remain independent from the saved manual order`() {
        for (mode in SavedItemSortMode.entries.filterNot { it == SavedItemSortMode.CUSTOM }) {
            assertEquals(
                routes.sortedBySavedItemMode(mode) { it.name },
                routes.sortedForRoutePicker(mode, listOf("c", "a", "b")),
            )
        }
    }
}
