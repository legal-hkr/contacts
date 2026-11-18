package sh.hnet.contacts.model

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.contacts.model.ContactWidgetPlan.Look
import sh.hnet.contacts.model.ContactWidgetPlan.Number
import sh.hnet.contacts.model.ContactWidgetPlan.SmallButton

class ContactWidgetPlanTest {
    @Test
    fun twoByTwoLeavesHalfAButtonUnderTheCircle() {
        // Two cells by the platform's formula: 130 × 130
        assertEquals(ContactWidgetPlan.Layout(Look.SQUARE, 102, 48), ContactWidgetPlan.layout(130, 130))
        // Wider than high: the height decides
        assertEquals(152, ContactWidgetPlan.layout(300, 180).circle)
    }

    @Test
    fun twoByOneStacksTheButtonsBesideTheCircle() {
        assertEquals(ContactWidgetPlan.Layout(Look.WIDE, 96, 40), ContactWidgetPlan.layout(200, 100))
        // A low cell: smaller buttons, never under 24
        assertEquals(24, ContactWidgetPlan.layout(130, 57).button)
    }

    @Test
    fun oneByOneHasOneSmallButton() {
        assertEquals(ContactWidgetPlan.Layout(Look.SMALL, 76, 30), ContactWidgetPlan.layout(80, 100))
        assertEquals(Look.SMALL, ContactWidgetPlan.layout(80, 200).look)
        assertEquals(36, ContactWidgetPlan.layout(105, 105).button)
    }

    @Test
    fun theDefaultOrTheOnlyNumberIsDialedAtOnce() {
        assertEquals(Number.Direct("2"), ContactWidgetPlan.number(listOf("1" to false, "2" to true)))
        assertEquals(Number.Direct("1"), ContactWidgetPlan.number(listOf("1" to false)))
        assertEquals(Number.Ask, ContactWidgetPlan.number(listOf("1" to false, "2" to false)))
        assertEquals(Number.None, ContactWidgetPlan.number(emptyList()))
    }

    @Test
    fun anUnknownButtonIsCall() {
        assertEquals(SmallButton.MESSAGE, SmallButton.of("message"))
        assertEquals(SmallButton.CALL, SmallButton.of(null))
        assertEquals(SmallButton.CALL, SmallButton.of("x"))
    }
}
