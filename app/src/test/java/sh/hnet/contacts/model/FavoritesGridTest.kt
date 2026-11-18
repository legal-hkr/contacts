package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesGridTest {
    @Test
    fun fourByTwoHasATitleAndTwoRows() {
        val fit = FavoritesGrid.fit(250, 200)
        assertEquals(4, fit.columns)
        assertEquals(2, fit.rows)
        assertTrue(fit.title)
        assertTrue(fit.labels)
        assertEquals(8, fit.count)
        assertEquals(FavoritesGrid.StateLook.FULL, FavoritesGrid.stateLook(fit))
    }

    @Test
    fun oneRowHasNoTitle() {
        val fit = FavoritesGrid.fit(250, 110)
        assertEquals(4, fit.columns)
        assertEquals(1, fit.rows)
        assertFalse(fit.title)
        assertTrue(fit.labels)
        assertEquals(FavoritesGrid.StateLook.TEXT, FavoritesGrid.stateLook(fit))
    }

    @Test
    fun twoByOneShowsTwoEvenOnTheSmallestCells() {
        // Two cells as the platform's formula gives them at the least: 73 × 2 − 16
        assertEquals(2, FavoritesGrid.fit(130, 110).count)
    }

    @Test
    fun oneColumnShowsOneAvatarWithoutItsName() {
        val fit = FavoritesGrid.fit(60, 60)
        assertEquals(1, fit.count)
        assertFalse(fit.labels)
        assertEquals(48, fit.avatar)
        assertEquals(FavoritesGrid.StateLook.ICON, FavoritesGrid.stateLook(fit))
    }

    @Test
    fun aVeryLowWidgetShrinksTheAvatar() {
        assertEquals(36, FavoritesGrid.fit(40, 40).avatar)
        assertEquals(FavoritesGrid.SMALLEST_AVATAR, FavoritesGrid.fit(57, 20).avatar)
    }

    @Test
    fun aTallWidgetFillsWithRows() {
        val fit = FavoritesGrid.fit(250, 420)
        assertTrue(fit.title)
        // 420 − 4 of room for shadows − 36 of the header = 380: five rows of 60 with 8 between take 332, six 400
        assertEquals(5, fit.rows)
    }
}
