package fold.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {
    private fun say(s: String) = SpeechText.forVoice(s)
    @Test fun roadNumbersAsPeopleSayThem() {
        assertEquals("two eighty-seven", SpeechText.roadNumber(287))
        assertEquals("two oh five", SpeechText.roadNumber(205))
        assertEquals("ninety", SpeechText.roadNumber(90))
        assertEquals("ten oh one", SpeechText.roadNumber(1001))
        assertEquals("fifteen hundred", SpeechText.roadNumber(1500))
        assertEquals("twenty twenty", SpeechText.roadNumber(2020))
        assertEquals("three hundred", SpeechText.roadNumber(300))
        assertEquals("Left onto U S two eighty-seven", say("Left onto US-287"))
        assertEquals("Keep right for I ninety", say("Keep right for I-90"))
        assertEquals("Right onto county road fourteen B", say("Right onto CR 14B"))
        assertEquals("Left onto M T eighty-four", say("Left onto MT-84"))
        assertEquals("forest road ten oh one", say("FS 1001"))
    }
    @Test fun aRollChartsCapitalsReadAsWords() {
        assertEquals("right on ranch road", say("R ON RANCH RD"))
        assertEquals("keep left at fork, cattle guard", say("KEEP L AT FORK - CATTLE GUARD"))
        assertEquals("left at T, B L M gate", say("L AT T, BLM GATE !!"))
        assertEquals("right on M T eighty-four", say("R ON MT-84"))
    }
    @Test fun ordinaryTextIsLeftAlone() {
        assertEquals("Washout after the cattle guard", say("Washout after the cattle guard"))
        assertEquals("Left onto Main Street", say("Left onto Main Street"))
        assertEquals("Gas in 5 miles", say("Gas in 5 mi"))
        assertEquals("Turn at 12 o'clock", say("Turn at 12 o'clock"))
        assertEquals("Ranch road and Bob's gate", say("Ranch Rd & Bob's gate"))
        assertEquals("Danger. Washout", say("Danger. Washout"))
        assertEquals("Left", say("Left"))
    }
}
