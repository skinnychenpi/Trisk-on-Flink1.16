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

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.Archiveable;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.rescale.RescaleID;
import org.apache.flink.runtime.scheduler.strategy.ConsumedPartitionGroup;
import org.apache.flink.runtime.scheduler.strategy.ExecutionVertexID;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.apache.flink.runtime.execution.ExecutionState.FINISHED;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The ExecutionVertex is a parallel subtask of the execution. It may be executed once, or several
 * times, each of which time it spawns an {@link Execution}.
 */
public class ExecutionVertex
        implements AccessExecutionVertex, Archiveable<ArchivedExecutionVertex> {

    public static final int MAX_DISTINCT_LOCATIONS_TO_CONSIDER = 8;

    // --------------------------------------------------------------------------------------------

    final ExecutionJobVertex jobVertex;

    private final Map<IntermediateResultPartitionID, IntermediateResultPartition> resultPartitions;

    private final int subTaskIndex;

    private final ExecutionVertexID executionVertexId;

    final ExecutionHistory executionHistory;

    private final Time timeout;

    /** The name in the format "myTask (2/7)", cached to avoid frequent string concatenations. */
    private String taskNameWithSubtask;

    /** The current or latest execution attempt of this vertex's task. */
    Execution currentExecution; // this field must never be null

    final ArrayList<InputSplit> inputSplits;

    private int nextAttemptNumber;

    /** This field holds the allocation id of the last successful assignment. */
    @Nullable private TaskManagerLocation lastAssignedLocation;

    @Nullable private AllocationID lastAssignedAllocationID;

    // ---------------------------------------Trisk Fields------------------------------------------
    private volatile RescaleID rescaleId;

    private volatile KeyGroupRange keyGroupRange;

    private volatile int idInModel;

    // ---------------------------------------------------------------------------------------------

    /**
     * Creates an ExecutionVertex.
     *
     * @param timeout The RPC timeout to use for deploy / cancel calls
     * @param createTimestamp The timestamp for the vertex creation, used to initialize the first
     *     Execution with.
     * @param executionHistorySizeLimit The maximum number of historical Executions (= execution
     *     attempts) to keep.
     * @param initialAttemptCount The attempt number of the first execution of this vertex.
     */
    @VisibleForTesting
    public ExecutionVertex(
            ExecutionJobVertex jobVertex,
            int subTaskIndex,
            IntermediateResult[] producedDataSets,
            Time timeout,
            long createTimestamp,
            int executionHistorySizeLimit,
            int initialAttemptCount) {

        this.jobVertex = jobVertex;
        this.subTaskIndex = subTaskIndex;
        this.executionVertexId = new ExecutionVertexID(jobVertex.getJobVertexId(), subTaskIndex);
        this.taskNameWithSubtask =
                String.format(
                        "%s (%d/%d)",
                        jobVertex.getJobVertex().getName(),
                        subTaskIndex + 1,
                        jobVertex.getParallelism());

        this.resultPartitions = new LinkedHashMap<>(producedDataSets.length, 1);

        for (IntermediateResult result : producedDataSets) {
            IntermediateResultPartition irp =
                    new IntermediateResultPartition(
                            result,
                            this,
                            subTaskIndex,
                            getExecutionGraphAccessor().getEdgeManager());
            result.setPartition(subTaskIndex, irp);

            resultPartitions.put(irp.getPartitionId(), irp);
        }

        this.executionHistory = new ExecutionHistory(executionHistorySizeLimit);

        this.nextAttemptNumber = initialAttemptCount;

        this.timeout = timeout;
        this.inputSplits = new ArrayList<>();

        this.currentExecution = createNewExecution(createTimestamp);

        getExecutionGraphAccessor().registerExecution(currentExecution);

        this.rescaleId = RescaleID.DEFAULT;
        this.idInModel = subTaskIndex;
    }

    // --------------------------------------------------------------------------------------------
    //  Properties
    // --------------------------------------------------------------------------------------------

    Execution createNewExecution(final long timestamp) {
        return new Execution(
                getExecutionGraphAccessor().getFutureExecutor(),
                this,
                nextAttemptNumber++,
                timestamp,
                timeout);
    }

    public Execution getPartitionProducer() {
        return currentExecution;
    }

    public JobID getJobId() {
        return this.jobVertex.getJobId();
    }

    public ExecutionJobVertex getJobVertex() {
        return jobVertex;
    }

    public JobVertexID getJobvertexId() {
        return this.jobVertex.getJobVertexId();
    }

    public String getTaskName() {
        return this.jobVertex.getJobVertex().getName();
    }

    /**
     * Creates a simple name representation in the style 'taskname (x/y)', where 'taskname' is the
     * name as returned by {@link #getTaskName()}, 'x' is the parallel subtask index as returned by
     * {@link #getParallelSubtaskIndex()}{@code + 1}, and 'y' is the total number of tasks, as
     * returned by {@link #getTotalNumberOfParallelSubtasks()}.
     *
     * @return A simple name representation in the form 'myTask (2/7)'
     */
    @Override
    public String getTaskNameWithSubtaskIndex() {
        return this.taskNameWithSubtask;
    }

    public void updateTaskNameWithSubtaskIndex() {
        this.taskNameWithSubtask =
                String.format(
                        "%s (%d/%d)",
                        jobVertex.getJobVertex().getName(),
                        subTaskIndex + 1,
                        jobVertex.getParallelism());
    }

    public void updateTaskIndex(int subTaskIndex) {
        // save an old index for checkpoint and operator state operation

        // For now it is a dummy method. Will be updated when dealing with scale in.
        //        this.oldSubTaskIndex = this.subTaskIndex;
        //        this.subTaskIndex = subTaskIndex;
        //        this.executionVertexId.setSubtaskIndex(subTaskIndex);
        //
        //        for (IntermediateResultPartition irp : this.resultPartitions.values()) {
        //            irp.updatePartitionNumber(subTaskIndex);
        //        }
    }

    public int getTotalNumberOfParallelSubtasks() {
        return this.jobVertex.getParallelism();
    }

    public int getOldTotalNumberOfParallelSubtasks() {
        return this.jobVertex.getOldParallelism();
    }

    public int getMaxParallelism() {
        return this.jobVertex.getMaxParallelism();
    }

    public ResourceProfile getResourceProfile() {
        return this.jobVertex.getResourceProfile();
    }

    @Override
    public int getParallelSubtaskIndex() {
        return this.subTaskIndex;
    }

    public ExecutionVertexID getID() {
        return executionVertexId;
    }

    public int getNumberOfInputs() {
        return getAllConsumedPartitionGroups().size();
    }

    public List<ConsumedPartitionGroup> getAllConsumedPartitionGroups() {
        return getExecutionGraphAccessor()
                .getEdgeManager()
                .getConsumedPartitionGroupsForVertex(executionVertexId);
    }

    public ConsumedPartitionGroup getConsumedPartitionGroup(int input) {
        final List<ConsumedPartitionGroup> allConsumedPartitions = getAllConsumedPartitionGroups();

        if (input < 0 || input >= allConsumedPartitions.size()) {
            throw new IllegalArgumentException(
                    String.format(
                            "Input %d is out of range [0..%d)",
                            input, allConsumedPartitions.size()));
        }

        return allConsumedPartitions.get(input);
    }

    public Optional<InputSplit> getNextInputSplit(String host, int attemptNumber) {
        final int subtaskIndex = getParallelSubtaskIndex();
        final InputSplit nextInputSplit =
                jobVertex.getSplitAssigner().getNextInputSplit(host, subtaskIndex);
        if (nextInputSplit != null) {
            inputSplits.add(nextInputSplit);
        }
        return Optional.ofNullable(nextInputSplit);
    }

    @Override
    public Execution getCurrentExecutionAttempt() {
        return currentExecution;
    }

    public Collection<Execution> getCurrentExecutions() {
        return Collections.singleton(currentExecution);
    }

    public Execution getCurrentExecution(int attemptNumber) {
        checkArgument(attemptNumber == currentExecution.getAttemptNumber());
        return currentExecution;
    }

    @Override
    public ExecutionState getExecutionState() {
        return getCurrentExecutionAttempt().getState();
    }

    @Override
    public long getStateTimestamp(ExecutionState state) {
        return getCurrentExecutionAttempt().getStateTimestamp(state);
    }

    @Override
    public Optional<ErrorInfo> getFailureInfo() {
        return getCurrentExecutionAttempt().getFailureInfo();
    }

    public CompletableFuture<TaskManagerLocation> getCurrentTaskManagerLocationFuture() {
        return getCurrentExecutionAttempt().getTaskManagerLocationFuture();
    }

    public LogicalSlot getCurrentAssignedResource() {
        return getCurrentExecutionAttempt().getAssignedResource();
    }

    @Override
    public TaskManagerLocation getCurrentAssignedResourceLocation() {
        return getCurrentExecutionAttempt().getAssignedResourceLocation();
    }

    @Override
    public ExecutionHistory getExecutionHistory() {
        return executionHistory;
    }

    void setLatestPriorSlotAllocation(
            TaskManagerLocation taskManagerLocation, AllocationID lastAssignedAllocationID) {
        this.lastAssignedLocation = Preconditions.checkNotNull(taskManagerLocation);
        this.lastAssignedAllocationID = Preconditions.checkNotNull(lastAssignedAllocationID);
    }

    /**
     * Gets the location that an execution of this vertex was assigned to.
     *
     * @return The last execution location, or null, if there is none, yet.
     */
    public Optional<TaskManagerLocation> findLastLocation() {
        return Optional.ofNullable(lastAssignedLocation);
    }

    public Optional<AllocationID> findLastAllocation() {
        return Optional.ofNullable(lastAssignedAllocationID);
    }

    public final InternalExecutionGraphAccessor getExecutionGraphAccessor() {
        return this.jobVertex.getGraph();
    }

    public Map<IntermediateResultPartitionID, IntermediateResultPartition> getProducedPartitions() {
        return resultPartitions;
    }

    CompletableFuture<?> getTerminationFuture() {
        return currentExecution.getTerminalStateFuture();
    }

    // --------------------------------------------------------------------------------------------
    //  Graph building
    // --------------------------------------------------------------------------------------------

    public void addConsumedPartitionGroup(ConsumedPartitionGroup consumedPartitions) {

        getExecutionGraphAccessor()
                .getEdgeManager()
                .connectVertexWithConsumedPartitionGroup(executionVertexId, consumedPartitions);
    }

    /**
     * Gets the preferred location to execute the current task execution attempt, based on the state
     * that the execution attempt will resume.
     */
    public Optional<TaskManagerLocation> getPreferredLocationBasedOnState() {
        // only restore to same execution if it has state
        if (currentExecution.getTaskRestore() != null
                && currentExecution.getTaskRestore().getTaskStateSnapshot().hasState()) {
            return findLastLocation();
        }

        return Optional.empty();
    }

    // --------------------------------------------------------------------------------------------
    //   Actions
    // --------------------------------------------------------------------------------------------

    /** Archives the current Execution and creates a new Execution for this vertex. */
    public void resetForNewExecution() {
        resetForNewExecutionInternal(System.currentTimeMillis());
    }

    private void resetForNewExecutionInternal(final long timestamp) {
        final boolean isFinished = (getExecutionState() == FINISHED);

        resetExecutionsInternal();

        InputSplitAssigner assigner = jobVertex.getSplitAssigner();
        if (assigner != null) {
            assigner.returnInputSplit(inputSplits, getParallelSubtaskIndex());
            inputSplits.clear();
        }

        // if the execution was 'FINISHED' before, tell the ExecutionGraph that
        // we take one step back on the road to reaching global FINISHED
        if (isFinished) {
            getJobVertex().executionVertexUnFinished();
        }

        // reset the intermediate results
        for (IntermediateResultPartition resultPartition : resultPartitions.values()) {
            resultPartition.resetForNewExecution();
        }

        final Execution newExecution = createNewExecution(timestamp);
        currentExecution = newExecution;

        // register this execution to the execution graph, to receive call backs
        getExecutionGraphAccessor().registerExecution(newExecution);
    }

    void resetExecutionsInternal() {
        resetExecution(currentExecution);
    }

    void resetExecution(final Execution execution) {
        final ExecutionState oldState = execution.getState();

        checkState(
                oldState.isTerminal(),
                "Cannot reset an execution that is in non-terminal state " + oldState);

        if (oldState == FINISHED) {
            // pipelined partitions are released in Execution#cancel(), covering both job
            // failures and vertex resets
            // do not release pipelined partitions here to save RPC calls
            execution.handlePartitionCleanup(false, true);
            getExecutionGraphAccessor()
                    .getPartitionGroupReleaseStrategy()
                    .vertexUnfinished(executionVertexId);
        }

        executionHistory.add(execution.archive());
    }

    public void tryAssignResource(LogicalSlot slot) {
        if (!currentExecution.tryAssignResource(slot)) {
            throw new IllegalStateException(
                    "Could not assign resource "
                            + slot
                            + " to current execution "
                            + currentExecution
                            + '.');
        }
    }

    public void deploy() throws JobException {
        currentExecution.deploy();
    }

    @VisibleForTesting
    public void deployToSlot(LogicalSlot slot) throws JobException {
        if (currentExecution.tryAssignResource(slot)) {
            currentExecution.deploy();
        } else {
            throw new IllegalStateException(
                    "Could not assign resource "
                            + slot
                            + " to current execution "
                            + currentExecution
                            + '.');
        }
    }

    /**
     * Cancels this ExecutionVertex.
     *
     * @return A future that completes once the execution has reached its final state.
     */
    public CompletableFuture<?> cancel() {
        // to avoid any case of mixup in the presence of concurrent calls,
        // we copy a reference to the stack to make sure both calls go to the same Execution
        final Execution exec = currentExecution;
        exec.cancel();
        return exec.getReleaseFuture();
    }

    public CompletableFuture<?> suspend() {
        return currentExecution.suspend();
    }

    public void fail(Throwable t) {
        currentExecution.fail(t);
    }

    /**
     * This method marks the task as failed, but will make no attempt to remove task execution from
     * the task manager. It is intended for cases where the task is known not to be deployed yet.
     *
     * @param t The exception that caused the task to fail.
     */
    public void markFailed(Throwable t) {
        currentExecution.markFailed(t);
    }

    void cachePartitionInfo(PartitionInfo partitionInfo) {
        getCurrentExecutionAttempt().cachePartitionInfo(partitionInfo);
    }

    /** Returns all blocking result partitions whose receivers can be scheduled/updated. */
    @VisibleForTesting
    public List<IntermediateResultPartition> finishAllBlockingPartitions() {
        List<IntermediateResultPartition> finishedBlockingPartitions = null;

        for (IntermediateResultPartition partition : resultPartitions.values()) {
            if (!partition.getResultType().canBePipelinedConsumed()) {

                partition.markFinished();

                if (finishedBlockingPartitions == null) {
                    finishedBlockingPartitions = new LinkedList<>();
                }

                finishedBlockingPartitions.add(partition);
            }
        }

        if (finishedBlockingPartitions == null) {
            return Collections.emptyList();
        } else {
            return finishedBlockingPartitions;
        }
    }

    // --------------------------------------------------------------------------------------------
    //   Notifications from the Execution Attempt
    // --------------------------------------------------------------------------------------------

    void executionFinished(Execution execution) {
        getJobVertex().executionVertexFinished();
    }

    // --------------------------------------------------------------------------------------------
    //   Miscellaneous
    // --------------------------------------------------------------------------------------------

    void notifyPendingDeployment(Execution execution) {
        // only forward this notification if the execution is still the current execution
        // otherwise we have an outdated execution
        if (isCurrentExecution(execution)) {
            getExecutionGraphAccessor()
                    .getExecutionDeploymentListener()
                    .onStartedDeployment(
                            execution.getAttemptId(),
                            execution.getAssignedResourceLocation().getResourceID());
        }
    }

    void notifyCompletedDeployment(Execution execution) {
        // only forward this notification if the execution is still the current execution
        // otherwise we have an outdated execution
        if (isCurrentExecution(execution)) {
            getExecutionGraphAccessor()
                    .getExecutionDeploymentListener()
                    .onCompletedDeployment(execution.getAttemptId());
        }
    }

    /** Simply forward this notification. */
    void notifyStateTransition(
            Execution execution, ExecutionState previousState, ExecutionState newState) {
        // only forward this notification if the execution is still the current execution
        // otherwise we have an outdated execution
        if (isCurrentExecution(execution)) {
            getExecutionGraphAccessor().notifyExecutionChange(execution, previousState, newState);
        }
    }

    private boolean isCurrentExecution(Execution execution) {
        return currentExecution == execution;
    }

    // --------------------------------------------------------------------------------------------
    //  Utilities
    // --------------------------------------------------------------------------------------------

    @Override
    public String toString() {
        return getTaskNameWithSubtaskIndex();
    }

    @Override
    public ArchivedExecutionVertex archive() {
        return new ArchivedExecutionVertex(this);
    }

    // --------------------------------------------------------------------------------------------
    //  Trisk 1.16
    // --------------------------------------------------------------------------------------------
    //    public void connectEVWithIRP(int inputNumber, IntermediateResult source, JobEdge edge, int
    // consumerNumber) {
    //
    //        final DistributionPattern pattern = edge.getDistributionPattern();
    //        final IntermediateResultPartition[] sourcePartitions =
    // source.getPartitions();//这里的sourcePartition已经是10了
    //
    //        ExecutionEdge[] edges; //利用jobEdges更新ExecutionEdge
    //
    //        switch (pattern) {
    //            case POINTWISE:
    //                edges = connectPointwise(sourcePartitions, inputNumber);
    //                break;
    //
    //            case ALL_TO_ALL:
    //                connectAllToAll(sourcePartitions); //target
    // operator新建的8个ExecutionVertex都会执行到这里，然后每个EV都会新建一个（因为source的并行度是1）EE，EE会连接起上游的ERP和当下正在处理的EV
    //                break;
    //
    //            default:
    //                throw new RuntimeException("Unrecognized distribution pattern.");
    //
    //        }
    //
    //        inputEdges[inputNumber] = edges; //这一步就是明确当下的EV的上游是由哪些EE连接起来的，这些EE连接起了上游IRP和当下的EV
    //
    //        // add the consumers to the source
    //        // for now (until the receiver initiated handshake is in place), we need to register
    // the
    //        // edges as the execution graph
    //        for (ExecutionEdge ee : edges) {
    //            ee.getSource().addConsumer(ee, consumerNumber);
    //        }
    //    }
    //
    //    private void connectAllToAll(IntermediateResultPartition[] sourcePartitions) {
    //        for (int i = 0; i < sourcePartitions.length; i++) {
    //            IntermediateResultPartition irp = sourcePartitions[i];
    //            irp.addEdgeToExecutionVertex(this);//在这里新建了10次edges，并且把这些edges连接到target
    // operator的下游的tasks节点上面
    //        }
    //    }

    // ---------------------------------------Trisk Methods------------------------------------
    public void assignKeyGroupRange(KeyGroupRange keyGroupRange) {
        this.keyGroupRange = keyGroupRange;
    }

    public KeyGroupRange getKeyGroupRange() {
        return keyGroupRange;
    }

    public void setIdInModel(int idInModel) {
        this.idInModel = idInModel;
    }

    public int getIdInModel() {
        return idInModel;
    }

    public RescaleID getRescaleId() {
        return rescaleId;
    }

    public void updateRescaleId(RescaleID rescaleId) {
        this.rescaleId = rescaleId;
    }

    public List<CompletableFuture<Acknowledge>> getExecutionVerticesDeploymentFutureForRescale() {
        return getExecutionGraphAccessor().getExecutionVerticesDeploymentFutureForRescale();
    }

    public void setFlagToWaitForRescaleDeploymentFuturesDone() {
        getExecutionGraphAccessor().setFlagToWaitForRescaleDeploymentFuturesDone();
    }

    public void removeExecutionVertexIDToBeDeployedForRescale() {
        getExecutionGraphAccessor().removeExecutionVertexIDToBeDeployedForRescale(this.getID());
    }
    // ---------------------------------------Trisk Methods------------------------------------
}
