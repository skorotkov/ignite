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

package org.apache.ignite.internal.processors.job;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteDeploymentException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeExecutionRejectedException;
import org.apache.ignite.compute.ComputeJobSibling;
import org.apache.ignite.compute.ComputeTaskSession;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.JobEvent;
import org.apache.ignite.events.TaskEvent;
import org.apache.ignite.internal.GridJobCancelRequest;
import org.apache.ignite.internal.GridJobContextImpl;
import org.apache.ignite.internal.GridJobExecuteRequest;
import org.apache.ignite.internal.GridJobExecuteResponse;
import org.apache.ignite.internal.GridJobSessionImpl;
import org.apache.ignite.internal.GridJobSiblingsRequest;
import org.apache.ignite.internal.GridJobSiblingsResponse;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.GridTaskSessionImpl;
import org.apache.ignite.internal.GridTaskSessionRequest;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.managers.collision.GridCollisionJobContextAdapter;
import org.apache.ignite.internal.managers.collision.GridCollisionManager;
import org.apache.ignite.internal.managers.communication.GridIoManager;
import org.apache.ignite.internal.managers.communication.GridMessageListener;
import org.apache.ignite.internal.managers.deployment.GridDeployment;
import org.apache.ignite.internal.managers.eventstorage.GridLocalEventListener;
import org.apache.ignite.internal.managers.systemview.walker.ComputeJobViewWalker;
import org.apache.ignite.internal.processors.GridProcessorAdapter;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridReservable;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.configuration.distributed.DistributedLongProperty;
import org.apache.ignite.internal.processors.jobmetrics.GridJobMetricsSnapshot;
import org.apache.ignite.internal.processors.metric.MetricRegistryImpl;
import org.apache.ignite.internal.processors.metric.impl.AtomicLongMetric;
import org.apache.ignite.internal.util.GridAtomicLong;
import org.apache.ignite.internal.util.GridBoundedConcurrentLinkedHashMap;
import org.apache.ignite.internal.util.GridBoundedConcurrentLinkedHashSet;
import org.apache.ignite.internal.util.GridConcurrentHashSet;
import org.apache.ignite.internal.util.GridSpinReadWriteLock;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.LT;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.util.worker.GridWorker;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.marshaller.Marshaller;
import org.apache.ignite.spi.collision.CollisionSpi;
import org.apache.ignite.spi.collision.priorityqueue.PriorityQueueCollisionSpi;
import org.apache.ignite.spi.metric.DoubleMetric;
import org.apache.ignite.spi.systemview.view.ComputeJobView;
import org.apache.ignite.spi.systemview.view.ComputeJobView.ComputeJobState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentLinkedHashMap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_JOBS_HISTORY_SIZE;
import static org.apache.ignite.events.EventType.EVT_JOB_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.events.EventType.EVT_NODE_METRICS_UPDATED;
import static org.apache.ignite.events.EventType.EVT_TASK_SESSION_ATTR_SET;
import static org.apache.ignite.internal.GridTopic.TOPIC_JOB;
import static org.apache.ignite.internal.GridTopic.TOPIC_JOB_CANCEL;
import static org.apache.ignite.internal.GridTopic.TOPIC_JOB_SIBLINGS;
import static org.apache.ignite.internal.GridTopic.TOPIC_TASK;
import static org.apache.ignite.internal.cluster.DistributedConfigurationUtils.makeUpdateListener;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.MANAGEMENT_POOL;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.SYSTEM_POOL;
import static org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionState.OWNING;
import static org.apache.ignite.internal.processors.configuration.distributed.DistributedLongProperty.detachedLongProperty;
import static org.apache.ignite.internal.processors.metric.GridMetricManager.CPU_LOAD;
import static org.apache.ignite.internal.processors.metric.GridMetricManager.SYS_METRICS;
import static org.apache.ignite.internal.processors.metric.impl.MetricUtils.metricName;
import static org.apache.ignite.internal.processors.security.SecurityUtils.securitySubjectId;
import static org.jsr166.ConcurrentLinkedHashMap.QueuePolicy.PER_SEGMENT_Q;

/**
 * Responsible for all grid job execution and communication.
 */
public class GridJobProcessor extends GridProcessorAdapter {
    /** */
    public static final String JOBS_VIEW = "jobs";

    /** */
    public static final String JOBS_VIEW_DESC = "Running compute jobs, part of compute task started on remote host.";

    /** @see IgniteSystemProperties#IGNITE_JOBS_HISTORY_SIZE */
    public static final int DFLT_JOBS_HISTORY_SIZE = 10240;

    /** */
    private static final int FINISHED_JOBS_COUNT = Integer.getInteger(IGNITE_JOBS_HISTORY_SIZE, DFLT_JOBS_HISTORY_SIZE);

    /** Metrics prefix. */
    public static final String JOBS_METRICS = metricName("compute", "jobs");

    /** Started jobs metric name. */
    public static final String STARTED = "Started";

    /** Active jobs metric name. */
    public static final String ACTIVE = "Active";

    /** Waiting jobs metric name. */
    public static final String WAITING = "Waiting";

    /** Canceled jobs metric name. */
    public static final String CANCELED = "Canceled";

    /** Rejected jobs metric name. */
    public static final String REJECTED = "Rejected";

    /** Finished jobs metric name. */
    public static final String FINISHED = "Finished";

    /** Total jobs execution time metric name. */
    public static final String EXECUTION_TIME = "ExecutionTime";

    /** Total jobs waiting time metric name. */
    public static final String WAITING_TIME = "WaitingTime";

    /**
     * Distributed property that defines the timeout for interrupting the
     * {@link GridJobWorker worker} after {@link GridJobWorker#cancel() cancellation} in mills.
     */
    public static final String COMPUTE_JOB_WORKER_INTERRUPT_TIMEOUT = "computeJobWorkerInterruptTimeout";

    /** */
    private final Marshaller marsh;

    /** Collision SPI is not available: {@link GridCollisionManager#enabled()} {@code == false}. */
    private final boolean jobAlwaysActivate;

    /** */
    private volatile ConcurrentMap<IgniteUuid, GridJobWorker> syncRunningJobs;

    /** */
    private volatile ConcurrentMap<IgniteUuid, GridJobWorker> activeJobs;

    /** */
    private final ConcurrentMap<IgniteUuid, GridJobWorker> passiveJobs;

    /** */
    private final ConcurrentMap<IgniteUuid, GridJobWorker> cancelledJobs =
        new ConcurrentHashMap<>();

    /** */
    private final Collection<IgniteUuid> heldJobs = new GridConcurrentHashSet<>();

    /** If value is {@code true}, job was cancelled from future. */
    private volatile GridBoundedConcurrentLinkedHashMap<IgniteUuid, Boolean> cancelReqs =
        new GridBoundedConcurrentLinkedHashMap<>(FINISHED_JOBS_COUNT,
            FINISHED_JOBS_COUNT < 128 ? FINISHED_JOBS_COUNT : 128,
            0.75f, 16);

    /** */
    private final GridBoundedConcurrentLinkedHashSet<IgniteUuid> finishedJobs =
        new GridBoundedConcurrentLinkedHashSet<>(FINISHED_JOBS_COUNT,
            FINISHED_JOBS_COUNT < 128 ? FINISHED_JOBS_COUNT : 128,
            0.75f, 256, PER_SEGMENT_Q);

    /** */
    private final GridJobEventListener evtLsnr;

    /** */
    private final GridMessageListener cancelLsnr;

    /** */
    private final GridMessageListener jobExecLsnr;

    /** */
    private final GridLocalEventListener discoLsnr;

    /** Needed for statistics. */
    @Deprecated
    private final LongAdder canceledJobsCnt = new LongAdder();

    /** Needed for statistics. */
    @Deprecated
    private final LongAdder finishedJobsCnt = new LongAdder();

    /** Needed for statistics. */
    @Deprecated
    private final LongAdder startedJobsCnt = new LongAdder();

    /** Needed for statistics. */
    @Deprecated
    private final LongAdder rejectedJobsCnt = new LongAdder();

    /** Total job execution time (unaccounted for in metrics). */
    @Deprecated
    private final LongAdder finishedJobsTime = new LongAdder();

    /** Cpu load metric */
    private final DoubleMetric cpuLoadMetric;

    /** Maximum job execution time for finished jobs. */
    @Deprecated
    private final GridAtomicLong maxFinishedJobsTime = new GridAtomicLong();

    /** */
    @Deprecated
    private final AtomicLong metricsLastUpdateTstamp = new AtomicLong();

    /** Number of started jobs. */
    final AtomicLongMetric startedJobsMetric;

    /** Number of active jobs currently executing. */
    final AtomicLongMetric activeJobsMetric;

    /** Number of currently queued jobs waiting to be executed. */
    final AtomicLongMetric waitingJobsMetric;

    /** Number of cancelled jobs that are still running. */
    final AtomicLongMetric canceledJobsMetric;

    /** Number of jobs rejected after more recent collision resolution operation. */
    final AtomicLongMetric rejectedJobsMetric;

    /** Number of finished jobs. */
    final AtomicLongMetric finishedJobsMetric;

