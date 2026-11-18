package com.soufiane.ar.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoUiTest {

    private fun demo(id: String, file: String, size: Long, onServer: Boolean = true) =
        DemoRepository.Demo(id, id, "corn", "inspect", file, size, hasTimeline = true, onServer = onServer, local = false)

    @Test
    // a half downloaded file must be fetched again
    fun mergeMarksCompleteDownloadsAsLocal() {
        val remote = listOf(demo("a", "a.mp4", 100), demo("b", "b.mp4", 200), demo("gone", "g.mp4", 0, onServer = false))
        val merged = DemoRepository.merge(remote, mapOf("a.mp4" to 100L, "b.mp4" to 150L, "own.mp4" to 42L))
        assertEquals(listOf("a", "b", "local:own.mp4"), merged.map { it.id })
        assertTrue(merged[0].local)
        assertFalse(merged[1].local)
        assertTrue(merged[2].local && !merged[2].onServer)
        assertEquals("own", merged[2].title)
    }

    @Test
    fun mergeWithoutServerShowsTheLocalVideos() {
        val merged = DemoRepository.merge(emptyList(), mapOf("x.mp4" to 1L))
        assertEquals(listOf("local:x.mp4"), merged.map { it.id })
    }

    @Test
    fun expectedAnswerByPosition() {
        val t = Timeline(listOf(Timeline.Hold(500, 6500, "Corn leaf blight"), Timeline.Hold(7700, 13700, "Corn rust leaf")))
        assertEquals("Corn leaf blight", t.expectedAt(450))
        assertEquals("Corn leaf blight", t.expectedAt(6500))
        assertNull(t.expectedAt(7000))
        assertEquals("Corn rust leaf", t.expectedAt(8125))
        assertEquals("expected: Corn rust leaf  ✓", Timeline.verdictLine("Corn rust leaf", "Corn rust leaf"))
        assertEquals("expected: Corn rust leaf  ✗", Timeline.verdictLine("Corn rust leaf", "Corn Gray leaf spot"))
    }

    @Test
    fun newSettingsAreValidated() {
        assertEquals(8090, AppSettings().apiPort)
        assertEquals("welcome", AppSettings().startScreen)
        assertEquals(8090, AppSettings(apiPort = 0).validated().apiPort)
        assertEquals("welcome", AppSettings(startScreen = "splash").validated().startScreen)
        assertEquals("inspect", AppSettings(startScreen = "inspect").validated().startScreen)
        assertTrue(AppSettings().showCamera)
        assertTrue(AppSettings().showBoxes)
        assertEquals(listOf("show_boxes"), AppSettings.changedKeys(AppSettings(), AppSettings(showBoxes = false)))
        assertEquals(listOf("show_camera"), AppSettings.changedKeys(AppSettings(), AppSettings(showCamera = false)))
        assertEquals(listOf("api_port", "start_screen"),
            AppSettings.changedKeys(AppSettings(), AppSettings(apiPort = 9000, startScreen = "inspect")))
    }
}
