package dev.heartline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTitlesTest {
    private fun facts(vararg ex: Pair<String, Int>, watch: List<String> = emptyList(), cardio: List<String> = emptyList()) =
        TitleFacts(ex.toList(), watch, cardio, null, 1_800_000_000L, 3600, ex.sumOf { it.second })

    @Test fun localNamesFromTheTwoBiggestGroups() {
        assertEquals("Back and biceps day", localTitle(facts("Assisted pull-up" to 4, "Lat pulldown" to 3, "Rear delt fly machine" to 3,
            "Preacher curl machine" to 3, "Machine forearm curl" to 4, "Reverse forearm curl / wrist extension" to 3)))
        assertEquals("Chest and triceps day", localTitle(facts("Bench press (barbell, flat)" to 4, "Incline dumbbell press" to 3, "Pec fly" to 3, "Tricep pushdown" to 4)))
        assertEquals("Leg day", localTitle(facts("Leg press" to 4, "Squat" to 3)))
        // Real names from the logs: "machine" is not a chin-up, and leg raises are core work.
        assertEquals("Chest and triceps day", localTitle(facts("Bench press (barbell, flat)" to 5, "Cable tricep pushdown" to 4, "Machine chest fly" to 4,
            "Machine shoulder press (plate-loaded)" to 3)))
        assertEquals("Back and biceps day", localTitle(facts("Assisted pull-up (machine)" to 5, "Lat pulldown" to 5, "Preacher curl (machine)" to 4,
            "Rear delt machine" to 4, "Leg raises (dip bar / hanging)" to 3, "Treadmill (incline walk)" to 1, "Forearm curl (machine)" to 3)))
        assertEquals("Treadmill", localTitle(facts("Treadmill incline walk" to 2, watch = listOf("Treadmill"))))
        assertEquals("Weights, 12 sets", TitleFacts(emptyList(), emptyList(), emptyList(), null, 0, 3600, 12).let(::localTitle))
    }

    @Test fun genericLabelsGetNamed() {
        assertTrue(genericLabel("Gym")); assertTrue(genericLabel(" workout ")); assertFalse(genericLabel("Leg day with Sam"))
    }

    @Test fun modelRepliesAreCheckedBeforeUse() {
        assertEquals("Back and biceps day", parseTitle("{\"name\": \"Back and biceps day\"}"))
        assertEquals("Pull day", parseTitle("```json\n{\"name\":\"Pull day.\"}\n```"))
        assertNull(parseTitle("{\"name\": \"A very long name that goes on and on\"}"))
        assertNull(parseTitle("not json"))
    }

    @Test fun basisChangesWhenTheLogArrives() {
        val before = TitleFacts(emptyList(), listOf("Strength training"), emptyList(), null, 0, 3600, 21)
        val after = before.copy(exercises = listOf("Lat pulldown" to 3), logName = "Back biceps forearms")
        assertFalse(before.basis == after.basis)
        assertTrue(after.prompt().contains("Lat pulldown (3)"))
    }
}
