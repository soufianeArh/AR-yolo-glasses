package com.soufiane.ar.scheduler;

import com.soufiane.ar.proto.v1.HeartbeatAck;
import com.soufiane.ar.proto.v1.WorkerControlGrpc;
import com.soufiane.ar.proto.v1.WorkerHeartbeat;
import io.grpc.stub.StreamObserver;

// every c++ worker calls this once per second
final class WorkerControlService extends WorkerControlGrpc.WorkerControlImplBase {
    // the same registry the frame service picks workers from
    private final WorkerRegistry registry;

    // created once in Main
    WorkerControlService(WorkerRegistry registry) {
        this.registry = registry;
    }

    // a heartbeat registers a new worker and keeps a known one alive
    @Override
    public void heartbeat(WorkerHeartbeat request, StreamObserver<HeartbeatAck> response) {
        // the ack says accepted or refused like for a duplicate worker id
        response.onNext(registry.onHeartbeat(request));
        // one question and one answer so the call ends right here
        response.onCompleted();
    }
}
