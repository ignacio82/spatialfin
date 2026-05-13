package dev.jdtech.jellyfin.cast.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssStyleDetectorTest {

    @Test
    fun `plain dialogue without override tags is not styled`() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,52

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello there.
            Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,How are you?
            Dialogue: 0,0:00:07.00,0:00:09.00,Default,,0,0,0,,Just fine — thanks.
        """.trimIndent()
        assertFalse("Plain dialogue should not trigger burn-in", AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `positioning tag flags as styled`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:00.00,0:01:03.00,Sign,,0,0,0,,{\an7\pos(120,80)\b1}EAST DISTRICT
        """.trimIndent()
        assertTrue("ASS with \\pos must be flagged styled", AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `karaoke tag flags as styled`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:10.00,0:00:15.00,OP,,0,0,0,,{\k20}Yo{\k15}ru{\k25}no{\k20}u{\k30}ta
        """.trimIndent()
        assertTrue("Karaoke (\\k) must be flagged styled", AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `rotation tag flags as styled`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Sign,,0,0,0,,{\frz45}Tilted billboard
        """.trimIndent()
        assertTrue("Rotation (\\frz) must be flagged styled", AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `move animation flags as styled`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\move(100,200,300,400)}Sliding sign
        """.trimIndent()
        assertTrue(AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `transition tag flags as styled`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\t(0,1000,\fscx150)}Growing text
        """.trimIndent()
        assertTrue(AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `blank content returns false`() {
        assertFalse(AssStyleDetector.isStyled(""))
        assertFalse(AssStyleDetector.isStyled("   "))
    }

    @Test
    fun `non-dialogue lines are ignored`() {
        val ass = """
            ; This is a comment with \pos and \k tags
            [Script Info]
            ; \frz45 lives in a comment
            Title: \move(0,0,1,1) is in the title
        """.trimIndent()
        assertFalse("Tags outside Dialogue lines must not flag styled", AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `clip tag flags as styled`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Sign,,0,0,0,,{\clip(0,0,100,100)}Masked text
        """.trimIndent()
        assertTrue(AssStyleDetector.isStyled(ass))
    }

    @Test
    fun `detector stops after fifty dialogue lines`() {
        // Build 60 plain lines + 1 styled line at the very end. The detector caps at 50 so the
        // styled tag after the cap should NOT be seen — the policy treats this as plain. This
        // is the expected behaviour: anime typesetting happens early; if we haven't seen it by
        // line 50 we accept the small false-negative risk to keep the detector cheap.
        val sb = StringBuilder()
        sb.append("[Events]\n")
        sb.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        repeat(60) { i ->
            sb.append("Dialogue: 0,0:00:0$i.00,0:00:0$i.50,Default,,0,0,0,,Line $i\n")
        }
        sb.append("Dialogue: 0,0:01:00.00,0:01:03.00,Sign,,0,0,0,,{\\pos(10,10)}LATE STYLE\n")
        assertFalse("Detector should cap at 50 dialogue lines", AssStyleDetector.isStyled(sb.toString()))
    }
}
