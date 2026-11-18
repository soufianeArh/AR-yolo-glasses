package com.soufiane.ar.glasses

import com.soufiane.ar.glasses.InspectController.Kind
import com.soufiane.ar.glasses.InspectController.State
import com.soufiane.ar.proto.v1.Detection
import com.soufiane.ar.proto.v1.FrameResult
import com.soufiane.ar.proto.v1.ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InspectControllerTest {

    // thumbnails are faked directly so no bitmaps are needed
    private val a = IntArray(880) { 10 }
    private val a2 = IntArray(880) { 12 }
    private val b = IntArray(880) { 200 }

    private val sent = mutableListOf<Long>()
    private var canSend = true
    private var lostCalls = 0
    private var now = 0L
    private var last: InspectController.Snapshot<IntArray>? = null
    private lateinit var c: InspectController<IntArray>

    @Before
    fun setUp() {
        c = InspectController(
            send = { id, _, models -> assertEquals(listOf("plant"), models); if (canSend) sent += id; canSend },
            thumb = { it }, encode = { ByteArray(1) },
            voteFrames = 2, crops = setOf("corn", "tomato", "potato"), clock = { now },
            onLost = { lostCalls++ }, onUpdate = { last = it },
        )
    }

    private fun frames(f: IntArray, n: Int) = repeat(n) { now += 125; c.onFrame(f, now) }

    private fun result(id: Long, label: String?, conf: Float = 0.9f, status: ResultStatus = ResultStatus.OK) =
        FrameResult.newBuilder().setFrameId(id).setStatus(status).apply {
            if (label != null) addDetections(Detection.newBuilder().setLabel(label).setConfidence(conf))
        }.build()

    @Test
    fun noInspectionWhileTheViewMoves() {
        repeat(10) { frames(a, 1); frames(b, 1) }
        assertTrue(sent.isEmpty())
        assertEquals(State.MOVING, c.currentState)
    }

    @Test
    fun holdStartsACheckAfterFourSteadyFrames() {
        frames(a, 1)
        frames(a2, 3)
        assertTrue(sent.isEmpty())
        frames(a, 1)
        assertEquals(listOf(1L), sent)
        assertEquals(State.CHECKING, c.currentState)
    }

    @Test
    fun twoAgreeingVotesThenShowingAndNoRecheckOfTheSameLeaf() {
        frames(a, 5)
        c.onResult(result(1, "Corn rust leaf", 0.8f), 100)
        frames(a, 1)
        assertEquals(listOf(1L, 2L), sent)
        c.onResult(result(2, "Corn rust leaf", 0.9f), 100)
        assertEquals(State.SHOWING, c.currentState)
        val v = last!!.verdict!!
        assertEquals(Kind.DISEASE, v.kind)
        assertEquals("Corn rust leaf", v.label)
        assertEquals(2, v.agree)
        frames(a, 20)
        assertEquals(2, sent.size)
        frames(b, 5)
        assertEquals(3, sent.size)
    }

    @Test
    // this one used to get stuck forever
    fun movingBetweenTwoVotesStillEndsTheCheck() {
        frames(a, 5)
        c.onResult(result(1, "Potato leaf late blight", 0.7f), 100)
        frames(b, 1)
        assertEquals(State.MOVING, c.currentState)
        val v = last!!.verdict!!
        assertEquals("Potato leaf late blight", v.label)
        assertEquals(1, v.frames)
        frames(b, 4)
        assertEquals(listOf(1L, 2L), sent)
    }

    @Test
    fun noAnswerWithinTheTimeoutGivesFailed() {
        frames(a, 5)
        now += 12_001
        c.onFrame(a, now)
        assertEquals(Kind.FAILED, last!!.verdict!!.kind)
        assertEquals("Network too slow - try again", last!!.verdict!!.text)
        assertEquals(1, lostCalls)
        assertEquals(State.SHOWING, c.currentState)
    }

    @Test
    fun aFailedFrameEndsTheCheckAtOnce() {
        frames(a, 5)
        c.onResult(result(1, null, status = ResultStatus.DROPPED_DEADLINE), 100)
        assertEquals(Kind.FAILED, last!!.verdict!!.kind)
        assertEquals(1, sent.size)
    }

    @Test
    fun lateAnswersOfOtherFramesAreIgnored() {
        frames(a, 5)
        c.onResult(result(99, "Corn rust leaf"), 100)
        assertNull(last!!.verdict)
        assertEquals(State.CHECKING, c.currentState)
    }

    @Test
    fun unsendableFrameEndsTheCheckWithTheReason() {
        canSend = false
        frames(a, 5)
        val v = last!!.verdict!!
        assertEquals(Kind.FAILED, v.kind)
        assertEquals("Network too slow - try again", v.text)
        assertEquals(State.SHOWING, c.currentState)
        canSend = true
        frames(a, 10)
        assertTrue(sent.isEmpty())
        frames(b, 5)
        assertEquals(1, sent.size)
    }

    @Test
    fun lostStreamEndsTheCheck() {
        frames(a, 5)
        c.onStreamLost()
        assertEquals(Kind.FAILED, last!!.verdict!!.kind)
    }

    @Test
    fun onlyTheLastCheckedLeafIsRemembered() {
        frames(a, 5)
        c.onResult(result(1, "Corn rust leaf", 0.8f), 100)
        frames(a, 1)
        c.onResult(result(2, "Corn rust leaf", 0.9f), 100)
        frames(b, 5)
        c.onResult(result(3, "Corn leaf blight", 0.8f), 100)
        frames(b, 1)
        c.onResult(result(4, "Corn leaf blight", 0.8f), 100)
        assertEquals(4, sent.size)
        frames(a2, 5)
        assertEquals(5, sent.size)
    }

    @Test
    fun sameViewReuseOnlyForTheLastLeafAndOnlyRecently() {
        frames(a, 5)
        c.onResult(result(1, "Corn rust leaf", 0.8f), 100)
        frames(a, 1)
        c.onResult(result(2, "Corn rust leaf", 0.9f), 100)
        val firstHold = last!!.verdictHold
        frames(b, 1); frames(a2, 5)
        assertEquals(2, sent.size)
        assertTrue(last!!.verdictHold > firstHold)
        assertEquals("Corn rust leaf", last!!.verdict!!.label)
        now += 61_000
        frames(b, 1); frames(a, 5)
        assertEquals(3, sent.size)
    }

    @Test
    fun voteKeepsOnlyOurCrops() {
        val crops = setOf("corn", "tomato", "potato")
        val twoDets = FrameResult.newBuilder().setFrameId(1).setStatus(ResultStatus.OK)
            .addDetections(Detection.newBuilder().setLabel("Blueberry leaf").setConfidence(0.82f))
            .addDetections(Detection.newBuilder().setLabel("Potato leaf late blight").setConfidence(0.41f)).build()
        val v = InspectController.vote(listOf(twoDets), crops)
        assertEquals(Kind.DISEASE, v.kind)
        assertEquals("Potato leaf late blight", v.label)
        val other = InspectController.vote(listOf(result(1, "Apple Scab Leaf", 0.95f)), crops)
        assertEquals(Kind.OTHER_PLANT, other.kind)
        assertEquals("Not a corn, potato or tomato leaf", other.text)
        assertEquals(Kind.DISEASE, InspectController.vote(listOf(result(1, "Apple Scab Leaf", 0.95f))).kind)
    }

    @Test
    fun voteMajorityWins() {
        val v = InspectController.vote(listOf(
            result(1, "Tomato leaf late blight", 0.6f), result(2, "Tomato leaf late blight", 0.7f),
            result(3, "Tomato Early blight leaf", 0.9f)))
        assertEquals(Kind.DISEASE, v.kind)
        assertEquals("Tomato leaf late blight", v.label)
        assertEquals(2, v.agree)
        assertEquals(0.7f, v.confidence)
    }

    @Test
    fun voteTieIsUncertain() {
        val v = InspectController.vote(listOf(result(1, "Corn rust leaf", 0.6f), result(2, "Corn leaf blight", 0.5f)))
        assertEquals(Kind.UNCERTAIN, v.kind)
        assertEquals("Corn rust leaf", v.label)
        assertEquals("Corn leaf blight", v.second)
    }

    @Test
    fun voteHealthyNoneAndFailed() {
        assertEquals(Kind.HEALTHY, InspectController.vote(listOf(result(1, "Tomato leaf"))).kind)
        assertEquals(Kind.NONE, InspectController.vote(listOf(result(1, null), result(2, null))).kind)
        assertEquals(Kind.FAILED, InspectController.vote(listOf(
            result(1, null, status = ResultStatus.WORKER_FAILED))).kind)
        assertEquals(Kind.FAILED, InspectController.vote(emptyList()).kind)
    }

    @Test
    fun diseaseWords() {
        listOf("Corn rust leaf", "Corn Gray leaf spot", "Tomato leaf mosaic virus", "Squash Powdery mildew leaf",
            "Tomato mold leaf", "Apple Scab Leaf", "Tomato leaf bacterial spot", "Tomato two spotted spider mites leaf")
            .forEach { assertTrue(it, InspectController.isDisease(it)) }
        listOf("Tomato leaf", "Blueberry leaf", "Potato leaf", "Corn leaf")
            .forEach { assertTrue(it, !InspectController.isDisease(it)) }
    }
}
