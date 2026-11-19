package com.soufiane.ar.glasses

import android.util.Log
import com.soufiane.ar.proto.v1.FrameResult
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// one grpc stream at a time that reconnects by itself
class Connection(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val onResult: (FrameResult, Long) -> Unit,
    // wait 1 2 4 8 and then 10 s between two attempts
    private val backoffMs: List<Long> = listOf(1000, 2000, 4000, 8000, 10000),
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

    // never queues a frame when not connected or when the network is still busy
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

    // a fresh stream drops the frames still stuck in the socket buffer
    fun reset(why: String) {
        timer.execute {
            val c = synchronized(lock) { if (state == State.CONNECTED) client else null } ?: return@execute
            attempt = 0
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

    // opens a new stream and then polls the channel untill it is ready
    private fun connect() {
        val c: BackendClient
        synchronized(lock) {
            if (closed) return
            setState(State.CONNECTING, "attempt ${attempt + 1} to $host:$port")
            lateinit var self: BackendClient
            self = BackendClient(host, port, token,
                // answers from a replaced stream are dropped here
                { r, rtt -> if (isCurrent(self)) onResult(r, rtt) },
                { e -> timer.execute { lost(self, e) } })
            c = self
            client = c
        }
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
                // an attempt can hang forever without any error so give up after a while
                } else if (System.currentTimeMillis() - started > connectTimeoutMs) {
                    readyCheck?.cancel(false)
                    timedOut = true
                }
            }
            if (timedOut) lost(c, java.util.concurrent.TimeoutException("not connected after $connectTimeoutMs ms"))
        }, 100, 200, TimeUnit.MILLISECONDS)
    }

    // only the current stream counts here because an old one is already replaced
    private fun lost(c: BackendClient, e: Throwable) {
        val delay: Long
        synchronized(lock) {
            if (closed || client !== c) return
            client = null
            readyCheck?.cancel(false)
            delay = backoffMs[attempt.coerceAtMost(backoffMs.size - 1)]
            attempt++
            setState(State.DISCONNECTED, "${e.javaClass.simpleName}: ${e.message?.take(120)}; retry in $delay ms")
        }
        c.closeQuietly()
        // try again once the backoff delay is over
        timer.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun isCurrent(c: BackendClient) = synchronized(lock) { client === c && !closed }

    private fun setState(s: State, why: String) {
        state = s
        Log.i(MainActivity.TAG, "CONN_STATE $s $why")
        onState(s, why)
    }
}
