package sh.hnet.contacts.model

/**
 * How the Favorites widget lays out at the size the launcher gives it (SPEC §14.1, the owner's D4 of 18 Nov 2026): as many favorites as whole cells fit, never scrolling and never "more"; the title only where two rows fit under it; 1 × 1 shows one avatar (the owner, 18 Nov 2026). All sizes in dp.
 */
object FavoritesGrid {
    /** The card's padding at the sides and at the top and bottom. */
    const val PADDING_SIDE = 8
    const val PADDING_END = 10

    /** One column: the 48 dp avatar with 4 dp on each side; a name wider than that is cut with an ellipsis. */
    const val COLUMN = 56

    /** The avatar, the name's 4 dp margin and its line. */
    const val AVATAR = 48
    const val CELL = 68

    /** Between two rows. */
    const val GAP = 8

    /** The title's line with its margin under it. */
    const val TITLE = 24

    /** The smallest avatar, for a widget lower than one whole cell. */
    const val SMALLEST_AVATAR = 32

    /**
     * @param avatar The avatar's size: 48, smaller only when even one cell's height doesn't fit
     * @param labels Whether the names fit under the avatars
     */
    data class Fit(val columns: Int, val rows: Int, val title: Boolean, val labels: Boolean, val avatar: Int) {
        val count: Int get() = columns * rows
    }

    /** How the state cards (no access, no favorites) show: the icon alone at 1 × 1, the sentence alone in one row, all of it otherwise. */
    enum class StateLook { ICON, TEXT, FULL }

    fun fit(width: Int, height: Int): Fit {
        val inner = height - 2 * PADDING_END
        val columns = maxOf(1, (width - 2 * PADDING_SIDE) / COLUMN)
        val titled = rows(inner - TITLE) >= 2
        val rows = maxOf(1, rows(if (titled) inner - TITLE else inner))
        val labels = inner >= CELL
        val avatar = if (labels) AVATAR else inner.coerceIn(SMALLEST_AVATAR, AVATAR)
        return Fit(columns, rows, titled, labels, avatar)
    }

    fun stateLook(fit: Fit): StateLook = when {
        fit.columns == 1 -> StateLook.ICON
        fit.rows == 1 && !fit.title -> StateLook.TEXT
        else -> StateLook.FULL
    }

    private fun rows(height: Int): Int = if (height < CELL) 0 else (height + GAP) / (CELL + GAP)
}
