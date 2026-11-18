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
    fun oneByOneShowsOneAvatarWithoutItsName() {
        val fit = FavoritesGrid.fit(70, 70)
        assertEquals(1, fit.count)
        assertFalse(fit.labels)
        assertEquals(48, fit.avatar)
        assertEquals(FavoritesGrid.StateLook.ICON, FavoritesGrid.stateLook(fit))
    }

    @Test
    fun aVeryLowWidgetShrinksTheAvatar() {
        assertEquals(37, FavoritesGrid.fit(57, 57).avatar)
        assertEquals(FavoritesGrid.SMALLEST_AVATAR, FavoritesGrid.fit(57, 20).avatar)
    }

    @Test
    fun aTallWidgetFillsWithRows() {
        val fit = FavoritesGrid.fit(250, 420)
        assertTrue(fit.title)
        // 420 − 20 of padding − 24 of title = 376: four rows of 68 with 8 between take 296, five 372
        assertEquals(5, fit.rows)
    }
}
