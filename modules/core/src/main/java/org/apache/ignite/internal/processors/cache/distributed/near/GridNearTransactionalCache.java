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

package org.apache.ignite.internal.processors.cache.distributed.near;

import java.io.Externalizable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cache.ReadRepairStrategy;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheOperationContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheEntryEx;
import org.apache.ignite.internal.processors.cache.GridCacheEntryRemovedException;
import org.apache.ignite.internal.processors.cache.GridCacheMvccCandidate;
import org.apache.ignite.internal.processors.cache.IgniteCacheExpiryPolicy;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.distributed.GridDistributedCacheEntry;
import org.apache.ignite.internal.processors.cache.distributed.GridDistributedUnlockRequest;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtCache;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtUnlockRequest;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxLocalEx;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.future.GridFinishedFuture;
import org.apache.ignite.internal.util.lang.IgnitePair;
import org.apache.ignite.internal.util.typedef.CI2;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.security.SecurityPermission;
import org.apache.ignite.transactions.TransactionIsolation;
import org.jetbrains.annotations.Nullable;

/**
 * Near cache for transactional cache.
 */
public class GridNearTransactionalCache<K, V> extends GridNearCacheAdapter<K, V> {
    /** */
    private static final long serialVersionUID = 0L;

    /** DHT cache. */
    private GridDhtCache<K, V> dht;

    /**
     * Empty constructor required for {@link Externalizable}.
     */
    public GridNearTransactionalCache() {
        // No-op.
    }

