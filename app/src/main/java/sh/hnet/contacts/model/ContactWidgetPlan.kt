package sh.hnet.contacts.model

/**
 * How the one-contact widget lays out at the size the launcher gives it and what its buttons do (SPEC §14.2, the owner's decisions of 18 Nov 2026: a 1 × 1 look with the button chosen at setup, the name pill on the top end corner). All sizes in dp.
 */
object ContactWidgetPlan {
    /** Two cells by the platform's formula at the least (73 × 2 − 16 = 130) with some to spare: below it the widget is one cell in that direction. */
    const val TWO_CELLS = 110

    /** Room around the pieces for their shadows. */
    const val ROOM = 2

    /** The buttons at 2 × 2, over the circle's lower edge by half. */
    const val SQUARE_BUTTON = 48

    /** The buttons at 2 × 1, stacked, 4 dp apart, over the circle's end edge by half. */
    const val WIDE_BUTTON = 40
    const val WIDE_GAP = 4

    /** The one button at 1 × 1, from 24 to 36 dp, 40 % of the circle. */
    const val SMALL_BUTTON = 36
    const val SMALLEST_BUTTON = 24

    enum class Look { SQUARE, WIDE, SMALL }

    /** @param circle The photo's diameter; @param button The buttons' size */
    data class Layout(val look: Look, val circle: Int, val button: Int)

    fun layout(width: Int, height: Int): Layout {
        val wide = width >= TWO_CELLS
        val tall = height >= TWO_CELLS
        return when {
            wide && tall -> Layout(Look.SQUARE, minOf(width - 2 * ROOM, height - 2 * ROOM - SQUARE_BUTTON / 2), SQUARE_BUTTON)
            wide -> {
                val button = ((height - 2 * ROOM - WIDE_GAP) / 2).coerceIn(SMALLEST_BUTTON, WIDE_BUTTON)
                Layout(Look.WIDE, minOf(height - 2 * ROOM, width - 2 * ROOM - button / 2), button)
            }
            else -> {
                val circle = minOf(width, height) - 2 * ROOM
                Layout(Look.SMALL, circle, (circle * 2 / 5).coerceIn(SMALLEST_BUTTON, SMALL_BUTTON))
            }
        }
    }

    /** The button the widget shows at 1 × 1, chosen when it is set up; Call when nothing was chosen. */
    enum class SmallButton(val key: String) {
        CALL("call"), MESSAGE("message"), NONE("none");

        companion object {
            fun of(key: String?): SmallButton = entries.firstOrNull { it.key == key } ?: CALL
        }
    }

    /** What Call or Message does with a contact's numbers (SPEC §14.2, §6.3.1). */
    sealed interface Number {
        /** The default or the only number: straight to the dialer or the messages. */
        data class Direct(val value: String) : Number

        /** Several numbers and none is the default: the contact's page with the number sheet open. */
        data object Ask : Number

        /** No number: the buttons aren't there. */
        data object None : Number
    }

    /** @param numbers The contact's numbers, each with whether it is the default */
    fun number(numbers: List<Pair<String, Boolean>>): Number {
        numbers.firstOrNull { it.second }?.let { return Number.Direct(it.first) }
        return when (numbers.size) {
            0 -> Number.None
            1 -> Number.Direct(numbers[0].first)
            else -> Number.Ask
        }
    }
}
