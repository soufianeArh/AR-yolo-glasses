package com.soufiane.ar.glasses

import com.soufiane.ar.glasses.InspectController.Kind
import com.soufiane.ar.glasses.InspectController.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupRulesTest {

    private fun verdict(kind: Kind, label: String = "Corn rust leaf", conf: Float = 0.9f, agree: Int = 2, frames: Int = 2) =
        Verdict(kind, label, conf, agree, frames, if (kind == Kind.UNCERTAIN) "Corn leaf blight" else null)

    @Test
    fun diseaseAboveThreshold() {
        val p = PopupRules.forVerdict(verdict(Kind.DISEASE), 0.40f)
        assertTrue(p.sure)
        assertEquals(PopupRules.ORANGE, p.color)
        assertTrue(p.text, p.text.startsWith("Corn rust leaf  0.90"))
        assertTrue(p.text, p.text.endsWith("2/2 frames agree"))
    }

    @Test
    fun belowThresholdIsNotSure() {
        val p = PopupRules.forVerdict(verdict(Kind.DISEASE, conf = 0.35f), 0.40f)
        assertFalse(p.sure)
        assertEquals(PopupRules.YELLOW, p.color)
        assertTrue(p.text, p.text.startsWith("Not sure - look closer"))
        assertTrue(p.text, p.text.contains("Corn rust leaf 0.35"))
    }

    @Test
    fun singleFrameHasNoAgreementLine() {
        val p = PopupRules.forVerdict(verdict(Kind.HEALTHY, "Tomato leaf", agree = 1, frames = 1), 0.40f)
        assertEquals("Healthy leaf (Tomato leaf)", p.text)
        assertEquals(PopupRules.GREEN, p.color)
    }

    @Test
    fun noneAndFailedAreGreyWithoutAgreement() {
        val none = PopupRules.forVerdict(Verdict(Kind.NONE, "-", 0f, 0, 2, null), 0.40f)
        assertEquals("No leaf disease found", none.text)
        assertEquals(PopupRules.GREY, none.color)
        val failed = PopupRules.forVerdict(Verdict(Kind.FAILED, "-", 0f, 0, 1, null), 0.40f)
        assertEquals("Check failed - try again", failed.text)
    }

    @Test
    fun uncertainIsYellow() {
        val p = PopupRules.forVerdict(verdict(Kind.UNCERTAIN, agree = 1), 0.40f)
        assertEquals(PopupRules.YELLOW, p.color)
        assertTrue(p.text, p.text.startsWith("Unsure: Corn rust leaf or Corn leaf blight?"))
    }

    @Test
    // this is what stops the flicker in stream mode
    fun streamNeedsTheSameTopTwiceInARow() {
        val s = PopupRules.StableTop(0.5f, emptySet())
        assertNull(s.next(listOf("person" to 0.9f, "cow" to 0.6f)))
        assertEquals("person" to 0.92f, s.next(listOf("person" to 0.92f)))
        assertEquals("person", s.next(listOf("person" to 0.8f))!!.first)
        assertNull(s.next(listOf("cow" to 0.7f)))
        assertNull(s.next(listOf("person" to 0.9f)))
    }

    @Test
    fun streamIgnoresWeakAndFilteredClasses() {
        val s = PopupRules.StableTop(0.5f, setOf("cow"))
        repeat(3) { assertNull(s.next(listOf("person" to 0.95f, "cow" to 0.4f))) }
        assertNull(s.next(listOf("person" to 0.95f, "cow" to 0.7f)))
        assertEquals("cow", s.next(listOf("person" to 0.95f, "cow" to 0.7f))!!.first)
    }

    @Test
    fun settingsAreValidated() {
        val s = AppSettings(host = "  ", port = 70000, source = "video:", mode = "dance", model = "x",
            fps = 99, votes = 0, minConf = 1.5f).validated()
        assertEquals(AppSettings(), s.copy(fps = 8, votes = 2, minConf = -1f))
        assertEquals(30, s.fps)
        assertEquals(1, s.votes)
        assertEquals(1f, s.minConf)
    }

    @Test
    fun cameraIsAValidSource() {
        assertEquals("camera", AppSettings(source = "camera").validated().source)
        assertEquals("video:x.mp4", AppSettings(source = " video:x.mp4 ").validated().source)
        assertEquals("test-image", AppSettings(source = "webcam").validated().source)
    }

    @Test
    fun perModelThresholdUnlessSet() {
        assertEquals(0.40f, AppSettings().minConfFor("plant"))
        assertEquals(0.50f, AppSettings().minConfFor("default"))
        assertEquals(0.7f, AppSettings(minConf = 0.7f).minConfFor("plant"))
    }

    @Test
    fun popupClassesParsingAndChangedKeys() {
        assertEquals(setOf("person", "cow"), AppSettings.parseClasses(" Person, cow ,,"))
        assertEquals(listOf("host", "fps"),
            AppSettings.changedKeys(AppSettings(), AppSettings(host = "172.22.36.122", fps = 4)))
    }
}
