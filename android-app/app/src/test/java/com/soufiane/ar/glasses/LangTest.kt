package com.soufiane.ar.glasses

import com.soufiane.ar.glasses.InspectController.Kind
import com.soufiane.ar.glasses.InspectController.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LangTest {

    @After
    // lang is global so reset it for the other tests
    fun backToEnglish() {
        Lang.current = "en"
    }

    @Test
    fun chinesePopups() {
        Lang.current = "zh"
        val disease = PopupRules.forVerdict(Verdict(Kind.DISEASE, "Corn leaf blight", 0.97f, 2, 2, null), 0.4f)
        assertEquals("玉米大斑病  0.97\n2/2 帧一致", disease.text)
        assertEquals("健康叶片（番茄叶）", Verdict(Kind.HEALTHY, "Tomato leaf", 0.8f, 1, 1, null).text)
        assertEquals("不是玉米、马铃薯或番茄的叶片", Verdict(Kind.OTHER_PLANT, "Apple Scab Leaf", 0.95f, 0, 1, null,
            note = "corn,potato,tomato").text)
        assertEquals("网络太慢 - 请重试", Verdict(Kind.FAILED, "-", 0f, 0, 0, null, note = "net_slow").text)
        assertTrue(PopupRules.forVerdict(Verdict(Kind.DISEASE, "Corn rust leaf", 0.3f, 1, 1, null), 0.4f).text
            .startsWith("不确定 - 请靠近一点"))
        assertEquals("正确答案：马铃薯晚疫病  ✗", Timeline.verdictLine("Potato leaf late blight", "Blueberry leaf"))
    }

    @Test
    fun englishStaysAsBefore() {
        assertEquals("Not a corn, potato or tomato leaf", Verdict(Kind.OTHER_PLANT, "Apple Scab Leaf", 0.95f, 0, 1, null,
            note = "corn,potato,tomato").text)
        assertEquals("Network too slow - try again", Verdict(Kind.FAILED, "-", 0f, 0, 0, null, note = "net_slow").text)
        assertEquals("Bell pepper leaf spot", Lang.label("Bell_pepper leaf spot"))
    }

    @Test
    fun everyModelLabelHasAChineseName() {
        Lang.current = "zh"
        val plant = listOf("leaves", "Apple Scab Leaf", "Apple leaf", "Apple rust leaf", "Bell_pepper leaf",
            "Bell_pepper leaf spot", "Blueberry leaf", "Cherry leaf", "Corn Gray leaf spot", "Corn leaf blight",
            "Corn rust leaf", "Peach leaf", "Potato leaf", "Potato leaf early blight", "Potato leaf late blight",
            "Raspberry leaf", "Soyabean leaf", "Soybean leaf", "Squash Powdery mildew leaf", "Strawberry leaf",
            "Tomato Early blight leaf", "Tomato Septoria leaf spot", "Tomato leaf", "Tomato leaf bacterial spot",
            "Tomato leaf late blight", "Tomato leaf mosaic virus", "Tomato leaf yellow virus", "Tomato mold leaf",
            "Tomato two spotted spider mites leaf", "grape leaf", "grape leaf black rot")
        for (l in plant) assertTrue(l, Lang.label(l) != l)
        for (l in listOf("person", "cow", "sheep", "dog", "toothbrush", "traffic light")) assertTrue(l, Lang.label(l) != l)
    }

    @Test
    fun languageSetting() {
        assertEquals("en", AppSettings().language)
        assertEquals("zh", AppSettings(language = "zh").validated().language)
        assertEquals("en", AppSettings(language = "fr").validated().language)
        assertEquals(listOf("language"), AppSettings.changedKeys(AppSettings(), AppSettings(language = "zh")))
    }
}
