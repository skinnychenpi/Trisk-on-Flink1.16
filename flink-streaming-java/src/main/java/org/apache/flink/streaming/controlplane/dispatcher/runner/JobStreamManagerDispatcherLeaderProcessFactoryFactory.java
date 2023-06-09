/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.controlplane.dispatcher.runner;

import org.apache.flink.runtime.dispatcher.runner.JobDispatcherLeaderProcessFactory;
import org.apache.flink.runtime.entrypoint.component.JobGraphRetriever;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobmanager.JobPersistenceComponentFactory;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.streaming.controlplane.dispatcher.JobStreamManagerDispatcherFactory;
import org.apache.flink.streaming.controlplane.dispatcher.PartialStreamManagerDispatcherServices;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.FlinkRuntimeException;

import java.util.concurrent.Executor;

/** Factory for the {@link JobDispatcherLeaderProcessFactory}. */
public class JobStreamManagerDispatcherLeaderProcessFactoryFactory
        implements StreamManagerDispatcherLeaderProcessFactoryFactory {

    private final JobGraphRetriever jobGraphRetriever;

    private JobStreamManagerDispatcherLeaderProcessFactoryFactory(
            JobGraphRetriever jobGraphRetriever) {
        this.jobGraphRetriever = jobGraphRetriever;
    }

    @Override
    public StreamManagerDispatcherLeaderProcessFactory createFactory(
            JobPersistenceComponentFactory jobPersistenceComponentFactory,
            Executor ioExecutor,
            RpcService rpcService,
            PartialStreamManagerDispatcherServices partialDispatcherServices,
            FatalErrorHandler fatalErrorHandler) {

        final JobGraph jobGraph;

        try {
            jobGraph =
                    jobGraphRetriever.retrieveJobGraph(
                            partialDispatcherServices.getConfiguration());
        } catch (FlinkException e) {
            throw new FlinkRuntimeException("Could not retrieve the JobGraph.", e);
        }

        final DefaultStreamManagerDispatcherGatewayServiceFactory defaultDispatcherServiceFactory =
                new DefaultStreamManagerDispatcherGatewayServiceFactory(
                        JobStreamManagerDispatcherFactory.INSTANCE,
                        rpcService,
                        partialDispatcherServices);

        return new JobStreamManagerDispatcherLeaderProcessFactory(
                defaultDispatcherServiceFactory, jobGraph, fatalErrorHandler);
    }

    public static JobStreamManagerDispatcherLeaderProcessFactoryFactory create(
            JobGraphRetriever jobGraphRetriever) {
        return new JobStreamManagerDispatcherLeaderProcessFactoryFactory(jobGraphRetriever);
    }
}
