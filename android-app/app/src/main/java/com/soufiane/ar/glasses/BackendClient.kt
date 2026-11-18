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

class BackendClient(
    host: String,
    port: Int,
    deviceToken: String,
    private val onResult: (FrameResult, Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val channel: ManagedChannel = OkHttpChannelBuilder.forAddress(host, port)
        .usePlaintext()
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
            // round trip is measured with the client clock only
            override fun onNext(r: FrameResult) {
                val t0 = synchronized(sentAt) { sentAt.remove(r.frameId) }
                onResult(r, if (t0 != null) System.currentTimeMillis() - t0 else -1)
            }
            override fun onError(t: Throwable) {
                this@BackendClient.onError(t)
            }
            override fun onCompleted() {}
        }) as ClientCallStreamObserver<Frame>
    }

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

    // false while older frames are still waiting in the send buffer
    fun canSend(): Boolean = requests.isReady

    fun isReady(): Boolean = channel.getState(true) == ConnectivityState.READY

    // the stream is already dead so no need to wait for it
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