    /** Total job execution time. */
    final AtomicLongMetric totalExecutionTimeMetric;

    /** Total time jobs spent on waiting queue. */
    final AtomicLongMetric totalWaitTimeMetric;

    /** */
    private boolean stopping;

    /** */
    private boolean cancelOnStop;

    /** */
    @Deprecated
    private final long metricsUpdateFreq;

    /** */
    private final GridSpinReadWriteLock rwLock = new GridSpinReadWriteLock();

    /** Topic ID generator. */
    private final AtomicLong topicIdGen = new AtomicLong();

    /** */
    private final GridJobHoldListener holdLsnr = new JobHoldListener();

    /** */
    private final ThreadLocal<Boolean> handlingCollision = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() {
            return false;
        }
    };

    /** Internal task flag. */
    private final ThreadLocal<Boolean> internal = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() {
            return false;
        }
    };

    /** Current session. */
    private final ThreadLocal<GridJobSessionImpl> currSess = new ThreadLocal<>();

    /**
     * {@link PriorityQueueCollisionSpi#getPriorityAttributeKey} or
     * {@link null} if the {@link PriorityQueueCollisionSpi} is not configured.
     */
    @Nullable private final String taskPriAttrKey;

    /**
     * {@link PriorityQueueCollisionSpi#getJobPriorityAttributeKey} or
     * {@link null} if the {@link PriorityQueueCollisionSpi} is not configured.
     */
    @Nullable private final String jobPriAttrKey;

    /** Timeout interrupt {@link GridJobWorker workers} after {@link GridJobWorker#cancel cancel} im mills. */
    private final DistributedLongProperty computeJobWorkerInterruptTimeout =
        detachedLongProperty(COMPUTE_JOB_WORKER_INTERRUPT_TIMEOUT,
            "The timeout in milliseconds for interrupting the a job worker after a cancel operation is called.");

    /**
     * @param ctx Kernal context.
     */
    public GridJobProcessor(GridKernalContext ctx) {
        super(ctx);

        marsh = ctx.marshaller();

        // Collision manager is already started and is fully functional.
        jobAlwaysActivate = !ctx.collision().enabled();

        metricsUpdateFreq = ctx.config().getMetricsUpdateFrequency();

        syncRunningJobs = new ConcurrentHashMap<>();

        activeJobs = initJobsMap(jobAlwaysActivate);

        passiveJobs = jobAlwaysActivate ? null : new JobsMap(1024, 0.75f, 256);

        evtLsnr = new JobEventListener();
        cancelLsnr = new JobCancelListener();
        jobExecLsnr = new JobExecutionListener();
        discoLsnr = new JobDiscoveryListener();

        cpuLoadMetric = ctx.metric().registry(SYS_METRICS).findMetric(CPU_LOAD);

        MetricRegistryImpl mreg = ctx.metric().registry(JOBS_METRICS);

        startedJobsMetric = mreg.longMetric(STARTED, "Number of started jobs.");

        activeJobsMetric = mreg.longMetric(ACTIVE, "Number of active jobs currently executing.");

        waitingJobsMetric = mreg.longMetric(WAITING, "Number of currently queued jobs waiting to be executed.");

        canceledJobsMetric = mreg.longMetric(CANCELED, "Number of cancelled jobs that are still running.");

        rejectedJobsMetric = mreg.longMetric(REJECTED,
            "Number of jobs rejected after more recent collision resolution operation.");

        finishedJobsMetric = mreg.longMetric(FINISHED, "Number of finished jobs.");

        totalExecutionTimeMetric = mreg.longMetric(EXECUTION_TIME, "Total execution time of jobs.");

        totalWaitTimeMetric = mreg.longMetric(WAITING_TIME, "Total time jobs spent on waiting queue.");

        ctx.systemView().registerInnerCollectionView(JOBS_VIEW, JOBS_VIEW_DESC,
            new ComputeJobViewWalker(),
            passiveJobs == null ?
                Arrays.asList(activeJobs, syncRunningJobs, cancelledJobs) :
                Arrays.asList(activeJobs, syncRunningJobs, passiveJobs, cancelledJobs),
            ConcurrentMap::entrySet,
            (map, e) -> {
                ComputeJobState state = (map == activeJobs || map == syncRunningJobs) ? ComputeJobState.ACTIVE :
                    (map == passiveJobs ? ComputeJobState.PASSIVE : ComputeJobState.CANCELED);

                return new ComputeJobView(e.getKey(), e.getValue(), state);
            });

        CollisionSpi collisionSpi = ctx.config().getCollisionSpi();

        if (!jobAlwaysActivate && collisionSpi instanceof PriorityQueueCollisionSpi) {
            taskPriAttrKey = ((PriorityQueueCollisionSpi)collisionSpi).getPriorityAttributeKey();
            jobPriAttrKey = ((PriorityQueueCollisionSpi)collisionSpi).getJobPriorityAttributeKey();
        }
        else {
            taskPriAttrKey = null;
            jobPriAttrKey = null;
        }

        ctx.internalSubscriptionProcessor().registerDistributedConfigurationListener(dispatcher -> {
            computeJobWorkerInterruptTimeout.addListener(makeUpdateListener(
                "Compute job parameter '%s' was changed from '%s' to '%s'",
                log
            ));

            dispatcher.registerProperty(computeJobWorkerInterruptTimeout);
        });
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        if (metricsUpdateFreq < -1)
            throw new IgniteCheckedException("Invalid value for 'metricsUpdateFrequency' configuration property " +
                "(should be greater than or equals to -1): " + metricsUpdateFreq);

        if (metricsUpdateFreq == -1)
            U.warn(log, "Job metrics are disabled (use with caution).");

        if (!jobAlwaysActivate)
            ctx.collision().setCollisionExternalListener(new CollisionExternalListener());

        GridIoManager ioMgr = ctx.io();

        ioMgr.addMessageListener(TOPIC_JOB_CANCEL, cancelLsnr);
        ioMgr.addMessageListener(TOPIC_JOB, jobExecLsnr);

        ctx.event().addLocalEventListener(discoLsnr, EVT_NODE_FAILED, EVT_NODE_LEFT, EVT_NODE_METRICS_UPDATED);

        if (log.isDebugEnabled())
            log.debug("Job processor started.");
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) {
        // Clear collections.
        syncRunningJobs = new ConcurrentHashMap<>();

        activeJobs = initJobsMap(jobAlwaysActivate);

        activeJobsMetric.reset();

        cancelledJobs.clear();

        cancelReqs = new GridBoundedConcurrentLinkedHashMap<>(FINISHED_JOBS_COUNT,
            FINISHED_JOBS_COUNT < 128 ? FINISHED_JOBS_COUNT : 128,
            0.75f, 16);

        if (log.isDebugEnabled())
            log.debug("Job processor stopped.");
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        // Stop receiving new requests and sending responses.
        GridIoManager commMgr = ctx.io();

        commMgr.removeMessageListener(TOPIC_JOB, jobExecLsnr);
        commMgr.removeMessageListener(TOPIC_JOB_CANCEL, cancelLsnr);

        if (!jobAlwaysActivate)
            // Ignore external collision events.
            ctx.collision().unsetCollisionExternalListener();

        rwLock.writeLock();

        try {
            stopping = true;

            cancelOnStop = cancel;
        }
        finally {
            rwLock.writeUnlock();
        }

        // Rejected jobs.
        if (!jobAlwaysActivate) {
            for (GridJobWorker job : passiveJobs.values())
                if (removeFromPassive(job))
                    rejectJob(job, false);
        }

        // Cancel only if we force grid to stop
        if (cancel) {
            for (GridJobWorker job : activeJobs.values()) {
                job.onStopping();

                cancelJob(job, false);
            }
        }

        U.join(activeJobs.values(), log);
        U.join(cancelledJobs.values(), log);

        // Ignore topology changes.
        ctx.event().removeLocalEventListener(discoLsnr);

        if (log.isDebugEnabled())
            log.debug("Finished executing job processor onKernalStop() callback.");
    }

    /**
     * Gets active job.
     *
     * @param jobId Job ID.
     * @return Active job.
     */
    @Nullable public GridJobWorker activeJob(IgniteUuid jobId) {
        assert jobId != null;

        return activeJobs.get(jobId);
    }

    /**
     * @return {@code True} if running internal task.
     */
    public boolean internal() {
        return internal.get();
    }

    /**
     * Sets internal task flag.
     *
     * @param internal {@code True} if running internal task.
     */
    void internal(boolean internal) {
        this.internal.set(internal);
    }

    /**
     * @param job Rejected job.
     * @param sndReply {@code True} to send reply.
     */
    private void rejectJob(GridJobWorker job, boolean sndReply) {
        IgniteException e = new ComputeExecutionRejectedException("Job was cancelled before execution [taskSesId=" +
            job.getSession().getId() + ", jobId=" + job.getJobId() + ", job=" + job.getJob() + ']');

        job.finishJob(null, e, sndReply);
    }

    /**
     * @param job Canceled job.
     * @param sysCancel {@code True} if job has been cancelled from system and no response needed.
     */
    private void cancelJob(GridJobWorker job, boolean sysCancel) {
        boolean isCancelled = job.isCancelled();

        // We don't increment number of cancelled jobs if it
        // was already cancelled.
        if (!job.isInternal() && !isCancelled) {
            canceledJobsCnt.increment();

            canceledJobsMetric.increment();
        }

        job.cancel(sysCancel);
    }

    /**
     * @param dep Deployment to release.
     */
    private void release(GridDeployment dep) {
        dep.release();

        if (dep.obsolete())
            ctx.resource().onUndeployed(dep);
    }

    /**
     * @param collisionsDisabled If collision SPI is disabled.
     */
    private ConcurrentMap<IgniteUuid, GridJobWorker> initJobsMap(boolean collisionsDisabled) {
        return collisionsDisabled ? new ConcurrentHashMap<IgniteUuid, GridJobWorker>() :
            new JobsMap(1024, 0.75f, 256);
    }

    /**
     * @param ses Session.
     * @param attrs Attributes.
     * @throws IgniteCheckedException If failed.
     */
    public void setAttributes(GridJobSessionImpl ses, Map<?, ?> attrs) throws IgniteCheckedException {
        assert ses.isFullSupport();

        long timeout = ses.getEndTime() - U.currentTimeMillis();

        if (timeout <= 0) {
            U.warn(log, "Task execution timed out (remote session attributes won't be set): " + ses);

            return;
        }

        if (log.isDebugEnabled())
            log.debug("Setting session attribute(s) from job: " + ses);

        ClusterNode taskNode = ctx.discovery().node(ses.getTaskNodeId());

        if (taskNode == null)
            throw new IgniteCheckedException("Node that originated task execution has left grid: " +
                ses.getTaskNodeId());

        boolean loc = ctx.localNodeId().equals(taskNode.id()) && !ctx.config().isMarshalLocalJobs();

        GridTaskSessionRequest req = new GridTaskSessionRequest(ses.getId(), ses.getJobId(),
            loc ? null : U.marshal(marsh, attrs), attrs);

        Object topic = TOPIC_TASK.topic(ses.getJobId(), ctx.discovery().localNode().id());

        // Always go through communication to preserve order.
        ctx.io().sendOrderedMessage(
            taskNode,
            topic, // Job topic.
            req,
            SYSTEM_POOL,
            timeout,
            false);
    }

    /**
     * @param ses Session.
     * @return Siblings.
     * @throws IgniteCheckedException If failed.
     */
    public Collection<ComputeJobSibling> requestJobSiblings(
        final ComputeTaskSession ses) throws IgniteCheckedException {
        assert ses != null;

        final UUID taskNodeId = ses.getTaskNodeId();

        ClusterNode taskNode = ctx.discovery().node(taskNodeId);

        if (taskNode == null)
            throw new IgniteCheckedException("Node that originated task execution has left grid: " + taskNodeId);

        // Tuple: error message-response.
        final IgniteBiTuple<String, GridJobSiblingsResponse> t = new IgniteBiTuple<>();

        final Lock lock = new ReentrantLock();
        final Condition cond = lock.newCondition();

        GridMessageListener msgLsnr = new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                String err = null;
                GridJobSiblingsResponse res = null;

                if (!(msg instanceof GridJobSiblingsResponse))
                    err = "Received unexpected message: " + msg;
                else if (!nodeId.equals(taskNodeId))
                    err = "Received job siblings response from unexpected node [taskNodeId=" + taskNodeId +
                        ", nodeId=" + nodeId + ']';
                else {
                    // Sender and message type are fine.
                    res = (GridJobSiblingsResponse)msg;

                    if (res.jobSiblings() == null) {
                        try {
                            res.unmarshalSiblings(marsh);
                        }
                        catch (IgniteCheckedException e) {
                            U.error(log, "Failed to unmarshal job siblings.", e);

                            err = e.getMessage();
                        }
                    }
                }

                lock.lock();

                try {
                    if (t.isEmpty()) {
                        t.set(err, res);

                        cond.signalAll();
                    }
                }
                finally {
                    lock.unlock();
                }
            }
        };

        GridLocalEventListener discoLsnr = new GridLocalEventListener() {
            @Override public void onEvent(Event evt) {
                assert evt instanceof DiscoveryEvent &&
                    (evt.type() == EVT_NODE_FAILED || evt.type() == EVT_NODE_LEFT) : "Unexpected event: " + evt;

                DiscoveryEvent discoEvt = (DiscoveryEvent)evt;

                if (taskNodeId.equals(discoEvt.eventNode().id())) {
                    lock.lock();

                    try {
                        if (t.isEmpty()) {
                            t.set("Node that originated task execution has left grid: " + taskNodeId, null);

                            cond.signalAll();
                        }
                    }
                    finally {
                        lock.unlock();
                    }
                }
            }
        };

        boolean loc = ctx.localNodeId().equals(taskNodeId);

        // 1. Create unique topic name.
        Object topic = TOPIC_JOB_SIBLINGS.topic(ses.getId(), topicIdGen.getAndIncrement());

        try {
            // 2. Register listener.
            ctx.io().addMessageListener(topic, msgLsnr);

            // 3. Send message.
            ctx.io().sendToGridTopic(taskNode, TOPIC_JOB_SIBLINGS,
                new GridJobSiblingsRequest(ses.getId(),
                    loc ? topic : null,
                    loc ? null : U.marshal(marsh, topic)),
                SYSTEM_POOL);

            // 4. Listen to discovery events.
            ctx.event().addLocalEventListener(discoLsnr, EVT_NODE_FAILED, EVT_NODE_LEFT);

            // 5. Check whether node has left before disco listener has been installed.
            taskNode = ctx.discovery().node(taskNodeId);

            if (taskNode == null)
                throw new IgniteCheckedException("Node that originated task execution has left grid: " + taskNodeId);

            // 6. Wait for result.
            lock.lock();

            try {
                long netTimeout = ctx.config().getNetworkTimeout();

                if (t.isEmpty())
                    cond.await(netTimeout, MILLISECONDS);

                if (t.isEmpty())
                    throw new IgniteCheckedException("Timed out waiting for job siblings (consider increasing" +
                        "'networkTimeout' configuration property) [ses=" + ses + ", netTimeout=" + netTimeout + ']');

                // Error is set?
                if (t.get1() != null)
                    throw new IgniteCheckedException(t.get1());
                else
                    // Return result
                    return t.get2().jobSiblings();
            }
            catch (InterruptedException e) {
                throw new IgniteCheckedException("Interrupted while waiting for job siblings response: " + ses, e);
            }
            finally {
                lock.unlock();
            }
        }
        finally {
            ctx.io().removeMessageListener(topic, msgLsnr);
            ctx.event().removeLocalEventListener(discoLsnr);
        }
    }

    /**
     * Notify processor that master leave aware handler must be invoked on all jobs with the given session ID.
     *
     * @param sesId Session ID.
     */
    public void masterLeaveLocal(IgniteUuid sesId) {
        assert sesId != null;

        for (GridJobWorker job : activeJobs.values())
            if (job.getSession().getId().equals(sesId))
                job.onMasterNodeLeft();
    }

    /**
     * @param sesId Session ID.
     * @param jobId Job ID.
     * @param sys System flag.
     */
    public void cancelJob(@Nullable final IgniteUuid sesId, @Nullable final IgniteUuid jobId, final boolean sys) {
        assert sesId != null || jobId != null;

        rwLock.readLock();

        try {
            if (stopping && cancelOnStop) {
                if (log.isDebugEnabled())
                    log.debug("Received job cancellation request while stopping grid with cancellation " +
                        "(will ignore) [sesId=" + sesId + ", jobId=" + jobId + ", sys=" + sys + ']');

                return;
            }

            // Put either job ID or session ID (they are unique).
            cancelReqs.putIfAbsent(jobId != null ? jobId : sesId, sys);

            Predicate<GridJobWorker> idsMatch = idMatch(sesId, jobId);

            // If we don't have jobId then we have to iterate
            if (jobId == null) {
                if (!jobAlwaysActivate) {
                    for (GridJobWorker job : passiveJobs.values()) {
                        if (idsMatch.test(job))
                            cancelPassiveJob(job);
                    }
                }

                for (GridJobWorker job : activeJobs.values()) {
                    if (idsMatch.test(job))
                        cancelActiveJob(job, sys);
                }

                for (GridJobWorker job : syncRunningJobs.values()) {
                    if (idsMatch.test(job))
                        cancelJob(job, sys);
                }
            }
            else {
                if (!jobAlwaysActivate) {
                    GridJobWorker passiveJob = passiveJobs.get(jobId);

                    if (passiveJob != null && idsMatch.test(passiveJob) && cancelPassiveJob(passiveJob))
                        return;
                }

                GridJobWorker activeJob = activeJobs.get(jobId);

                if (activeJob != null && idsMatch.test(activeJob)) {
                    cancelActiveJob(activeJob, sys);

                    return;
                }

                activeJob = syncRunningJobs.get(jobId);

                if (activeJob != null && idsMatch.test(activeJob))
                    cancelJob(activeJob, sys);
            }
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * Tries to cancel passive job. No-op if job is not in 'passive' state.
     *
     * @param job Job to cancel.
     * @return {@code True} if succeeded.
     */
    private boolean cancelPassiveJob(GridJobWorker job) {
        assert !jobAlwaysActivate;

        if (removeFromPassive(job)) {
            if (log.isDebugEnabled())
                log.debug("Job has been cancelled before activation: " + job);

            canceledJobsCnt.increment();

            canceledJobsMetric.increment();

            return true;
        }

        return false;
    }

    /**
     * Tries to cancel active job. No-op if job is not in 'active' state.
     *
     * @param job Job to cancel.
     * @param sys Flag indicating whether this is a system cancel.
     */
    private void cancelActiveJob(GridJobWorker job, boolean sys) {
        if (removeFromActive(job)) {
            cancelledJobs.put(job.getJobId(), job);

            if (finishedJobs.contains(job.getJobId()))
                // Job has finished concurrently.
                cancelledJobs.remove(job.getJobId(), job);
            else
                // No reply, since it is not cancel from collision.
                cancelJob(job, sys);
        }
    }

    /**
     * @param job Job to remove.
     * @return {@code True} if job actually removed.
     */
    private boolean removeFromActive(GridJobWorker job) {
        boolean res = activeJobs.remove(job.getJobId(), job);

        if (res)
            activeJobsMetric.decrement();

        return res;
    }

    /**
     * @param job Job to remove.
     * @return {@code True} if job actually removed.
     */
    private boolean removeFromPassive(GridJobWorker job) {
        boolean res = passiveJobs.remove(job.getJobId(), job);

        if (res) {
            waitingJobsMetric.decrement();

            if (!jobAlwaysActivate)
                totalWaitTimeMetric.add(job.getQueuedTime());
        }

        return res;
    }

    /**
     * Handles collisions.
     * <p>
     * In most cases this method should be called from main read lock
     * to avoid jobs activation after node stop has started.
     */
    public void handleCollisions() {
        assert !jobAlwaysActivate;

        if (handlingCollision.get()) {
            if (log.isDebugEnabled())
                log.debug("Skipping recursive collision handling.");

            return;
        }

        handlingCollision.set(Boolean.TRUE);

        try {
            if (log.isDebugEnabled())
                log.debug("Before handling collisions.");

            // Invoke collision SPI.
            ctx.collision().onCollision(
                // Passive jobs view.
                new AbstractCollection<org.apache.ignite.spi.collision.CollisionJobContext>() {
                    /** {@inheritDoc} */
                    @NotNull @Override public Iterator<org.apache.ignite.spi.collision.CollisionJobContext> iterator() {
                        final Iterator<GridJobWorker> iter = passiveJobs.values().iterator();

                        return new Iterator<org.apache.ignite.spi.collision.CollisionJobContext>() {
                            /** {@inheritDoc} */
                            @Override public boolean hasNext() {
                                return iter.hasNext();
                            }

                            /** {@inheritDoc} */
                            @Override public org.apache.ignite.spi.collision.CollisionJobContext next() {
                                return new CollisionJobContext(iter.next(), true);
                            }

                            /** {@inheritDoc} */
                            @Override public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    /** {@inheritDoc} */
                    @Override public int size() {
                        return passiveJobs.size();
                    }
                },

                // Active jobs view.
                new AbstractCollection<org.apache.ignite.spi.collision.CollisionJobContext>() {
                    /** {@inheritDoc} */
                    @NotNull @Override public Iterator<org.apache.ignite.spi.collision.CollisionJobContext> iterator() {
                        final Iterator<GridJobWorker> iter = activeJobs.values().iterator();

                        return new Iterator<org.apache.ignite.spi.collision.CollisionJobContext>() {
                            private GridJobWorker w;

                            {
                                advance();
                            }

                            /**
                             *
                             */
                            void advance() {
                                assert w == null;

                                while (iter.hasNext()) {
                                    GridJobWorker w0 = iter.next();

                                    assert !w0.isInternal();

                                    if (!w0.held()) {
                                        w = w0;

                                        break;
                                    }
                                }
                            }

                            /** {@inheritDoc} */
                            @Override public boolean hasNext() {
                                return w != null;
                            }

                            /** {@inheritDoc} */
                            @Override public org.apache.ignite.spi.collision.CollisionJobContext next() {
                                if (w == null)
                                    throw new NoSuchElementException();

                                org.apache.ignite.spi.collision.CollisionJobContext ret = new CollisionJobContext(w, false);

                                w = null;

                                advance();

                                return ret;
                            }

                            /** {@inheritDoc} */
                            @Override public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    /** {@inheritDoc} */
                    @Override public int size() {
                        int ret = activeJobs.size() - heldJobs.size();

                        return Math.max(ret, 0);
                    }
                },

                // Held jobs view.
                new AbstractCollection<org.apache.ignite.spi.collision.CollisionJobContext>() {
                    /** {@inheritDoc} */
                    @NotNull @Override public Iterator<org.apache.ignite.spi.collision.CollisionJobContext> iterator() {
                        final Iterator<GridJobWorker> iter = activeJobs.values().iterator();

                        return new Iterator<org.apache.ignite.spi.collision.CollisionJobContext>() {
                            private GridJobWorker w;

                            {
                                advance();
                            }

                            /**
                             *
                             */
                            void advance() {
                                assert w == null;

                                while (iter.hasNext()) {
                                    GridJobWorker w0 = iter.next();

                                    assert !w0.isInternal();

                                    if (w0.held()) {
                                        w = w0;

                                        break;
                                    }
                                }
                            }

                            /** {@inheritDoc} */
                            @Override public boolean hasNext() {
                                return w != null;
                            }

                            /** {@inheritDoc} */
                            @Override public org.apache.ignite.spi.collision.CollisionJobContext next() {
                                if (w == null)
                                    throw new NoSuchElementException();

                                org.apache.ignite.spi.collision.CollisionJobContext ret =
                                    new CollisionJobContext(w, false);

                                w = null;

                                advance();

                                return ret;
                            }

                            /** {@inheritDoc} */
                            @Override public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    /** {@inheritDoc} */
                    @Override public int size() {
                        return heldJobs.size();
                    }
                });

            updateJobMetrics();
        }
        finally {
            handlingCollision.set(Boolean.FALSE);
        }
    }

    /**
     * This method should be removed in Ignite 3.0.
     *
     * @deprecated Metrics calculated via new subsystem.
     * @see #startedJobsMetric
     * @see #activeJobsMetric
     * @see #waitingJobsMetric
     * @see #canceledJobsMetric
     * @see #rejectedJobsMetric
     * @see #finishedJobsMetric
     */
    @Deprecated
    private void updateJobMetrics() {
        assert metricsUpdateFreq > 0L;

        long now = U.currentTimeMillis();
        long lastUpdate = metricsLastUpdateTstamp.get();

        if (now - lastUpdate > metricsUpdateFreq && metricsLastUpdateTstamp.compareAndSet(lastUpdate, now))
            updateJobMetrics0();

    }

    /**
     * This method should be removed in Ignite 3.0.
     *
     * @deprecated Metrics calculated via new subsystem.
     * @see #startedJobsMetric
     * @see #activeJobsMetric
     * @see #waitingJobsMetric
     * @see #canceledJobsMetric
     * @see #rejectedJobsMetric
     * @see #finishedJobsMetric
     */
    @Deprecated
    private void updateJobMetrics0() {
        assert metricsUpdateFreq > 0L;

        GridJobMetricsSnapshot m = new GridJobMetricsSnapshot();

        m.setRejectJobs((int)rejectedJobsCnt.sumThenReset());
        m.setStartedJobs((int)startedJobsCnt.sumThenReset());

        // Iterate over active jobs to determine max execution time.
        int cnt = 0;

        for (GridJobWorker jobWorker : activeJobs.values()) {
            assert !jobWorker.isInternal();

            cnt++;

            if (!jobWorker.held()) {
                long execTime = jobWorker.getExecuteTime();

                if (execTime > m.getMaximumExecutionTime())
                    m.setMaximumExecutionTime(execTime);
            }
        }

        m.setActiveJobs(cnt);

        cnt = 0;

        // Do this only if collision SPI is used. Otherwise, 0 is correct value
        // for passive jobs count and max wait time.
        if (!jobAlwaysActivate) {
            // Iterate over passive jobs to determine max queued time.
            for (GridJobWorker jobWorker : passiveJobs.values()) {
                // We don't expect that there are any passive internal jobs.
                assert !jobWorker.isInternal();

                cnt++;

                long queuedTime = jobWorker.getQueuedTime();

                if (queuedTime > m.getMaximumWaitTime())
                    m.setMaximumWaitTime(queuedTime);

                m.setWaitTime(m.getWaitTime() + jobWorker.getQueuedTime());
            }

            m.setPassiveJobs(cnt);
        }

        m.setFinishedJobs((int)finishedJobsCnt.sumThenReset());
        m.setExecutionTime(finishedJobsTime.sumThenReset());
        m.setCancelJobs((int)canceledJobsCnt.sumThenReset());

        long maxFinishedTime = maxFinishedJobsTime.getAndSet(0);

        if (maxFinishedTime > m.getMaximumExecutionTime())
            m.setMaximumExecutionTime(maxFinishedTime);

        // CPU load.
        m.setCpuLoad(cpuLoadMetric.value());

        ctx.jobMetric().addSnapshot(m);
    }

    /**
     * @param node Node.
     * @param req Request.
     */
    @SuppressWarnings("TooBroadScope")
    public void processJobExecuteRequest(ClusterNode node, final GridJobExecuteRequest req) {
        if (log.isDebugEnabled())
            log.debug("Received job request message [req=" + req + ", nodeId=" + node.id() + ']');

        PartitionsReservation partsReservation = null;

        if (req.getCacheIds() != null) {
            assert req.getPartition() >= 0 : req;
            assert !F.isEmpty(req.getCacheIds()) : req;

            partsReservation = new PartitionsReservation(req.getCacheIds(), req.getPartition(), req.getTopVer());
        }

        GridJobWorker job = null;

        if (!rwLock.tryReadLock()) {
            if (log.isDebugEnabled())
                log.debug("Received job execution request while stopping this node (will ignore): " + req);

            return;
        }

        try {
            long endTime = req.getCreateTime() + req.getTimeout();

            // Account for overflow.
            if (endTime < 0)
                endTime = Long.MAX_VALUE;

            GridDeployment tmpDep = req.isForceLocalDeployment() ?
                ctx.deploy().getLocalDeployment(req.getTaskClassName()) :
                ctx.deploy().getGlobalDeployment(
                    req.getDeploymentMode(),
                    req.getTaskName(),
                    req.getTaskClassName(),
                    req.getUserVersion(),
                    node.id(),
                    req.getClassLoaderId(),
                    req.getLoaderParticipants(),
                    null);

            if (tmpDep == null) {
                if (log.isDebugEnabled())
                    log.debug("Checking local tasks...");

                // Check local tasks.
                for (Map.Entry<String, GridDeployment> d : ctx.task().getUsedDeploymentMap().entrySet()) {
                    if (d.getValue().classLoaderId().equals(req.getClassLoaderId())) {
                        assert d.getValue().local();

                        tmpDep = d.getValue();

                        break;
                    }
                }
            }

            final GridDeployment dep = tmpDep;

            if (log.isDebugEnabled())
                log.debug("Deployment: " + dep);

            boolean releaseDep = true;

            try {
                if (dep != null && dep.acquire()) {
                    GridJobSessionImpl jobSes;
                    GridJobContextImpl jobCtx;

                    try {
                        List<ComputeJobSibling> siblings = null;

                        if (!req.isDynamicSiblings()) {
                            Collection<ComputeJobSibling> siblings0 = req.getSiblings();

                            if (siblings0 == null) {
                                assert req.getSiblingsBytes() != null;

                                siblings0 = U.unmarshal(marsh, req.getSiblingsBytes(), U.resolveClassLoader(ctx.config()));
                            }

                            siblings = new ArrayList<>(siblings0);
                        }

                        Map<Object, Object> sesAttrs = null;

                        if (req.isSessionFullSupport()) {
                            sesAttrs = req.getSessionAttributes();

                            if (sesAttrs == null)
                                sesAttrs = U.unmarshal(marsh, req.getSessionAttributesBytes(),
                                    U.resolveClassLoader(dep.classLoader(), ctx.config()));
                        }

                        IgnitePredicate<ClusterNode> topPred = req.getTopologyPredicate();

                        if (topPred == null && req.getTopologyPredicateBytes() != null) {
                            topPred = U.unmarshal(marsh, req.getTopologyPredicateBytes(),
                                U.resolveClassLoader(dep.classLoader(), ctx.config()));
                        }

                        // Note that we unmarshal session/job attributes here with proper class loader.
                        GridTaskSessionImpl taskSes = ctx.session().createTaskSession(
                            req.getSessionId(),
                            node.id(),
                            req.getTaskName(),
                            dep,
                            req.getTaskClassName(),
                            req.topology(),
                            topPred,
                            req.getStartTaskTime(),
                            endTime,
                            siblings,
                            sesAttrs,
                            req.isSessionFullSupport(),
                            req.isInternal(),
                            req.executorName(),
                            ctx.security().securityContext()
                        );

                        taskSes.setCheckpointSpi(req.getCheckpointSpi());
                        taskSes.setClassLoader(dep.classLoader());

                        jobSes = new GridJobSessionImpl(ctx, taskSes, req.getJobId());

                        Map<? extends Serializable, ? extends Serializable> jobAttrs = req.getJobAttributes();

                        if (jobAttrs == null)
                            jobAttrs = U.unmarshal(marsh, req.getJobAttributesBytes(),
                                U.resolveClassLoader(dep.classLoader(), ctx.config()));

                        jobCtx = new GridJobContextImpl(ctx, req.getJobId(), jobAttrs);
                    }
                    catch (IgniteCheckedException e) {
                        IgniteException ex = new IgniteException("Failed to deserialize task attributes " +
                            "[taskName=" + req.getTaskName() + ", taskClsName=" + req.getTaskClassName() +
                            ", codeVer=" + req.getUserVersion() + ", taskClsLdr=" + dep.classLoader() + ']', e);

                        U.error(log, ex.getMessage(), e);

                        handleException(node, req, ex, endTime);

                        return;
                    }

                    job = new GridJobWorker(
                        ctx,
                        dep,
                        req.getCreateTime(),
                        jobSes,
                        jobCtx,
                        req.getJobBytes(),
                        req.getJob(),
                        node,
                        req.isInternal(),
                        evtLsnr,
                        holdLsnr,
                        partsReservation,
                        req.getTopVer(),
                        req.executorName(),
                        this::computeJobWorkerInterruptTimeout
                    );

                    jobCtx.job(job);

                    // If exception occurs on job initialization, deployment is released in job listener.
                    releaseDep = false;

                    if (job.initialize(dep, dep.deployedClass(req.getTaskClassName()).get1())) {
                        // Internal jobs will always be executed synchronously.
                        if (job.isInternal()) {
                            // This is an internal job and can be executed inside busy lock
                            // since job is expected to be short.
                            // This is essential for proper stop without races.
                            runSync(job);

                            // No execution outside lock.
                            job = null;
                        }
                        else if (jobAlwaysActivate) {
                            if (onBeforeActivateJob(job)) {
                                if (ctx.localNodeId().equals(node.id())) {
                                    // Always execute in another thread for local node.
                                    executeAsync(job);

                                    // No sync execution.
                                    job = null;
                                }
                                else {
                                    if (metricsUpdateFreq > -1L)
                                        // Job will be executed synchronously.
                                        startedJobsCnt.increment();

                                    startedJobsMetric.increment();
                                }

                            }
                            else
                                // Job has been cancelled.
                                // Set to null, to avoid sync execution.
                                job = null;
                        }
                        else {
                            GridJobWorker old = passiveJobs.putIfAbsent(job.getJobId(), job);

                            if (old == null) {
                                waitingJobsMetric.increment();

                                handleCollisions();
                            }
                            else
                                U.error(log, "Received computation request with duplicate job ID (could be " +
                                    "network malfunction, source node may hang if task timeout was not set) " +
                                    "[srcNode=" + node.id() +
                                    ", jobId=" + req.getJobId() + ", sesId=" + req.getSessionId() +
                                    ", locNodeId=" + ctx.localNodeId() + ']');

                            // No sync execution.
                            job = null;
                        }
                    }
                    else
                        // Job was not initialized, no execution.
                        job = null;
                }
                else {
                    // Deployment is null.
                    IgniteException ex = new IgniteDeploymentException("Task was not deployed or was redeployed since " +
                        "task execution [taskName=" + req.getTaskName() + ", taskClsName=" + req.getTaskClassName() +
                        ", codeVer=" + req.getUserVersion() + ", clsLdrId=" + req.getClassLoaderId() +
                        ", seqNum=" + req.getClassLoaderId().localId() + ", depMode=" + req.getDeploymentMode() +
                        ", dep=" + dep + ']');

                    U.error(log, ex.getMessage(), ex);

                    handleException(node, req, ex, endTime);
                }
            }
            finally {
                if (dep != null && releaseDep)
                    release(dep);
            }
        }
        finally {
            rwLock.readUnlock();
        }

        if (job != null)
            job.run();
    }

    /**
     * Adds job to {@link #syncRunningJobs} while run to provide info in system view.
     * @param job Job to add in system view and run synchronously.
     */
    private void runSync(GridJobWorker job) {
        IgniteUuid jobId = job.getJobId();

        syncRunningJobs.put(jobId, job);

        try {
            job.run();
        }
        finally {
            syncRunningJobs.remove(jobId);
        }
    }

    /**
     * Callback from job worker to set current task session for execution.
     *
     * @param ses Session.
     */
    public void currentTaskSession(GridJobSessionImpl ses) {
        currSess.set(ses);
    }

    /**
     * Gets hash of task name executed by current thread.
     *
     * @return Task name hash or {@code 0} if security is disabled.
     */
    public int currentTaskNameHash() {
        String name = currentTaskName();

        return name == null ? 0 : name.hashCode();
    }

    /**
     * Gets name task executed by current thread.
     *
     * @return Task name or {@code null} if security is disabled.
     */
    public String currentTaskName() {
        if (!ctx.security().enabled())
            return null;

        ComputeTaskSession ses = currSess.get();

        if (ses == null)
            return null;

        return ses.getTaskName();
    }

    /**
     * Returns current deployment.
     *
     * @return Deployment.
     */
    public GridDeployment currentDeployment() {
        GridJobSessionImpl ses = currSess.get();

        if (ses == null || ses.deployment() == null)
            return null;

        return ses.deployment();
    }

    /**
     * @param jobWorker Worker.
     * @return {@code True} if job has not been cancelled and should be activated.
     */
    private boolean onBeforeActivateJob(GridJobWorker jobWorker) {
        assert jobWorker != null;

        activeJobs.put(jobWorker.getJobId(), jobWorker);

        activeJobsMetric.increment();

        // Check if job has been concurrently cancelled.
        Boolean sysCancelled = cancelReqs.get(jobWorker.getSession().getId());

        if (sysCancelled == null)
            sysCancelled = cancelReqs.get(jobWorker.getJobId());

        if (sysCancelled != null) {
            // Job has been concurrently cancelled.
            // Remove from active jobs.
            removeFromActive(jobWorker);

            // Even if job has been removed from another thread, we need to reject it
            // here since job has never been executed.
            IgniteException e2 = new ComputeExecutionRejectedException(
                "Job was cancelled before execution [jobSes=" + jobWorker.
                    getSession() + ", job=" + jobWorker.getJob() + ']');

            jobWorker.finishJob(null, e2, !sysCancelled);

            return false;
        }

        // Job has not been cancelled and should be activated.
        // However we need to check if master is alive before job will get
        // its runner thread for proper master leave handling.
        if (ctx.discovery().node(jobWorker.getTaskNode().id()) == null &&
            removeFromActive(jobWorker)) {
            // Add to cancelled jobs.
            cancelledJobs.put(jobWorker.getJobId(), jobWorker);

            if (!jobWorker.onMasterNodeLeft()) {
                U.warn(log, "Job is being cancelled because master task node left grid " +
                    "(as there is no one waiting for results, job will not be failed over): " +
                    jobWorker.getJobId());

                cancelJob(jobWorker, true);
            }
        }

        return true;
    }

    /**
     * @param jobWorker Job worker.
     * @return {@code True} if job has been submitted to pool.
     */
    private boolean executeAsync(GridJobWorker jobWorker) {
        try {
            if (jobWorker.executorName() != null) {
                Executor customExec = ctx.pools().customExecutor(jobWorker.executorName());

                if (customExec != null)
                    customExec.execute(jobWorker);
                else {
                    LT.warn(log, "Custom executor doesn't exist (local job will be processed in default " +
                        "thread pool): " + jobWorker.executorName());

                    ctx.pools().getExecutorService().execute(jobWorker);
                }
            }
            else
                ctx.pools().getExecutorService().execute(jobWorker);

            if (metricsUpdateFreq > -1L)
                startedJobsCnt.increment();

            startedJobsMetric.increment();

            return true;
        }
        catch (RejectedExecutionException e) {
            // Remove from active jobs.
            removeFromActive(jobWorker);

            // Even if job was removed from another thread, we need to reject it
            // here since job has never been executed.
            IgniteException e2 = new ComputeExecutionRejectedException("Job has been rejected " +
                "[jobSes=" + jobWorker.getSession() + ", job=" + jobWorker.getJob() + ']', e);

            if (metricsUpdateFreq > -1L)
                rejectedJobsCnt.increment();

            rejectedJobsMetric.increment();

            jobWorker.finishJob(null, e2, true);
        }

        return false;
    }

    /**
     * Handles errors that happened prior to job creation.
     *
     * @param node Sender node.
     * @param req Job execution request.
     * @param ex Exception that happened.
     * @param endTime Job end time.
     */
    private void handleException(ClusterNode node, GridJobExecuteRequest req, IgniteException ex, long endTime) {
        UUID locNodeId = ctx.localNodeId();

        ClusterNode sndNode = ctx.discovery().node(node.id());

        if (sndNode == null) {
            U.warn(log, "Failed to reply to sender node because it left grid [nodeId=" + node.id() +
                ", jobId=" + req.getJobId() + ']');

            if (ctx.event().isRecordable(EVT_JOB_FAILED)) {
                JobEvent evt = new JobEvent();

                evt.jobId(req.getJobId());
                evt.message("Job reply failed (original task node left grid): " + req.getJobId());
                evt.node(ctx.discovery().localNode());
                evt.taskName(req.getTaskName());
                evt.taskClassName(req.getTaskClassName());
                evt.taskSessionId(req.getSessionId());
                evt.type(EVT_JOB_FAILED);
                evt.taskNode(node);
                evt.taskSubjectId(securitySubjectId(ctx));

                // Record job reply failure.
                ctx.event().record(evt);
            }

            return;
        }

        try {
            boolean loc = ctx.localNodeId().equals(sndNode.id()) && !ctx.config().isMarshalLocalJobs();

            GridJobExecuteResponse jobRes = new GridJobExecuteResponse(
                locNodeId,
                req.getSessionId(),
                req.getJobId(),
                loc ? null : U.marshal(marsh, ex),
                ex,
                loc ? null : U.marshal(marsh, null),
                null,
                loc ? null : U.marshal(marsh, null),
                null,
                false,
                null);

            if (req.isSessionFullSupport()) {
                // Send response to designated job topic.
                // Always go through communication to preserve order,
                // if attributes are enabled.
                // Job response topic.
                Object topic = TOPIC_TASK.topic(req.getJobId(), locNodeId);

                long timeout = endTime - U.currentTimeMillis();

                if (timeout <= 0)
                    // Ignore the actual timeout and send response anyway.
                    timeout = 1;

                // Send response to designated job topic.
                // Always go through communication to preserve order.
                ctx.io().sendOrderedMessage(
                    sndNode,
                    topic,
                    jobRes,
                    req.isInternal() ? MANAGEMENT_POOL : SYSTEM_POOL,
                    timeout,
                    false);
            }
            else if (ctx.localNodeId().equals(sndNode.id()))
                ctx.task().processJobExecuteResponse(ctx.localNodeId(), jobRes);
            else
                // Send response to common topic as unordered message.
                ctx.io().sendToGridTopic(sndNode, TOPIC_TASK, jobRes, req.isInternal() ? MANAGEMENT_POOL : SYSTEM_POOL);
        }
        catch (IgniteCheckedException e) {
            // The only option here is to log, as we must assume that resending will fail too.
            if ((e instanceof ClusterTopologyCheckedException) || isDeadNode(node.id()))
                // Avoid stack trace for left nodes.
                U.error(log, "Failed to reply to sender node because it left grid [nodeId=" + node.id() +
                    ", jobId=" + req.getJobId() + ']');
            else {
                assert sndNode != null;

                U.error(log, "Error sending reply for job [nodeId=" + sndNode.id() + ", jobId=" +
                    req.getJobId() + ']', e);
            }

            if (ctx.event().isRecordable(EVT_JOB_FAILED)) {
                JobEvent evt = new JobEvent();

                evt.jobId(req.getJobId());
                evt.message("Failed to send reply for job: " + req.getJobId());
                evt.node(ctx.discovery().localNode());
                evt.taskName(req.getTaskName());
                evt.taskClassName(req.getTaskClassName());
                evt.taskSessionId(req.getSessionId());
                evt.type(EVT_JOB_FAILED);
                evt.taskNode(node);
                evt.taskSubjectId(securitySubjectId(ctx));

                // Record job reply failure.
                ctx.event().record(evt);
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    @SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter"})
    private void processTaskSessionRequest(UUID nodeId, GridTaskSessionRequest req) {
        if (!rwLock.tryReadLock()) {
            if (log.isDebugEnabled())
                log.debug("Received job session request while stopping grid (will ignore): " + req);

            return;
        }

        try {
            GridTaskSessionImpl ses = ctx.session().getSession(req.getSessionId());

            if (ses == null) {
                if (log.isDebugEnabled())
                    log.debug("Received job session request for non-existing session: " + req);

                return;
            }

            boolean loc = ctx.localNodeId().equals(nodeId) && !ctx.config().isMarshalLocalJobs();

            Map<?, ?> attrs = loc ? req.getAttributes() :
                (Map<?, ?>)U.unmarshal(marsh, req.getAttributesBytes(),
                    U.resolveClassLoader(ses.getClassLoader(), ctx.config()));

            if (ctx.event().isRecordable(EVT_TASK_SESSION_ATTR_SET)) {
                Event evt = new TaskEvent(
                    ctx.discovery().localNode(),
                    "Changed attributes: " + attrs,
                    EVT_TASK_SESSION_ATTR_SET,
                    ses.getId(),
                    ses.getTaskName(),
                    ses.getTaskClassName(),
                    false,
                    null);

                ctx.event().record(evt);
            }

            synchronized (ses) {
                ses.setInternal(attrs);
            }

            onChangeTaskAttributes(req.getSessionId(), req.getJobId(), attrs);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to deserialize session attributes.", e);
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * Checks whether node is alive or dead.
     *
     * @param uid UID of node to check.
     * @return {@code true} if node is dead, {@code false} is node is alive.
     */
    private boolean isDeadNode(UUID uid) {
        return ctx.discovery().node(uid) == null || !ctx.discovery().pingNodeNoError(uid);
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> Job processor memory stats [igniteInstanceName=" + ctx.igniteInstanceName() + ']');
        X.println(">>>   activeJobsSize: " + activeJobs.size());
        X.println(">>>   passiveJobsSize: " + (jobAlwaysActivate ? "n/a" : passiveJobs.size()));
        X.println(">>>   cancelledJobsSize: " + cancelledJobs.size());
        X.println(">>>   cancelReqsSize: " + cancelReqs.sizex());
        X.println(">>>   finishedJobsSize: " + finishedJobs.sizex());
    }

    /**
     *
     */
    public class PartitionsReservation implements GridReservable {
        /** Caches. */
        private final int[] cacheIds;

        /** Partition id. */
        private final int partId;

        /** Topology version. */
        private final AffinityTopologyVersion topVer;

        /** Partitions. */
        private GridDhtLocalPartition[] partititons;

        /**
         * @param cacheIds Cache identifiers array.
         * @param partId Partition number.
         * @param topVer Affinity topology version.
         */
        public PartitionsReservation(int[] cacheIds, int partId,
            AffinityTopologyVersion topVer) {
            this.cacheIds = cacheIds;
            this.partId = partId;
            this.topVer = topVer;
            partititons = new GridDhtLocalPartition[cacheIds.length];
        }

        /** @return Caches identifiers. */
        public int[] getCacheIds() {
            return cacheIds;
        }

        /** @return Partition id. */
        public int getPartId() {
            return partId;
        }

        /** {@inheritDoc} */
        @Override public boolean reserve() {
            boolean reserved = false;

            try {
                for (int i = 0; i < cacheIds.length; ++i) {
                    GridCacheContext<?, ?> cctx = ctx.cache().context().cacheContext(cacheIds[i]);

                    if (cctx == null) // Cache was not found, probably was not deployed yet.
                        return reserved;

                    if (!cctx.started()) // Cache not started.
                        return reserved;

                    if (!cctx.rebalanceEnabled())
                        continue;

                    boolean checkPartMapping = false;

                    try {
                        if (cctx.isReplicated()) {
                            GridDhtLocalPartition part = cctx.topology().localPartition(partId,
                                topVer, false);

                            // We don't need to reserve partitions because they will not be evicted in replicated caches.
                            if (part == null || part.state() != OWNING) {
                                checkPartMapping = true;

                                return reserved;
                            }
                        }

                        GridDhtLocalPartition part = cctx.topology().localPartition(partId, topVer, false);

                        if (part == null || part.state() != OWNING || !part.reserve()) {
                            checkPartMapping = true;

                            return reserved;
                        }

                        partititons[i] = part;

                        // Double check that we are still in owning state and partition contents are not cleared.
                        if (part.state() != OWNING) {
                            checkPartMapping = true;

                            return reserved;
                        }
                    }
                    finally {
                        if (checkPartMapping && !cctx.affinity().primaryByPartition(partId, topVer).id().equals(ctx.localNodeId()))
                            throw new IgniteException("Failed partition reservation. " +
                                "Partition is not primary on the node. [partition=" + partId + ", cacheName=" + cctx.name() +
                                ", nodeId=" + ctx.localNodeId() + ", topology=" + topVer + ']');
                    }
                }

                reserved = true;
            }
            finally {
                if (!reserved)
                    release();
            }

            return true;
        }

        /** {@inheritDoc} */
        @Override public void release() {
            for (int i = 0; i < partititons.length; ++i) {
                if (partititons[i] == null)
                    break;

                partititons[i].release();
                partititons[i] = null;
            }
        }
    }

    /**
     *
     */
    private class CollisionJobContext extends GridCollisionJobContextAdapter {
        /** */
        private final boolean passive;

        /**
         * @param jobWorker Job Worker.
         * @param passive {@code True} if job is on waiting list on creation time.
         */
        CollisionJobContext(GridJobWorker jobWorker, boolean passive) {
            super(jobWorker);

            assert !jobWorker.isInternal();
            assert !jobAlwaysActivate;

            this.passive = passive;
        }

        /** {@inheritDoc} */
        @Override public boolean activate() {
            GridJobWorker jobWorker = getJobWorker();

            return removeFromPassive(jobWorker) &&
                onBeforeActivateJob(jobWorker) &&
                executeAsync(jobWorker);
        }

        /** {@inheritDoc} */
        @Override public boolean cancel() {
            GridJobWorker jobWorker = getJobWorker();

            cancelReqs.putIfAbsent(jobWorker.getJobId(), false);

            boolean ret = false;

            if (passive) {
                // If waiting job being rejected.
                if (removeFromPassive(jobWorker)) {
                    rejectJob(jobWorker, true);

                    if (metricsUpdateFreq > -1L)
                        rejectedJobsCnt.increment();

                    rejectedJobsMetric.increment();

                    ret = true;
                }
            }
            // If active job being cancelled.
            else if (removeFromActive(jobWorker)) {
                cancelledJobs.put(jobWorker.getJobId(), jobWorker);

                if (finishedJobs.contains(jobWorker.getJobId()))
                    // Job has finished concurrently.
                    cancelledJobs.remove(jobWorker.getJobId(), jobWorker);
                else
                    // We do apply cancel as many times as user cancel job.
                    cancelJob(jobWorker, false);

                ret = true;
            }

            return ret;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(CollisionJobContext.class, this);
        }
    }

    /**
     *
     */
    private class CollisionExternalListener implements org.apache.ignite.spi.collision.CollisionExternalListener {
        /** {@inheritDoc} */
        @Override public void onExternalCollision() {
            assert !jobAlwaysActivate;

            if (log.isDebugEnabled())
                log.debug("Received external collision event.");

            if (!rwLock.tryReadLock()) {
                if (log.isDebugEnabled())
                    log.debug("Received external collision notification while stopping grid (will ignore).");

                return;
            }

            try {
                handleCollisions();
            }
            finally {
                rwLock.readUnlock();
            }
        }
    }

    /**
     * Handles job state changes.
     */
    private class JobEventListener implements GridJobEventListener {
        /** */
        private final GridMessageListener sesLsnr = new JobSessionListener();

        /** {@inheritDoc} */
        @Override public void onJobQueued(GridJobWorker worker) {
            if (worker.getSession().isFullSupport()) {
                // Register session request listener for this job.
                ctx.io().addMessageListener(worker.getJobTopic(), sesLsnr);
            }
        }

        /** {@inheritDoc} */
        @Override public void onJobStarted(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onJobStarted() callback: " + worker);

            if (metricsUpdateFreq > -1L)
                updateJobMetrics();

            // Register for timeout notifications.
            if (worker.endTime() < Long.MAX_VALUE)
                ctx.timeout().addTimeoutObject(worker);
        }

        /** {@inheritDoc} */
        @Override public void onBeforeJobResponseSent(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onBeforeJobResponseSent() callback: " + worker);

            assert jobAlwaysActivate || !passiveJobs.containsKey(worker.getJobId());

            if (worker.getSession().isFullSupport()) {
                // Unregister session request listener for this jobs.
                ctx.io().removeMessageListener(worker.getJobTopic());
            }
        }

        /** {@inheritDoc} */
        @Override public void onJobFinished(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onJobFinished() callback: " + worker);

            GridJobSessionImpl ses = worker.getSession();

            // If last job for the task on this node.
            if (ses.isFullSupport() && ctx.session().removeSession(ses.getId())) {
                ses.onClosed();

                // Unregister checkpoints.
                ctx.checkpoint().onSessionEnd(ses, true);
            }

            // Unregister from timeout notifications.
            if (worker.endTime() < Long.MAX_VALUE)
                ctx.timeout().removeTimeoutObject(worker);

            release(worker.getDeployment());

            finishedJobs.add(worker.getJobId());

            if (!worker.isInternal()) {
                // Increment job execution counter. This counter gets
                // reset once this job will be accounted for in metrics.
                finishedJobsCnt.increment();

                finishedJobsMetric.increment();

                // Increment job execution time. This counter gets
                // reset once this job will be accounted for in metrics.
                long execTime = worker.getExecuteTime();

                finishedJobsTime.add(execTime);

                totalExecutionTimeMetric.add(execTime);

                maxFinishedJobsTime.setIfGreater(execTime);

                if (jobAlwaysActivate) {
                    if (metricsUpdateFreq > -1L)
                        updateJobMetrics();

                    if (!removeFromActive(worker))
                        cancelledJobs.remove(worker.getJobId(), worker);

                    heldJobs.remove(worker.getJobId());
                }
                else {
                    if (!rwLock.tryReadLock()) {
                        if (log.isDebugEnabled())
                            log.debug("Skipping collision handling on job finish (node is stopping).");

                        return;
                    }

                    if (!removeFromActive(worker))
                        cancelledJobs.remove(worker.getJobId(), worker);

                    heldJobs.remove(worker.getJobId());

                    try {
                        handleCollisions();
                    }
                    finally {
                        rwLock.readUnlock();
                    }
                }
            }

            if (ctx.performanceStatistics().enabled()) {
                ctx.performanceStatistics().job(ses.getId(),
                    worker.getQueuedTime(),
                    worker.getStartTime(),
                    worker.getExecuteTime(),
                    worker.isTimedOut());
            }
        }
    }

    /**
     *
     */
    private class JobHoldListener implements GridJobHoldListener {
        /** {@inheritDoc} */
        @Override public boolean onHeld(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onHeld() callback [worker=" + worker + ']');

            if (worker.isInternal())
                return true;

            boolean res = false;

            if (activeJobs.containsKey(worker.getJobId())) {
                res = heldJobs.add(worker.getJobId());

                if (!activeJobs.containsKey(worker.getJobId())) {
                    heldJobs.remove(worker.getJobId());

                    // Job has been completed and therefore cannot be held.
                    res = false;
                }
            }

            return res;
        }

        /** {@inheritDoc} */
        @Override public boolean onUnheld(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onUnheld() callback [worker=" + worker + ", active=" + activeJobs +
                    ", held=" + heldJobs + ']');

            if (worker.isInternal())
                return true;

            return heldJobs.remove(worker.getJobId());
        }
    }

    /**
     *
     */
    private class JobSessionListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
            assert nodeId != null;
            assert msg != null;

            if (log.isDebugEnabled())
                log.debug("Received session attribute request message [msg=" + msg + ", nodeId=" + nodeId + ']');

            processTaskSessionRequest(nodeId, (GridTaskSessionRequest)msg);
        }
    }

    /**
     * Handles task and job cancellations.
     */
    private class JobCancelListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
            assert nodeId != null;
            assert msg != null;

            GridJobCancelRequest cancelMsg = (GridJobCancelRequest)msg;

            if (log.isDebugEnabled())
                log.debug("Received job cancel request [cancelMsg=" + cancelMsg + ", nodeId=" + nodeId + ']');

            cancelJob(cancelMsg.sessionId(), cancelMsg.jobId(), cancelMsg.system());
        }
    }

    /**
     * Handles job execution requests.
     */
    private class JobExecutionListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
            assert nodeId != null;
            assert msg != null;

            ClusterNode node = ctx.discovery().node(nodeId);

            if (!ctx.discovery().alive(nodeId)) {
                U.warn(log, "Received job request message from unknown node (ignoring) " +
                    "[msg=" + msg + ", nodeId=" + nodeId + ']');

                return;
            }

            assert node != null;

            processJobExecuteRequest(node, (GridJobExecuteRequest)msg);
        }
    }

    /**
     * Listener to node discovery events.
     */
    private class JobDiscoveryListener implements GridLocalEventListener {
        /**
         * Counter used to determine whether all nodes updated metrics or not.
         * This counter is reset every time collisions are handled.
         */
        private int metricsUpdateCntr;

        /** {@inheritDoc} */
        @Override public void onEvent(Event evt) {
            assert evt instanceof DiscoveryEvent;

            boolean handleCollisions = false;

            UUID nodeId = ((DiscoveryEvent)evt).eventNode().id();

            // We should always process discovery events (even on stop,
            // since we wait for jobs to complete if processor is stopped
            // without cancellation).
            switch (evt.type()) {
                case EVT_NODE_LEFT:
                case EVT_NODE_FAILED:
                    if (!jobAlwaysActivate) {
                        for (GridJobWorker job : passiveJobs.values()) {
                            if (job.getTaskNode().id().equals(nodeId)) {
                                if (removeFromPassive(job))
                                    U.warn(log, "Task node left grid (job will not be activated) " +
                                        "[nodeId=" + nodeId + ", jobSes=" + job.getSession() + ", job=" + job + ']');
                            }
                        }
                    }

                    for (GridJobWorker job : activeJobs.values()) {
                        if (job.getTaskNode().id().equals(nodeId) && !job.isFinishing() &&
                            removeFromActive(job)) {
                            // Add to cancelled jobs.
                            cancelledJobs.put(job.getJobId(), job);

                            if (finishedJobs.contains(job.getJobId()))
                                // Job has finished concurrently.
                                cancelledJobs.remove(job.getJobId(), job);
                            else if (!job.onMasterNodeLeft()) {
                                U.warn(log, "Job is being cancelled because master task node left grid " +
                                    "(as there is no one waiting for results, job will not be failed over): " +
                                    job.getJobId());

                                cancelJob(job, true);
                            }
                        }
                    }

                    handleCollisions = true;

                    break;

                case EVT_NODE_METRICS_UPDATED:
                    // Check for less-than-equal rather than just equal
                    // in guard against topology changes.
                    if (ctx.discovery().allNodes().size() <= ++metricsUpdateCntr) {
                        metricsUpdateCntr = 0;

                        handleCollisions = true;
                    }

                    break;

                default:
                    assert false;
            }

            if (handleCollisions) {
                if (!rwLock.tryReadLock()) {
                    if (log.isDebugEnabled())
                        log.debug("Skipped collision handling on discovery event (node is stopping): " + evt);

                    return;
                }

                try {
                    if (!jobAlwaysActivate)
                        handleCollisions();
                    else if (metricsUpdateFreq > -1L)
                        updateJobMetrics();
                }
                finally {
                    rwLock.readUnlock();
                }
            }
        }
    }

    /**
     *
     */
    private class JobsMap extends ConcurrentLinkedHashMap<IgniteUuid, GridJobWorker> {
        /**
         * @param initCap Initial capacity.
         * @param loadFactor Load factor.
         * @param concurLvl Concurrency level.
         */
        private JobsMap(int initCap, float loadFactor, int concurLvl) {
            super(initCap, loadFactor, concurLvl);
        }

        /** {@inheritDoc} */
        @Override public GridJobWorker put(IgniteUuid key, GridJobWorker val) {
            assert !val.isInternal();

            GridJobWorker old = super.put(key, val);

            if (old != null)
                U.warn(log, "Jobs map already contains mapping for key [key=" + key + ", val=" + val +
                    ", old=" + old + ']');

            return old;
        }

        /** {@inheritDoc} */
        @Override public GridJobWorker putIfAbsent(IgniteUuid key, GridJobWorker val) {
            assert !val.isInternal();

            GridJobWorker old = super.putIfAbsent(key, val);

            if (old != null)
                U.warn(log, "Jobs map already contains mapping for key [key=" + key + ", val=" + val +
                    ", old=" + old + ']');

            return old;
        }

        /**
         * @return Constant-time {@code size()}.
         */
        @Override public int size() {
            return sizex();
        }
    }

    /**
     * Callback on changing task attributes.
     *
     * @param sesId Session ID.
     * @param jobId Job ID.
     * @param attrs Changed attributes.
     */
    public void onChangeTaskAttributes(IgniteUuid sesId, IgniteUuid jobId, Map<?, ?> attrs) {
        if (!rwLock.tryReadLock()) {
            if (log.isDebugEnabled())
                log.debug("Callback on changing the task attributes will be ignored " +
                    "(node is in the process of stopping): " + sesId);

            return;
        }

        try {
            if (jobAlwaysActivate || (taskPriAttrKey == null && jobPriAttrKey == null))
                return;

            GridJobWorker jobWorker = passiveJobs.get(jobId);

            if (jobWorker == null || jobWorker.isInternal())
                return;

            boolean handleCollisions = false;

            if (taskPriAttrKey != null && attrs.containsKey(taskPriAttrKey)) {
                // See PriorityQueueCollisionSpi#bumpPriority.
                jobWorker.getSession().setAttribute(jobPriAttrKey, attrs.get(taskPriAttrKey));

                handleCollisions = true;
            }

            if (!handleCollisions && jobPriAttrKey != null && attrs.containsKey(jobPriAttrKey))
                handleCollisions = true;

            if (handleCollisions)
                handleCollisions();
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * @param sesId Task session ID.
     * @return Job statistics for the task. Mapping: Job status -> count of jobs.
     */
    public Map<ComputeJobStatusEnum, Long> jobStatuses(IgniteUuid sesId) {
        return Stream.concat(
                activeJobs.values().stream(),
                jobAlwaysActivate ? cancelledJobs.values().stream() :
                    Stream.concat(passiveJobs.values().stream(), cancelledJobs.values().stream())
            )
            .filter(idMatch(sesId, null))
            .collect(groupingBy(GridJobWorker::status, counting()));
    }

    /**
     * @param sesId Task session ID.
     * @param jobId Job ID.
     * @return ID workers predicate.
     */
    private Predicate<GridJobWorker> idMatch(@Nullable IgniteUuid sesId, @Nullable IgniteUuid jobId) {
        assert sesId != null || jobId != null;

        if (sesId == null)
            return w -> jobId.equals(w.getJobId());
        else if (jobId == null)
            return w -> sesId.equals(w.getSession().getId());
        else
            return w -> sesId.equals(w.getSession().getId()) && jobId.equals(w.getJobId());
    }

    /**
     * @return Interruption timeout of {@link GridJobWorker workers} (in millis) after {@link GridWorker#cancel cancel} is called.
     */
    public long computeJobWorkerInterruptTimeout() {
        return computeJobWorkerInterruptTimeout.getOrDefault(ctx.config().getFailureDetectionTimeout());
    }
}
