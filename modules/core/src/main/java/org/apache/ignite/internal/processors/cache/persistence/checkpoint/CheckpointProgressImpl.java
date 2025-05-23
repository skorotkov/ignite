/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.checkpoint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.internal.processors.cache.persistence.CheckpointState;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.jetbrains.annotations.NotNull;

import static org.apache.ignite.internal.processors.cache.persistence.CheckpointState.FINISHED;
import static org.apache.ignite.internal.processors.cache.persistence.CheckpointState.LOCK_RELEASED;

/**
 * Data class representing the state of running/scheduled checkpoint.
 */
public class CheckpointProgressImpl implements CheckpointProgress {
    /** Scheduled time of checkpoint. */
    private volatile long nextCpNanos;

    /** Current checkpoint state. */
    private volatile AtomicReference<CheckpointState> state = new AtomicReference(CheckpointState.SCHEDULED);

    /** Future which would be finished when corresponds state is set. */
    private final Map<CheckpointState, GridFutureAdapter> stateFutures = new ConcurrentHashMap<>();

    /** Cause of fail, which has happened during the checkpoint or null if checkpoint was successful. */
    private volatile Throwable failCause;

    /** Partitions destroy queue. */
    private final PartitionDestroyQueue destroyQueue = new PartitionDestroyQueue();

    /** Wakeup reason. */
    private String reason;

    /** Counter for written checkpoint pages. Not null only if checkpoint is running. */
    private volatile AtomicInteger writtenPagesCntr;

    /** Counter for fsynced checkpoint pages. Not null only if checkpoint is running. */
    private volatile AtomicInteger syncedPagesCntr;

    /** Counter for evicted checkpoint pages. Not null only if checkpoint is running. */
    private volatile AtomicInteger evictedPagesCntr;

    /** Counter for written recovery pages. Not null only if checkpoint is running. */
    private volatile AtomicInteger writtenRecoveryPagesCntr;

    /** Number of pages in current checkpoint at the beginning of checkpoint. */
    private volatile int currCheckpointPagesCnt;

    /**
     * @param cpFreq Timeout until next checkpoint.
     */
    CheckpointProgressImpl(long cpFreq) {
        // Avoid overflow on nextCpNanos.
        cpFreq = Math.min(TimeUnit.DAYS.toMillis(365), cpFreq);

        nextCpNanos = System.nanoTime() + U.millisToNanos(cpFreq);
    }

    /**
     * @return {@code true} If checkpoint already started but have not finished yet.
     */
    @Override public boolean inProgress() {
        return greaterOrEqualTo(LOCK_RELEASED) && !greaterOrEqualTo(FINISHED);
    }

    /**
     * @param expectedState Expected state.
     * @return {@code true} if current state equal to given state.
     */
    public boolean greaterOrEqualTo(CheckpointState expectedState) {
        return state.get().ordinal() >= expectedState.ordinal();
    }

    /**
     * @param state State for which future should be returned.
     * @return Existed or new future which corresponds to the given state.
     */
    @Override public GridFutureAdapter futureFor(CheckpointState state) {
        GridFutureAdapter stateFut = stateFutures.computeIfAbsent(state, (k) -> new GridFutureAdapter());

        if (greaterOrEqualTo(state) && !stateFut.isDone())
            stateFut.onDone(failCause);

        return stateFut;
    }

    /**
     * Mark this checkpoint execution as failed.
     *
     * @param error Causal error of fail.
     */
    @Override public void fail(Throwable error) {
        failCause = error;

        transitTo(FINISHED);
    }

    /**
     * Changing checkpoint state if order of state is correct.
     *
     * @param newState New checkpoint state.
     */
    @Override public void transitTo(@NotNull CheckpointState newState) {
        CheckpointState state = this.state.get();

        if (state.ordinal() < newState.ordinal()) {
            this.state.compareAndSet(state, newState);

            doFinishFuturesWhichLessOrEqualTo(newState);
        }
    }