    /**
     * @param ctx Context.
     */
    public GridNearTransactionalCache(GridCacheContext<K, V> ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws IgniteCheckedException {
        super.onKernalStart();

        assert !ctx.isRecoveryMode() : "Registering message handlers in recovery mode [cacheName=" + name() + ']';

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearGetResponse.class,
            (CI2<UUID, GridNearGetResponse>)this::processGetResponse);

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearLockResponse.class,
            (CI2<UUID, GridNearLockResponse>)this::processLockResponse);
    }

    /**
     * @param dht DHT cache.
     */
    public void dht(GridDhtCache<K, V> dht) {
        this.dht = dht;
    }

    /** {@inheritDoc} */
    @Override public GridDhtCache<K, V> dht() {
        return dht;
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Map<K, V>> getAllAsync(
        @Nullable final Collection<? extends K> keys,
        boolean forcePrimary,
        boolean skipTx,
        String taskName,
        final boolean deserializeBinary,
        final boolean recovery,
        final ReadRepairStrategy readRepairStrategy,
        final boolean skipVals,
        final boolean needVer
    ) {
        ctx.checkSecurity(SecurityPermission.CACHE_READ);

        if (F.isEmpty(keys))
            return new GridFinishedFuture<>(Collections.emptyMap());

        warnIfUnordered(keys, BulkOperation.GET);

        GridNearTxLocal tx = ctx.tm().threadLocalTx(ctx);

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        final boolean skipStore = opCtx != null && opCtx.skipStore();

        if (tx != null && !tx.implicit() && !skipTx) {
            return asyncOp(tx, new AsyncOp<Map<K, V>>(keys) {
                @Override public IgniteInternalFuture<Map<K, V>> op(GridNearTxLocal tx, AffinityTopologyVersion readyTopVer) {
                    return tx.getAllAsync(ctx,
                        readyTopVer,
                        ctx.cacheKeysView(keys),
                        deserializeBinary,
                        skipVals,
                        false,
                        skipStore,
                        recovery,
                        readRepairStrategy,
                        needVer);
                }
            }, opCtx, /*retry*/false);
        }

        return loadAsync(null,
            ctx.cacheKeysView(keys),
            forcePrimary,
            taskName,
            deserializeBinary,
            recovery,
            skipVals ? null : opCtx != null ? opCtx.expiry() : null,
            skipVals,
            skipStore,
            needVer);
    }

    /**
     * @param tx Transaction.
     * @param keys Keys to load.
     * @param readThrough Read through flag.
     * @param forcePrimary Force primary flag.
     * @param deserializeBinary Deserialize binary flag.
     * @param expiryPlc Expiry policy.
     * @param skipVals Skip values flag.
     * @param needVer If {@code true} returns values as tuples containing value and version.
     * @return Future.
     */
    IgniteInternalFuture<Map<K, V>> txLoadAsync(GridNearTxLocal tx,
        AffinityTopologyVersion topVer,
        @Nullable Collection<KeyCacheObject> keys,
        boolean readThrough,
        boolean forcePrimary,
        boolean deserializeBinary,
        boolean recovery,
        @Nullable IgniteCacheExpiryPolicy expiryPlc,
        boolean skipVals,
        boolean needVer) {
        assert tx != null;

        GridNearGetFuture<K, V> fut = new GridNearGetFuture<>(ctx,
            keys,
            readThrough,
            forcePrimary,
            tx,
            tx.resolveTaskName(),
            deserializeBinary,
            expiryPlc,
            skipVals,
            needVer,
            /*keepCacheObjects*/true,
            recovery);

        // init() will register future for responses if it has remote mappings.
        fut.init(topVer);

        return fut;
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    public void clearLocks(UUID nodeId, GridDhtUnlockRequest req) {
        assert nodeId != null;

        GridCacheVersion obsoleteVer = nextVersion();

        List<KeyCacheObject> keys = req.nearKeys();

        if (keys != null) {
            AffinityTopologyVersion topVer = ctx.affinity().affinityTopologyVersion();

            for (KeyCacheObject key : keys) {
                while (true) {
                    GridDistributedCacheEntry entry = peekExx(key);

                    try {
                        if (entry != null) {
                            entry.doneRemote(
                                req.version(),
                                req.version(),
                                null,
                                req.committedVersions(),
                                req.rolledbackVersions(),
                                /*system invalidate*/false);

                            // Note that we don't reorder completed versions here,
                            // as there is no point to reorder relative to the version
                            // we are about to remove.
                            if (entry.removeLock(req.version())) {
                                if (log.isDebugEnabled())
                                    log.debug("Removed lock [lockId=" + req.version() + ", key=" + key + ']');

                                // Try to evict near entry dht-mapped locally.
                                evictNearEntry(entry, obsoleteVer, topVer);
                            }
                            else {
                                if (log.isDebugEnabled())
                                    log.debug("Received unlock request for unknown candidate " +
                                        "(added to cancelled locks set): " + req);
                            }

                            entry.touch();
                        }
                        else if (log.isDebugEnabled())
                            log.debug("Received unlock request for entry that could not be found: " + req);

                        break;
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        if (log.isDebugEnabled())
                            log.debug("Received remove lock request for removed entry (will retry) [entry=" + entry +
                                ", req=" + req + ']');
                    }
                }
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processLockResponse(UUID nodeId, GridNearLockResponse res) {
        assert nodeId != null;
        assert res != null;

        GridNearLockFuture fut = (GridNearLockFuture)ctx.mvcc().versionedFuture(res.version(),
            res.futureId());

        if (fut != null)
            fut.onResult(nodeId, res);
    }

    /** {@inheritDoc} */
    @Override protected IgniteInternalFuture<Boolean> lockAllAsync(
        Collection<KeyCacheObject> keys,
        long timeout,
        IgniteTxLocalEx tx,
        boolean isInvalidate,
        boolean isRead,
        boolean retval,
        TransactionIsolation isolation,
        long createTtl,
        long accessTtl
    ) {
        CacheOperationContext opCtx = ctx.operationContextPerCall();

        GridNearLockFuture fut = new GridNearLockFuture(ctx,
            keys,
            (GridNearTxLocal)tx,
            isRead,
            retval,
            timeout,
            createTtl,
            accessTtl,
            opCtx != null && opCtx.skipStore(),
            opCtx != null && opCtx.isKeepBinary(),
            opCtx != null && opCtx.recovery());

        fut.map();

        return fut;
    }

    /**
     * @param e Transaction entry.
     * @param topVer Topology version.
     * @return {@code True} if entry is locally mapped as a primary or back up node.
     */
    protected boolean isNearLocallyMapped(GridCacheEntryEx e, AffinityTopologyVersion topVer) {
        return ctx.affinity().partitionBelongs(ctx.localNode(), e.partition(), topVer);
    }

    /**
     *
     * @param e Entry to evict if it qualifies for eviction.
     * @param obsoleteVer Obsolete version.
     * @param topVer Topology version.
     * @return {@code True} if attempt was made to evict the entry.
     */
    protected boolean evictNearEntry(GridCacheEntryEx e, GridCacheVersion obsoleteVer, AffinityTopologyVersion topVer) {
        assert e != null;
        assert obsoleteVer != null;

        if (isNearLocallyMapped(e, topVer)) {
            if (log.isDebugEnabled())
                log.debug("Evicting dht-local entry from near cache [entry=" + e + ", tx=" + this + ']');

            return e.markObsolete(obsoleteVer);
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public void unlockAll(Collection<? extends K> keys) {
        if (keys.isEmpty())
            return;

        try {
            GridCacheVersion ver = null;

            int keyCnt = -1;

            Map<ClusterNode, GridNearUnlockRequest> map = null;

            Collection<KeyCacheObject> locKeys = new LinkedList<>();

            for (K key : keys) {
                while (true) {
                    KeyCacheObject cacheKey = ctx.toCacheKeyObject(key);

                    GridDistributedCacheEntry entry = peekExx(cacheKey);

                    if (entry == null)
                        break; // While.

                    try {
                        GridCacheMvccCandidate cand = entry.candidate(ctx.nodeId(), Thread.currentThread().getId());

                        AffinityTopologyVersion topVer = AffinityTopologyVersion.NONE;

                        if (cand != null) {
                            assert cand.nearLocal() : "Got non-near-local candidate in near cache: " + cand;

                            ver = cand.version();

                            if (map == null) {
                                Collection<ClusterNode> affNodes = CU.affinityNodes(ctx, cand.topologyVersion());

                                if (F.isEmpty(affNodes))
                                    return;

                                keyCnt = (int)Math.ceil((double)keys.size() / affNodes.size());

                                map = U.newHashMap(affNodes.size());
                            }

                            topVer = cand.topologyVersion();

                            // Send request to remove from remote nodes.
                            ClusterNode primary = ctx.affinity().primaryByKey(key, topVer);

                            if (primary == null) {
                                if (log.isDebugEnabled())
                                    log.debug("Failed to unlock key (all partition nodes left the grid).");

                                break;
                            }

                            GridNearUnlockRequest req = map.get(primary);

                            if (req == null) {
                                map.put(primary, req = new GridNearUnlockRequest(ctx.cacheId(), keyCnt,
                                    ctx.deploymentEnabled()));

                                req.version(ver);
                            }

                            // Remove candidate from local node first.
                            GridCacheMvccCandidate rmv = entry.removeLock();

                            if (rmv != null) {
                                if (!rmv.reentry()) {
                                    if (ver != null && !ver.equals(rmv.version()))
                                        throw new IgniteCheckedException("Failed to unlock (if keys were locked separately, " +
                                            "then they need to be unlocked separately): " + keys);

                                    if (!primary.isLocal()) {
                                        assert req != null;

                                        req.addKey(entry.key());
                                    }
                                    else
                                        locKeys.add(cacheKey);

                                    if (log.isDebugEnabled())
                                        log.debug("Removed lock (will distribute): " + rmv);
                                }
                                else if (log.isDebugEnabled())
                                    log.debug("Current thread still owns lock (or there are no other nodes)" +
                                        " [lock=" + rmv + ", curThreadId=" + Thread.currentThread().getId() + ']');
                            }
                        }

                        assert !topVer.equals(AffinityTopologyVersion.NONE) || cand == null;

                        entry.touch();

                        break;
                    }
                    catch (GridCacheEntryRemovedException ignore) {
                        if (log.isDebugEnabled())
                            log.debug("Attempted to unlock removed entry (will retry): " + entry);
                    }
                }
            }

            if (ver == null)
                return;

            for (Map.Entry<ClusterNode, GridNearUnlockRequest> mapping : map.entrySet()) {
                ClusterNode n = mapping.getKey();

                GridDistributedUnlockRequest req = mapping.getValue();

                if (n.isLocal())
                    dht.removeLocks(ctx.nodeId(), req.version(), locKeys, true);
                else if (!F.isEmpty(req.keys()))
                    // We don't wait for reply to this message.
                    ctx.io().send(n, req, ctx.ioPolicy());
            }
        }
        catch (IgniteCheckedException ex) {
            U.error(log, "Failed to unlock the lock for keys: " + keys, ex);
        }
    }

    /**
     * Removes locks regardless of whether they are owned or not for given
     * version and keys.
     *
     * @param ver Lock version.
     * @param keys Keys.
     */
    public void removeLocks(GridCacheVersion ver, Collection<KeyCacheObject> keys) {
        if (keys.isEmpty())
            return;

        try {
            int keyCnt = -1;

            Map<ClusterNode, GridNearUnlockRequest> map = null;

            for (KeyCacheObject key : keys) {
                // Send request to remove from remote nodes.
                GridNearUnlockRequest req = null;

                while (true) {
                    GridDistributedCacheEntry entry = peekExx(key);

                    try {
                        if (entry != null) {
                            GridCacheMvccCandidate cand = entry.candidate(ver);

                            if (cand != null) {
                                if (map == null) {
                                    Collection<ClusterNode> affNodes = CU.affinityNodes(ctx, cand.topologyVersion());

                                    if (F.isEmpty(affNodes))
                                        return;

                                    keyCnt = (int)Math.ceil((double)keys.size() / affNodes.size());

                                    map = U.newHashMap(affNodes.size());
                                }

                                ClusterNode primary = ctx.affinity().primaryByKey(key, cand.topologyVersion());

                                if (primary == null) {
                                    if (log.isDebugEnabled())
                                        log.debug("Failed to unlock key (all partition nodes left the grid).");

                                    break;
                                }

                                if (!primary.isLocal()) {
                                    req = map.get(primary);

                                    if (req == null) {
                                        map.put(primary, req = new GridNearUnlockRequest(ctx.cacheId(), keyCnt,
                                            ctx.deploymentEnabled()));

                                        req.version(ver);
                                    }
                                }

                                // Remove candidate from local node first.
                                if (entry.removeLock(cand.version())) {
                                    if (primary.isLocal()) {
                                        dht.removeLocks(primary.id(), ver, F.asList(key), true);

                                        assert req == null;

                                        continue;
                                    }

                                    req.addKey(entry.key());
                                }
                            }
                        }

                        break;
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        if (log.isDebugEnabled())
                            log.debug("Attempted to remove lock from removed entry (will retry) [rmvVer=" +
                                ver + ", entry=" + entry + ']');
                    }
                }
            }

            if (map == null || map.isEmpty())
                return;

            IgnitePair<Collection<GridCacheVersion>> versPair = ctx.tm().versions(ver);

            Collection<GridCacheVersion> committed = versPair.get1();
            Collection<GridCacheVersion> rolledback = versPair.get2();

            for (Map.Entry<ClusterNode, GridNearUnlockRequest> mapping : map.entrySet()) {
                ClusterNode n = mapping.getKey();

                GridDistributedUnlockRequest req = mapping.getValue();

                if (!F.isEmpty(req.keys())) {
                    req.completedVersions(committed, rolledback);

                    // We don't wait for reply to this message.
                    ctx.io().send(n, req, ctx.ioPolicy());
                }
            }
        }
        catch (IgniteCheckedException ex) {
            U.error(log, "Failed to unlock the lock for keys: " + keys, ex);
        }
    }

    /** {@inheritDoc} */
    @Override public void onDeferredDelete(GridCacheEntryEx entry, GridCacheVersion ver) {
        assert false : "Should not be called";
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridNearTransactionalCache.class, this);
    }
}
