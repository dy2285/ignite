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

package org.apache.ignite.internal.processors.cache.transactions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.ignite.IgniteCacheRestartingException;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheInterceptor;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.internal.cluster.ClusterTopologyServerNotFoundException;
import org.apache.ignite.internal.processors.cache.CacheInvalidStateException;
import org.apache.ignite.internal.processors.cache.CacheStoppedException;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTopologyFuture;
import org.apache.ignite.internal.processors.cache.store.CacheStoreManager;
import org.apache.ignite.internal.util.GridIntList;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_ASYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.PRIMARY_SYNC;

/**
 *
 */
public class IgniteTxStateImpl extends IgniteTxLocalStateAdapter {
    /** Active cache IDs. */
    private final GridIntList activeCacheIds = new GridIntList();

    /** Per-transaction read map. */
    @GridToStringExclude
    private Map<IgniteTxKey, IgniteTxEntry> txMap;

    /** Read view on transaction map. */
    @GridToStringExclude
    private IgniteTxMap readView;

    /** Write view on transaction map. */
    @GridToStringExclude
    private IgniteTxMap writeView;

    /** */
    @GridToStringInclude
    private Boolean recovery;

    /** {@inheritDoc} */
    @Override public boolean implicitSingle() {
        return false;
    }

    /** {@inheritDoc} */
    @Nullable @Override public Integer firstCacheId() {
        return activeCacheIds.isEmpty() ? null : activeCacheIds.get(0);
    }

    /** {@inheritDoc} */
    @Nullable @Override public GridIntList cacheIds() {
        return activeCacheIds;
    }

