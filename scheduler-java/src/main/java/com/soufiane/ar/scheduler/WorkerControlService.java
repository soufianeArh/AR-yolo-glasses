package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.HeartbeatAck;
import com.soufiane.ar.proto.v1.WorkerControlGrpc;
import com.soufiane.ar.proto.v1.WorkerHeartbeat;
import io.grpc.stub.StreamObserver;

final class WorkerControlService extends WorkerControlGrpc.WorkerControlImplBase {
    private final WorkerRegistry registry;

    WorkerControlService(WorkerRegistry registry) {
        this.registry = registry;
    }

    @Override
    // a heartbeat registers a new worker and keeps a known one alive
    public void heartbeat(WorkerHeartbeat request, StreamObserver<HeartbeatAck> response) {
        response.onNext(registry.onHeartbeat(request));
        response.onCompleted();
    }
}
