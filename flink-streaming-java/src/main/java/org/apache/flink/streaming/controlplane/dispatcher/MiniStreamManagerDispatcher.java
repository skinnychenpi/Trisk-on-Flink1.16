/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.controlplane.dispatcher;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.entrypoint.ClusterEntrypoint;
import org.apache.flink.runtime.entrypoint.JobClusterEntrypoint;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.util.FlinkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Mini Dispatcher which is instantiated as the dispatcher component by the {@link
 * JobClusterEntrypoint}.
 *
 * <p>The mini dispatcher is initialized with a single {@link JobGraph} which it runs.
 *
 * <p>Depending on the {@link ClusterEntrypoint.ExecutionMode}, the mini dispatcher will directly
 * terminate after job completion if its execution mode is {@link
 * ClusterEntrypoint.ExecutionMode#DETACHED}.
 */
public class MiniStreamManagerDispatcher extends StreamManagerDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(MiniStreamManagerDispatcher.class);

    private final JobClusterEntrypoint.ExecutionMode executionMode;

    public MiniStreamManagerDispatcher(
            RpcService rpcService,
            String endpointId,
            StreamManagerDispatcherId fencingToken,
            StreamManagerDispatcherServices dispatcherServices,
            JobGraph jobGraph,
            JobClusterEntrypoint.ExecutionMode executionMode)
            throws Exception {
        super(
                rpcService,
                endpointId,
                fencingToken,
                Collections.singleton(jobGraph),
                dispatcherServices);

        this.executionMode = checkNotNull(executionMode);
    }

    @Override
    public CompletableFuture<Acknowledge> submitJob(JobGraph jobGraph, Time timeout) {
        final CompletableFuture<Acknowledge> acknowledgeCompletableFuture =
                super.submitJob(jobGraph, timeout);

        acknowledgeCompletableFuture.whenComplete(
                (Acknowledge ignored, Throwable throwable) -> {
                    if (throwable != null) {
                        onFatalError(
                                new FlinkException(
                                        "Failed to submit job "
                                                + jobGraph.getJobID()
                                                + " in job mode.",
                                        throwable));
                    }
                });

        return acknowledgeCompletableFuture;
    }

    @Override
    protected void jobNotFinished(JobID jobId) {
        super.jobNotFinished(jobId);

        // shut down since we have done our job
        shutDownFuture.complete(ApplicationStatus.UNKNOWN);
    }
}
