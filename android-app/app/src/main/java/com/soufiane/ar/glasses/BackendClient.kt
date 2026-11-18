package com.soufiane.ar.glasses

import com.google.protobuf.ByteString
import com.soufiane.ar.proto.v1.Frame
import com.soufiane.ar.proto.v1.FrameResult
import com.soufiane.ar.proto.v1.FrameStreamGrpc
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import java.util.concurrent.TimeUnit

/**
 * The connection to the Java scheduler: one gRPC two-way stream (FrameStream.Stream) per session,
 * exactly as defined in the shared contract (proto/ar/v1/detection.proto).
 *
 * One stream, no repair: [Connection] (8.7) replaces a broken client with a new one.
 */
class BackendClient(
    host: String,
    port: Int,
    deviceToken: String,
    private val onResult: (FrameResult, Long) -> Unit,   // result + round trip ms (client clock)
    private val onError: (Throwable) -> Unit,
) {
    private val channel: ManagedChannel = OkHttpChannelBuilder.forAddress(host, port)
        .usePlaintext()                       // development backend, no TLS yet
        .maxInboundMessageSize(8 * 1024 * 1024)
        .build()
    private val sentAt = HashMap<Long, Long>()
    private val requests: ClientCallStreamObserver<Frame>

    init {
        val headers = Metadata().apply {
            put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), deviceToken)
        }
        val stub = FrameStreamGrpc.newStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
        requests = stub.stream(object : StreamObserver<FrameResult> {
            override fun onNext(r: FrameResult) {
                val t0 = synchronized(sentAt) { sentAt.remove(r.frameId) }
                onResult(r, if (t0 != null) System.currentTimeMillis() - t0 else -1)
            }
            override fun onError(t: Throwable) {
                this@BackendClient.onError(t)     // the client's handler, not this method
            }
            override fun onCompleted() {}
        }) as ClientCallStreamObserver<Frame>
    }

    /** Send one frame. [models] picks the model: "plant" (leaf diseases) or "default" (COCO). */
    fun send(frameId: Long, image: ByteArray, models: List<String>) {
        val now = System.currentTimeMillis()
        synchronized(sentAt) { sentAt[frameId] = now }
        val frame = Frame.newBuilder()
            .setFrameId(frameId)
            .setCapturedAtMs(now)
            .setJpeg(ByteString.copyFrom(image))
            .addAllModels(models)
            .build()
        synchronized(requests) { requests.onNext(frame) }
    }

    /**
     * Backpressure (8.7): false while earlier frames are still in the send buffer (slow network).
     * Sending more then only queues frames that arrive late and together (seen on emulated EDGE:
     * 6 frames in one burst after ~70 s, 5 refused with SESSION_LIMIT).
     */
    fun canSend(): Boolean = requests.isReady

    /** The channel is connected to the scheduler. */
    fun isReady(): Boolean = channel.getState(true) == ConnectivityState.READY     // true: connect if idle

    /** Close without waiting for a broken stream (it is already dead). */
    fun closeQuietly() {
        try {
            channel.shutdownNow()
        } catch (_: Exception) {
        }
    }

    fun close() {
        try {
            synchronized(requests) { requests.onCompleted() }
        } catch (_: Exception) {
        }
        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS)
    }
}