    /**
     * Finishing futures with correct result in direct state order until lastState(included).
     *
     * @param lastState State until which futures should be done.
     */
    private void doFinishFuturesWhichLessOrEqualTo(@NotNull CheckpointState lastState) {
        for (CheckpointState old : CheckpointState.values()) {
            GridFutureAdapter fut = stateFutures.get(old);

            if (fut != null && !fut.isDone())
                fut.onDone(failCause);

            if (old == lastState)
                return;
        }
    }

    /**
     * @return Destroy queue.
     */
    @Override public PartitionDestroyQueue getDestroyQueue() {
        return destroyQueue;
    }

    /**
     * @return Scheduled time of checkpoint.
     */
    public long nextCpNanos() {
        return nextCpNanos;
    }

    /**
     * @param nextCpNanos New scheduled time of checkpoint.
     */
    public void nextCpNanos(long nextCpNanos) {
        this.nextCpNanos = nextCpNanos;
    }

    /** {@inheritDoc} */
    @Override public String reason() {
        return reason;
    }

    /**
     * @param reason New wakeup reason.
     */
    public void reason(String reason) {
        this.reason = reason;
    }

    /** {@inheritDoc} */
    @Override public AtomicInteger writtenPagesCounter() {
        return writtenPagesCntr;
    }

    /** {@inheritDoc} */
    @Override public void updateWrittenPages(int delta) {
        A.ensure(delta > 0, "param must be positive");

        writtenPagesCntr.addAndGet(delta);
    }

    /** {@inheritDoc} */
    @Override public AtomicInteger syncedPagesCounter() {
        return syncedPagesCntr;
    }

    /** {@inheritDoc} */
    @Override public void updateSyncedPages(int delta) {
        A.ensure(delta > 0, "param must be positive");

        syncedPagesCntr.addAndGet(delta);
    }

    /** {@inheritDoc} */
    @Override public AtomicInteger evictedPagesCounter() {
        return evictedPagesCntr;
    }

    /** {@inheritDoc} */
    @Override public void updateEvictedPages(int delta) {
        A.ensure(delta > 0, "param must be positive");

        AtomicInteger cntr = evictedPagesCounter();

        if (cntr != null)
            cntr.addAndGet(delta);
    }

    /** {@inheritDoc} */
    @Override public AtomicInteger writtenRecoveryPagesCounter() {
        return writtenRecoveryPagesCntr;
    }

    /** {@inheritDoc} */
    @Override public void updateWrittenRecoveryPages(int delta) {
        A.ensure(delta > 0, "param must be positive");

        AtomicInteger cntr = writtenRecoveryPagesCounter();

        if (cntr != null)
            cntr.addAndGet(delta);
    }

    /** {@inheritDoc} */
    @Override public int currentCheckpointPagesCount() {
        return currCheckpointPagesCnt;
    }

    /** {@inheritDoc} */
    @Override public void currentCheckpointPagesCount(int num) {
        currCheckpointPagesCnt = num;
    }

    /** {@inheritDoc} */
    @Override public void initCounters(int pagesSize) {
        currCheckpointPagesCnt = pagesSize;

        writtenPagesCntr = new AtomicInteger();
        syncedPagesCntr = new AtomicInteger();
        evictedPagesCntr = new AtomicInteger();
        writtenRecoveryPagesCntr = new AtomicInteger();
    }

    /** {@inheritDoc} */
    @Override public void clearCounters() {
        currCheckpointPagesCnt = 0;

        writtenPagesCntr = null;
        syncedPagesCntr = null;
        evictedPagesCntr = null;
        writtenRecoveryPagesCntr = null;
    }

    /** {@inheritDoc} */
    @Override public void onStateChanged(CheckpointState state, Runnable clo) {
        GridFutureAdapter<?> fut = futureFor(state);

        fut.listen(() -> {
            if (fut.error() == null)
                clo.run();
        });
    }
}
