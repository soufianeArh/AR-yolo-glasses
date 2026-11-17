package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.HeartbeatAck;
import com.soufiane.ar.proto.v1.WorkerControlGrpc;
import com.soufiane.ar.proto.v1.WorkerHeartbeat;
import io.grpc.stub.StreamObserver;

/** C++ worker -> Java scheduler: one heartbeat per second per worker. */
final class WorkerControlService extends WorkerControlGrpc.WorkerControlImplBase {
    private final WorkerRegistry registry;

    WorkerControlService(WorkerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void heartbeat(WorkerHeartbeat request, StreamObserver<HeartbeatAck> response) {
        response.onNext(registry.onHeartbeat(request));
        response.onCompleted();
    }
}
