package com.bharatdroid.agent.skills.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceParsingSkillTest {

    @Test
    fun `price comparator parses rupee style prices`() {
        val skill = PriceComparatorSkill()

        assertEquals(74900, skill.parsePrice("Amazon price: Rs 74,900 for iPhone"))
        assertEquals(69900, skill.parsePrice("Flipkart price: INR 69,900"))
        assertEquals(180, skill.parsePrice("lowest visible price is \u20B9180"))
        assertNull(skill.parsePrice("could not read price"))
    }

    @Test
    fun `food deal finder parses rupee style prices`() {
        val skill = FoodDealFinderSkill()

        assertEquals(180, skill.parsePrice("Swiggy: Rs 180 at A2B, rating 4.3"))
        assertEquals(210, skill.parsePrice("Zomato: INR 210 at Meghana Foods"))
        assertEquals(99, skill.parsePrice("Dish card shows \u20B999"))
        assertNull(skill.parsePrice("no amount visible"))
    }
}
