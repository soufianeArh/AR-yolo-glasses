package com.soufiane.ar.glasses

import android.util.Log
import com.soufiane.ar.proto.v1.FrameResult
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Step 8.7: a connection to the Java scheduler that repairs itself.
 *
 * Wraps one [BackendClient] (= one gRPC stream) at a time. When the stream breaks (scheduler
 * restarted, network gone, server not up yet), it waits and opens a new one: 1, 2, 4, 8, then
 * every 10 s, until it works - no app restart needed.
 *
 *   CONNECTING ──(channel READY)──► CONNECTED ──(stream error)──► DISCONNECTED ──(timer)──► CONNECTING
 *
 * - [send] returns false while not connected: the caller drops the frame (no queue of stale frames).
 * - Answers from an older, broken stream are ignored: nothing late or stale reaches the screen.
 *
 * Logcat (tag ARGlasses): CONN_STATE lines with the reason and the next retry, for tests.
 */
class Connection(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val onResult: (FrameResult, Long) -> Unit,
    private val backoffMs: List<Long> = listOf(1000, 2000, 4000, 8000, 10000),
    /** An attempt not connected by then counts as failed (8.7: an attempt made while the scheduler
     *  was down hung forever - no error, no success - even after it was back). */
    private val connectTimeoutMs: Long = 5000,
    private val onState: (State, String) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "connection") }
    private var client: BackendClient? = null
    private var readyCheck: ScheduledFuture<*>? = null
    private var attempt = 0
    private var closed = false
    @Volatile var state = State.DISCONNECTED
        private set

    fun start() = timer.execute { connect() }

    enum class SendResult { SENT, NOT_CONNECTED, NETWORK_BUSY }

    /** Sends a frame if connected and the network keeps up; otherwise the caller drops it. */
    fun trySend(frameId: Long, image: ByteArray, models: List<String>): SendResult {
        val c = synchronized(lock) { if (state == State.CONNECTED) client else null } ?: return SendResult.NOT_CONNECTED
        if (!c.canSend()) {
            Log.d(MainActivity.TAG, "NET_BUSY frame=$frameId not sent (earlier frames still uploading)")
            return SendResult.NETWORK_BUSY
        }
        c.send(frameId, image, models)
        return SendResult.SENT
    }

    fun send(frameId: Long, image: ByteArray, models: List<String>): Boolean =
        trySend(frameId, image, models) == SendResult.SENT

    /**
     * Throws the current stream away and reconnects (8.7): used when a frame got no answer in time.
     * On a slow link the unanswered frames sit in the socket buffer and would arrive later, stale and
     * all together (seen: SESSION_LIMIT bursts); a new stream drops them, the scheduler cancels them.
     */
    fun reset(why: String) {
        timer.execute {
            val c = synchronized(lock) { if (state == State.CONNECTED) client else null } ?: return@execute
            attempt = 0                                       // a fresh retry: 1 s
            lost(c, java.io.IOException(why))
        }
    }

    fun close() {
        val c = synchronized(lock) {
            closed = true
            readyCheck?.cancel(false)
            client.also { client = null }
        }
        timer.shutdownNow()
        c?.close()
    }

    // ------------------------------------------------------------------ inside the timer thread

    private fun connect() {
        val c: BackendClient
        synchronized(lock) {
            if (closed) return
            setState(State.CONNECTING, "attempt ${attempt + 1} to $host:$port")
            lateinit var self: BackendClient
            self = BackendClient(host, port, token,
                { r, rtt -> if (isCurrent(self)) onResult(r, rtt) },          // old stream: ignored
                { e -> timer.execute { lost(self, e) } })
            c = self
            client = c
        }
        // The stream opens lazily; poll the channel until it is READY (or the stream fails).
        val started = System.currentTimeMillis()
        readyCheck = timer.scheduleWithFixedDelay({
            var timedOut = false
            synchronized(lock) {
                if (closed || client !== c || state != State.CONNECTING) {
                    readyCheck?.cancel(false); return@scheduleWithFixedDelay
                }
                if (c.isReady()) {
                    attempt = 0
                    readyCheck?.cancel(false)
                    setState(State.CONNECTED, "after ${System.currentTimeMillis() - started} ms")
                } else if (System.currentTimeMillis() - started > connectTimeoutMs) {
                    readyCheck?.cancel(false)
                    timedOut = true
                }
            }
            if (timedOut) lost(c, java.util.concurrent.TimeoutException("not connected after $connectTimeoutMs ms"))
        }, 100, 200, TimeUnit.MILLISECONDS)
    }

    private fun lost(c: BackendClient, e: Throwable) {
        val delay: Long
        synchronized(lock) {
            if (closed || client !== c) return                   // an old stream, already replaced
            client = null
            readyCheck?.cancel(false)
            delay = backoffMs[attempt.coerceAtMost(backoffMs.size - 1)]
            attempt++
            setState(State.DISCONNECTED, "${e.javaClass.simpleName}: ${e.message?.take(120)}; retry in $delay ms")
        }
        c.closeQuietly()
        timer.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun isCurrent(c: BackendClient) = synchronized(lock) { client === c && !closed }

    private fun setState(s: State, why: String) {
        state = s
        Log.i(MainActivity.TAG, "CONN_STATE $s $why")
        onState(s, why)
    }
}
