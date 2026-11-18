package sh.hnet.contacts.model

/**
 * How the Favorites widget lays out at the size the launcher gives it (SPEC §14.1, the owner's D4 of 18 Nov 2026): as many favorites as whole cells fit, never scrolling and never "more"; the header pill wherever a row fits under it (the owner, 18 Nov 2026: one row too); the smallest size is 2 × 1 (the owner, 18 Nov 2026; one column only where a launcher's two cells are narrower than two columns). Pieces without a background of their own, as Calendar's Agenda (the owner, the same day). All sizes in dp.
 */
object FavoritesGrid {
    /** Room for the pieces' shadows at the sides and at the top and bottom. */
    const val PADDING_SIDE = 2
    const val PADDING_END = 2

    /** One column: the 48 dp avatar with 4 dp on each side; a name wider than its column is cut with an ellipsis in its pill. */
    const val COLUMN = 56

    /** The avatar, and the name's 20 dp pill over its lower edge by 8. */
    const val AVATAR = 48
    const val CELL = 60

    /** Between two rows. */
    const val GAP = 8

    /** The header pill, 30 dp, with 6 under it. */
    const val TITLE = 36

    /** The smallest avatar, for a widget lower than one whole cell. */
    const val SMALLEST_AVATAR = 32

    /**
     * @param avatar The avatar's size: 48, smaller only when even one cell's height doesn't fit
     * @param labels Whether the names fit under the avatars
     * @param low Whether the widget is only one row high, without the header: the states show their sentence alone then
     */
    data class Fit(val columns: Int, val rows: Int, val title: Boolean, val labels: Boolean, val avatar: Int, val low: Boolean) {
        val count: Int get() = columns * rows
    }

    /** How the states (no access, no favorites) show: the icon alone in one column, the sentence alone in one row, all of it otherwise. */
    enum class StateLook { ICON, TEXT, FULL }

    fun fit(width: Int, height: Int): Fit {
        val inner = height - 2 * PADDING_END
        val columns = maxOf(1, (width - 2 * PADDING_SIDE) / COLUMN)
        val titled = rows(inner - TITLE) >= 1
        val rows = maxOf(1, rows(if (titled) inner - TITLE else inner))
        val labels = inner >= CELL
        val avatar = if (labels) AVATAR else inner.coerceIn(SMALLEST_AVATAR, AVATAR)
        return Fit(columns, rows, titled, labels, avatar, low = rows(inner) <= 1)
    }

    fun stateLook(fit: Fit): StateLook = when {
        fit.columns == 1 -> StateLook.ICON
        fit.low -> StateLook.TEXT
        else -> StateLook.FULL
    }

    private fun rows(height: Int): Int = if (height < CELL) 0 else (height + GAP) / (CELL + GAP)
}