    /** {@inheritDoc} */
    @Override public void unwindEvicts(GridCacheSharedContext cctx) {
        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            GridCacheContext ctx = cctx.cacheContext(cacheId);

            if (ctx != null)
                CU.unwindEvicts(ctx);
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public GridCacheContext singleCacheContext(GridCacheSharedContext cctx) {
        if (activeCacheIds.size() == 1) {
            int cacheId = activeCacheIds.get(0);

            return cctx.cacheContext(cacheId);
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public void awaitLastFuture(GridCacheSharedContext cctx) {
        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            if (cctx.cacheContext(cacheId) == null)
                throw new IgniteException("Cache is stopped, id=" + cacheId);

            cctx.cacheContext(cacheId).cache().awaitLastFut();
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteCheckedException validateTopology(
        GridCacheSharedContext cctx,
        boolean read,
        GridDhtTopologyFuture topFut
    ) {
        Map<Integer, Set<KeyCacheObject>> keysByCacheId = new HashMap<>();

        for (IgniteTxKey key : txMap.keySet()) {
            Set<KeyCacheObject> set = keysByCacheId.computeIfAbsent(key.cacheId(), k -> new HashSet<>());

            set.add(key.key());
        }

        for (Map.Entry<Integer, Set<KeyCacheObject>> e : keysByCacheId.entrySet()) {
            int cacheId = e.getKey();

            GridCacheContext ctx = cctx.cacheContext(cacheId);

            assert ctx != null : cacheId;

            CacheInvalidStateException err = topFut.validateCache(ctx, recovery(), read, null, e.getValue());

            if (err != null)
                return err;
        }

        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            GridCacheContext<?, ?> cacheCtx = cctx.cacheContext(cacheId);

            if (CU.affinityNodes(cacheCtx, topFut.topologyVersion()).isEmpty()) {
                return new ClusterTopologyServerNotFoundException("Failed to map keys for cache (all " +
                    "partition nodes left the grid): " + cacheCtx.name());
            }
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public boolean recovery() {
        return recovery != null && recovery;
    }

    /** {@inheritDoc} */
    @Override public CacheWriteSynchronizationMode syncMode(GridCacheSharedContext cctx) {
        CacheWriteSynchronizationMode syncMode = CacheWriteSynchronizationMode.FULL_ASYNC;

        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            CacheWriteSynchronizationMode cacheSyncMode =
                cctx.cacheContext(cacheId).config().getWriteSynchronizationMode();

            switch (cacheSyncMode) {
                case FULL_SYNC:
                    return FULL_SYNC;

                case PRIMARY_SYNC: {
                    if (syncMode == FULL_ASYNC)
                        syncMode = PRIMARY_SYNC;

                    break;
                }

                case FULL_ASYNC:
                    break;
            }
        }

        return syncMode;
    }

    /** {@inheritDoc} */
    @Override public void addActiveCache(GridCacheContext cacheCtx, boolean recovery, IgniteTxAdapter tx)
        throws IgniteCheckedException {
        assert tx.local();

        GridCacheSharedContext cctx = cacheCtx.shared();

        int cacheId = cacheCtx.cacheId();

        if (this.recovery != null && this.recovery != recovery)
            throw new IgniteCheckedException("Failed to enlist an entry to existing transaction " +
                "(cannot transact between recovery and non-recovery caches).");

        this.recovery = recovery;

        // Check if we can enlist new cache to transaction.
        if (!activeCacheIds.contains(cacheId)) {
            String err = cctx.verifyTxCompatibility(tx, activeCacheIds, cacheCtx);

            if (err != null) {
                StringBuilder cacheNames = new StringBuilder();

                int idx = 0;

                for (int i = 0; i < activeCacheIds.size(); i++) {
                    int activeCacheId = activeCacheIds.get(i);

                    cacheNames.append(cctx.cacheContext(activeCacheId).name());

                    if (idx++ < activeCacheIds.size() - 1)
                        cacheNames.append(", ");
                }

                throw new IgniteCheckedException("Failed to enlist new cache to existing transaction (" +
                    err +
                    ") [activeCaches=[" + cacheNames + "]" +
                    ", cacheName=" + cacheCtx.name() +
                    ", cacheSystem=" + cacheCtx.systemTx() +
                    ", txSystem=" + tx.system() + ']');
            }
            else
                activeCacheIds.add(cacheId);

            if (activeCacheIds.size() == 1)
                tx.activeCachesDeploymentEnabled(cacheCtx.deploymentEnabled());
        }
    }

    /** {@inheritDoc} */
    @Override public GridDhtTopologyFuture topologyReadLock(GridCacheSharedContext cctx, GridFutureAdapter<?> fut) {
        if (activeCacheIds.isEmpty())
            return cctx.exchange().lastTopologyFuture();

        GridCacheContext<?, ?> ctx = null;

        Map<Integer, GridCacheContext> cacheCtxs = U.newHashMap(activeCacheIds.size());

        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            GridCacheContext<?, ?> cacheCtx = cctx.cacheContext(cacheId);

            if (ctx == null)
                ctx = cacheCtx;

            cacheCtxs.putIfAbsent(cacheCtx.cacheId(), cacheCtx);
        }

        if (ctx == null)
            return cctx.exchange().lastTopologyFuture();

        ctx.topology().readLock();

        for (Map.Entry<Integer, GridCacheContext> e : cacheCtxs.entrySet()) {
            GridCacheContext activeCacheCtx = e.getValue();

            if (activeCacheCtx.topology().stopping()) {
                fut.onDone(
                    cctx.cache().isCacheRestarting(activeCacheCtx.name()) ?
                        new IgniteCacheRestartingException(activeCacheCtx.name()) :
                        new CacheStoppedException(activeCacheCtx.name()));

                return null;
            }
        }

        return ctx.topology().topologyVersionFuture();
    }

    /** {@inheritDoc} */
    @Override public void topologyReadUnlock(GridCacheSharedContext cctx) {
        if (!activeCacheIds.isEmpty()) {
            int cacheId = activeCacheIds.get(0);

            GridCacheContext<?, ?> ctx = (GridCacheContext<?, ?>)cctx.cacheContext(cacheId);

            if (ctx != null)
                ctx.topology().readUnlock();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean storeWriteThrough(GridCacheSharedContext sctx) {
        if (!activeCacheIds.isEmpty()) {
            for (int i = 0; i < activeCacheIds.size(); i++) {
                int cacheId = activeCacheIds.get(i);

                GridCacheContext<?, ?> ctx = sctx.cacheContext(cacheId);

                // Ad-hoc solution to avoid the node crash.
                // Proper solution is expected at https://issues.apache.org/jira/browse/IGNITE-19529
                if (ctx == null) // Most likely, because of reading of an inconsitent state of a non-threadsafe collection.
                    continue;

                CacheStoreManager store = ctx.store();

                if (store.configured() && store.isWriteThrough())
                    return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean hasInterceptor(GridCacheSharedContext cctx) {
        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            CacheInterceptor interceptor = cctx.cacheContext(cacheId).config().getInterceptor();

            if (interceptor != null)
                return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public Collection<CacheStoreManager> stores(GridCacheSharedContext cctx) {
        GridIntList cacheIds = activeCacheIds;

        if (!cacheIds.isEmpty()) {
            Collection<CacheStoreManager> stores = new ArrayList<>(cacheIds.size());

            for (int i = 0; i < cacheIds.size(); i++) {
                int cacheId = cacheIds.get(i);

                CacheStoreManager store = cctx.cacheContext(cacheId).store();

                if (store.configured())
                    stores.add(store);
            }

            return stores;
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public void onTxEnd(GridCacheSharedContext cctx, IgniteInternalTx tx, boolean commit) {
        for (int i = 0; i < activeCacheIds.size(); i++) {
            int cacheId = activeCacheIds.get(i);

            GridCacheContext cacheCtx = cctx.cacheContext(cacheId);

            assert cacheCtx != null : "cacheCtx == null, cacheId=" + cacheId;

            onTxEnd(cacheCtx, tx, commit);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean init(int txSize) {
        if (txMap == null) {
            txMap = U.newLinkedHashMap(txSize > 0 ? txSize : 16);

            readView = new IgniteTxMap(txMap, CU.reads());
            writeView = new IgniteTxMap(txMap, CU.writes());

            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean initialized() {
        return txMap != null;
    }

    /** {@inheritDoc} */
    @Override public Collection<IgniteTxEntry> allEntries() {
        return txMap == null ? Collections.emptySet() : txMap.values();
    }

    /**
     * @return All entries. Returned collection is copy of internal collection.
     */
    public synchronized Collection<IgniteTxEntry> allEntriesCopy() {
        return txMap == null ? Collections.emptySet() : new ArrayList<>(txMap.values());
    }

    /** {@inheritDoc} */
    @Override public IgniteTxEntry entry(IgniteTxKey key) {
        return txMap == null ? null : txMap.get(key);
    }

    /** {@inheritDoc} */
    @Override public boolean hasWriteKey(IgniteTxKey key) {
        return writeView.containsKey(key);
    }

    /** {@inheritDoc} */
    @Override public Set<IgniteTxKey> readSet() {
        return txMap == null ? Collections.emptySet() : readView.keySet();
    }

    /** {@inheritDoc} */
    @Override public Set<IgniteTxKey> writeSet() {
        return txMap == null ? Collections.emptySet() : writeView.keySet();
    }

    /** {@inheritDoc} */
    @Override public Collection<IgniteTxEntry> writeEntries() {
        return writeView == null ? Collections.emptyList() : writeView.values();
    }

    /** {@inheritDoc} */
    @Override public Collection<IgniteTxEntry> readEntries() {
        return readView == null ? Collections.emptyList() : readView.values();
    }

    /** {@inheritDoc} */
    @Override public Map<IgniteTxKey, IgniteTxEntry> writeMap() {
        return writeView == null ? Collections.emptyMap() : writeView;
    }

    /** {@inheritDoc} */
    @Override public Map<IgniteTxKey, IgniteTxEntry> readMap() {
        return readView == null ? Collections.emptyMap() : readView;
    }

    /** {@inheritDoc} */
    @Override public boolean empty() {
        return txMap.isEmpty();
    }

    /** {@inheritDoc} */
    @Override public synchronized void addEntry(IgniteTxEntry entry) {
        txMap.put(entry.txKey(), entry);
    }

    /** {@inheritDoc} */
    @Override public synchronized void removeEntry(IgniteTxKey key) {
        txMap.remove(key);
    }

    /** {@inheritDoc} */
    @Override public void seal() {
        if (readView != null)
            readView.seal();

        if (writeView != null)
            writeView.seal();
    }

    /** {@inheritDoc} */
    @Override public IgniteTxEntry singleWrite() {
        return writeView != null && writeView.size() == 1 ? F.firstValue(writeView) : null;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(IgniteTxStateImpl.class, this, "txMap", allEntriesCopy());
    }
}
