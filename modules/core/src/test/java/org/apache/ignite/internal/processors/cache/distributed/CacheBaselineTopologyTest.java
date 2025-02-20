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
 *
 */

package org.apache.ignite.internal.processors.cache.distributed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.AffinityFunction;
import org.apache.ignite.cache.affinity.AffinityFunctionContext;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.BaselineNode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.WALMode;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.cluster.DetachedClusterNode;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionFullMap;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionState;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionTopology;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;
import static org.apache.ignite.cache.PartitionLossPolicy.READ_ONLY_SAFE;

/**
 *
 */
public class CacheBaselineTopologyTest extends GridCommonAbstractTest {
    /** */
    private static final String CACHE_NAME = "cache";

    /** */
    private static final int NODE_COUNT = 4;

    /** */
    private boolean disableAutoActivation;

    /** */
    private Map<String, Object> userAttrs;

    /** */
    private static final String DATA_NODE = "dataNodeUserAttr";

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();

        cleanPersistenceDir();

        disableAutoActivation = false;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setConsistentId(igniteInstanceName);

        if (disableAutoActivation)
            cfg.setAutoActivationEnabled(false);

        cfg.setDataStorageConfiguration(
            new DataStorageConfiguration().setDefaultDataRegionConfiguration(
                new DataRegionConfiguration()
                    .setPersistenceEnabled(true)
                    .setMaxSize(100L * 1024 * 1024)
                    .setInitialSize(100L * 1024 * 1024)
            )
            .setDataRegionConfigurations(
                new DataRegionConfiguration()
                .setName("memory")
                .setPersistenceEnabled(false)
                .setMaxSize(100L * 1024 * 1024)
                .setInitialSize(100L * 1024 * 1024)
            )
            .setWalMode(WALMode.LOG_ONLY)
        );

        if (userAttrs != null)
            cfg.setUserAttributes(userAttrs);

        return cfg;
    }

    /**
     * Verifies that rebalance on cache with Node Filter happens when BaselineTopology changes.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testRebalanceForCacheWithNodeFilter() throws Exception {
        try {
            final int EMPTY_NODE_IDX = 2;

            userAttrs = U.newHashMap(1);
            userAttrs.put(DATA_NODE, true);

            startGrids(2);

            userAttrs.put(DATA_NODE, false);

            IgniteEx ignite = startGrid(2);

            ignite.cluster().baselineAutoAdjustEnabled(false);
            ignite.cluster().state(ClusterState.ACTIVE);

            awaitPartitionMapExchange();

            IgniteCache<Integer, Integer> cache =
                ignite.createCache(
                    new CacheConfiguration<Integer, Integer>()
                        .setName(CACHE_NAME)
                        .setCacheMode(PARTITIONED)
                        .setBackups(1)
                        .setPartitionLossPolicy(READ_ONLY_SAFE)
                        .setAffinity(new RendezvousAffinityFunction(32, null))
                        .setNodeFilter(new DataNodeFilter())
                );

            for (int k = 0; k < 10_000; k++)
                cache.put(k, k);

            Thread.sleep(500);

            printSizesDataNodes(NODE_COUNT - 1, EMPTY_NODE_IDX);

            userAttrs.put(DATA_NODE, true);

            startGrid(3);

            ignite.cluster().setBaselineTopology(ignite.cluster().topologyVersion());

            awaitPartitionMapExchange();

            Thread.sleep(500);

            printSizesDataNodes(NODE_COUNT, EMPTY_NODE_IDX);
        }
        finally {
            userAttrs = null;
        }
    }

    /** */
    private void printSizesDataNodes(int nodesCnt, int emptyNodeIdx) {
        for (int i = 0; i < nodesCnt; i++) {
            IgniteEx ig = grid(i);

            int locSize = ig.cache(CACHE_NAME).localSize(CachePeekMode.PRIMARY);

            if (i == emptyNodeIdx)
                assertEquals("Cache local size on "
                    + i
                    + " node is expected to be zero", 0, locSize);
            else
                assertTrue("Cache local size on "
                    + i
                    + " node is expected to be non zero", locSize > 0);
        }
    }

    /** */
    private static class DataNodeFilter implements IgnitePredicate<ClusterNode> {
        /** {@inheritDoc} */
        @Override public boolean apply(ClusterNode clusterNode) {
            return clusterNode.attribute(DATA_NODE);
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testTopologyChangesWithFixedBaseline() throws Exception {
        startGrids(NODE_COUNT);

        IgniteEx ignite = grid(0);

        ignite.cluster().baselineAutoAdjustEnabled(false);
        ignite.cluster().state(ClusterState.ACTIVE);

        awaitPartitionMapExchange();

        Map<ClusterNode, Ignite> nodes = new HashMap<>();

        for (int i = 0; i < NODE_COUNT; i++) {
            Ignite ig = grid(i);

            nodes.put(ig.cluster().localNode(), ig);
        }

        IgniteCache<Integer, Integer> cache =
            ignite.createCache(
                new CacheConfiguration<Integer, Integer>()
                    .setName(CACHE_NAME)
                    .setCacheMode(PARTITIONED)
                    .setBackups(1)
                    .setPartitionLossPolicy(READ_ONLY_SAFE)
            );

        int key = -1;

        for (int k = 0; k < 100_000; k++) {
            if (!ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(k).contains(ignite.localNode())) {
                key = k;
                break;
            }
        }

        assert key >= 0;

        int part = ignite.affinity(CACHE_NAME).partition(key);

        Collection<ClusterNode> initMapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping.size() == 2 : initMapping;

        ignite.cluster().setBaselineTopology(baselineNodes(nodes.keySet()));

        awaitPartitionMapExchange();

        cache.put(key, 1);

        Collection<ClusterNode> mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping.size() == mapping.size() : mapping;
        assert initMapping.containsAll(mapping) : mapping;

        IgniteEx newIgnite = startGrid(4);

        awaitPartitionMapExchange();

        mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping.size() == mapping.size() : mapping;
        assert initMapping.containsAll(mapping) : mapping;

        mapping = newIgnite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping.size() == mapping.size() : mapping;
        assert initMapping.containsAll(mapping) : mapping;

        Set<String> stoppedNodeNames = new HashSet<>();

        ClusterNode node = mapping.iterator().next();

        stoppedNodeNames.add(nodes.get(node).name());

        nodes.get(node).close();

        nodes.remove(node);

        awaitPartitionMapExchange(true, true, null);

        mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert mapping.size() == 1 : mapping;
        assert initMapping.containsAll(mapping);

        node = mapping.iterator().next();

        stoppedNodeNames.add(nodes.get(node).name());

        nodes.get(node).close();

        nodes.remove(node);

        awaitPartitionMapExchange();

        mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert mapping.isEmpty() : mapping;

        GridDhtPartitionTopology topology = ignite.cachex(CACHE_NAME).context().topology();

        assert topology.lostPartitions().contains(part);

        for (String nodeName : stoppedNodeNames) {
            startGrid(nodeName);
        }

        assert ignite.cluster().nodes().size() == NODE_COUNT + 1;

        mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping.size() == mapping.size() : mapping;

        for (ClusterNode n1 : initMapping) {
            boolean found = false;

            for (ClusterNode n2 : mapping) {
                if (n2.consistentId().equals(n1.consistentId())) {
                    found = true;

                    break;
                }
            }

            assert found;
        }

        ignite.resetLostPartitions(Collections.singleton(CACHE_NAME));

        cache.put(key, 2);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineTopologyChangesFromServer() throws Exception {
        testBaselineTopologyChanges(false);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineTopologyChangesFromClient() throws Exception {
        testBaselineTopologyChanges(true);
    }

    /**
     * @throws Exception if failed.
     */
    @Test
    public void testClusterActiveWhileBaselineChanging() throws Exception {
        startGrids(NODE_COUNT);

        IgniteEx ig = grid(0);

        ig.cluster().baselineAutoAdjustEnabled(false);
        ig.cluster().state(ClusterState.ACTIVE);

        assertTrue(ig.cluster().state().active());

        startGrid(NODE_COUNT);

        IgniteInternalFuture fut = GridTestUtils.runAsync(() -> {
            try {
                U.sleep(100);
            }
            catch (IgniteInterruptedCheckedException e) {
                e.printStackTrace();
            }
            ig.cluster().setBaselineTopology(NODE_COUNT + 1);
        });

        while (!fut.isDone()) {
            assertTrue(grid(0).cluster().state().active());
            assertTrue(grid(0).context().state().publicApiActiveState(false));
            assertTrue(grid(NODE_COUNT).cluster().state().active());
            assertTrue(grid(NODE_COUNT).context().state().publicApiActiveState(false));
        }

        assertNull(String.valueOf(fut.error()), fut.error());

        assertEquals(NODE_COUNT + 1, ig.cluster().currentBaselineTopology().size());
    }

    /**
     * @throws Exception If failed.
     */
    private void testBaselineTopologyChanges(boolean fromClient) throws Exception {
        startGrids(NODE_COUNT);

        IgniteEx ignite;

        if (fromClient)
            ignite = startClientGrid(NODE_COUNT + 10);
        else
            ignite = grid(0);

        ignite.cluster().baselineAutoAdjustEnabled(false);
        ignite.cluster().state(ClusterState.ACTIVE);

        awaitPartitionMapExchange();

        Map<ClusterNode, Ignite> nodes = new HashMap<>();

        for (int i = 0; i < NODE_COUNT; i++) {
            Ignite ig = grid(i);

            nodes.put(ig.cluster().localNode(), ig);
        }

        ignite.createCache(
            new CacheConfiguration<Integer, Integer>()
                .setName(CACHE_NAME)
                .setCacheMode(PARTITIONED)
                .setBackups(1)
                .setPartitionLossPolicy(READ_ONLY_SAFE)
        );

        manualCacheRebalancing(ignite, CACHE_NAME);

        int key = -1;

        for (int k = 0; k < 100_000; k++) {
            if (!ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(k).contains(ignite.localNode())) {
                key = k;
                break;
            }
        }

        assert key >= 0;

        Collection<ClusterNode> initMapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping.size() == 2 : initMapping;

        ignite.cluster().setBaselineTopology(baselineNodes(nodes.keySet()));

        Set<String> stoppedNodeNames = new HashSet<>();

        ClusterNode node = initMapping.iterator().next();

        stoppedNodeNames.add(nodes.get(node).name());

        nodes.get(node).close();

        nodes.remove(node);

        awaitPartitionMapExchange();

        Collection<ClusterNode> mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert mapping.size() == 1 : mapping;
        assert initMapping.containsAll(mapping);

        Set<ClusterNode> blt2 = new HashSet<>(ignite.cluster().nodes());

        ignite.cluster().setBaselineTopology(baselineNodes(
            blt2.stream().filter(n -> !n.isClient()).collect(Collectors.toSet())));

        awaitPartitionMapExchange();

        Collection<ClusterNode> initMapping2 = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping2.size() == 2 : initMapping2;

        Ignite newIgnite = startGrid(NODE_COUNT);

        awaitPartitionMapExchange();

        mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert mapping.size() == initMapping2.size() : mapping;
        assert mapping.containsAll(initMapping2);

        assert ignite.affinity(CACHE_NAME).primaryPartitions(newIgnite.cluster().localNode()).length == 0;

        Set<ClusterNode> blt3 = new HashSet<>(ignite.cluster().nodes());

        ignite.cluster().setBaselineTopology(baselineNodes(
            blt3.stream().filter(n -> !n.isClient()).collect(Collectors.toSet())));

        awaitPartitionMapExchange();

        Collection<ClusterNode> initMapping3 = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert initMapping3.size() == 2;

        assert ignite.affinity(CACHE_NAME).primaryPartitions(newIgnite.cluster().localNode()).length > 0;

        newIgnite = startGrid(NODE_COUNT + 1);

        awaitPartitionMapExchange();

        mapping = ignite.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert mapping.size() == initMapping3.size() : mapping;
        assert mapping.containsAll(initMapping3);

        assert ignite.affinity(CACHE_NAME).primaryPartitions(newIgnite.cluster().localNode()).length == 0;

        ignite.cluster().setBaselineTopology(null);

        awaitPartitionMapExchange();

        assert ignite.affinity(CACHE_NAME).primaryPartitions(newIgnite.cluster().localNode()).length > 0;
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testPrimaryLeft() throws Exception {
        startGrids(NODE_COUNT);

        IgniteEx ig = grid(0);

        ig.cluster().state(ClusterState.ACTIVE);

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache =
            ig.createCache(
                new CacheConfiguration<Integer, Integer>()
                    .setName(CACHE_NAME)
                    .setCacheMode(PARTITIONED)
                    .setBackups(1)
                    .setPartitionLossPolicy(READ_ONLY_SAFE)
                    .setReadFromBackup(true)
                    .setWriteSynchronizationMode(FULL_SYNC)
                    .setRebalanceDelay(-1)
            );

        int key = 1;

        List<ClusterNode> affNodes = (List<ClusterNode>)ig.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert affNodes.size() == 2;

        int primaryIdx = -1;

        IgniteEx primary = null;
        IgniteEx backup = null;

        manualCacheRebalancing(ig, CACHE_NAME);

        for (int i = 0; i < NODE_COUNT; i++) {
            if (grid(i).localNode().equals(affNodes.get(0))) {
                primaryIdx = i;
                primary = grid(i);
            }
            else if (grid(i).localNode().equals(affNodes.get(1)))
                backup = grid(i);
        }

        assert primary != null;
        assert backup != null;

        Integer val1 = 1;
        Integer val2 = 2;

        cache.put(key, val1);

        assertEquals(val1, primary.cache(CACHE_NAME).get(key));
        assertEquals(val1, backup.cache(CACHE_NAME).get(key));

        if (ig == primary) {
            ig = backup;

            cache = ig.cache(CACHE_NAME);
        }

        primary.close();

        backup.context().cache().context().exchange().affinityReadyFuture(new AffinityTopologyVersion(5, 0)).get();

        assertEquals(backup.localNode(), ig.affinity(CACHE_NAME).mapKeyToNode(key));

        cache.put(key, val2);

        assertEquals(val2, backup.cache(CACHE_NAME).get(key));

        primary = startGrid(primaryIdx);

        assertEquals(backup.localNode(), ig.affinity(CACHE_NAME).mapKeyToNode(key));

        manualCacheRebalancing(ig, CACHE_NAME);

        awaitPartitionMapExchange();

        assertEquals(primary.localNode(), ig.affinity(CACHE_NAME).mapKeyToNode(key));

        assertEquals(val2, primary.cache(CACHE_NAME).get(key));
        assertEquals(val2, backup.cache(CACHE_NAME).get(key));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testPrimaryLeftAndClusterRestart() throws Exception {
        startGrids(NODE_COUNT);

        IgniteEx ig = grid(0);

        ig.cluster().state(ClusterState.ACTIVE);

        IgniteCache<Integer, Integer> cache =
            ig.createCache(
                new CacheConfiguration<Integer, Integer>()
                    .setName(CACHE_NAME)
                    .setWriteSynchronizationMode(FULL_SYNC)
                    .setCacheMode(PARTITIONED)
                    .setBackups(1)
                    .setPartitionLossPolicy(READ_ONLY_SAFE)
                    .setReadFromBackup(true)
                    .setRebalanceDelay(-1)
            );

        int key = 1;

        List<ClusterNode> affNodes = (List<ClusterNode>)ig.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assert affNodes.size() == 2;

        int primaryIdx = -1;
        int backupIdx = -1;

        IgniteEx primary = null;
        IgniteEx backup = null;

        manualCacheRebalancing(ig, CACHE_NAME);

        for (int i = 0; i < NODE_COUNT; i++) {
            if (grid(i).localNode().equals(affNodes.get(0))) {
                primaryIdx = i;
                primary = grid(i);
            }
            else if (grid(i).localNode().equals(affNodes.get(1))) {
                backupIdx = i;
                backup = grid(i);
            }
        }

        assert primary != null;
        assert backup != null;

        Integer val1 = 1;
        Integer val2 = 2;

        cache.put(key, val1);

        assertEquals(val1, primary.cache(CACHE_NAME).get(key));
        assertEquals(val1, backup.cache(CACHE_NAME).get(key));

        if (ig == primary) {
            ig = backup;

            cache = ig.cache(CACHE_NAME);
        }

        stopGrid(primaryIdx, false);

        backup.context().cache().context().exchange().affinityReadyFuture(new AffinityTopologyVersion(5, 0)).get();

        assertEquals(backup.localNode(), ig.affinity(CACHE_NAME).mapKeyToNode(key));

        cache.put(key, val2);

        assertEquals(val2, backup.cache(CACHE_NAME).get(key));

        stopAllGrids(false);

        startGrids(NODE_COUNT);

        ig = grid(0);
        primary = grid(primaryIdx);
        backup = grid(backupIdx);

        boolean activated = GridTestUtils.waitForCondition(() -> {
            for (int i = 0; i < NODE_COUNT; i++) {
                if (!grid(i).cluster().state().active())
                    return false;
            }

            return true;
        }, 10_000);

        assert activated;

//        assertEquals(backup.localNode(), ig.affinity(CACHE_NAME).mapKeyToNode(key));

        assertEquals(val2, primary.cache(CACHE_NAME).get(key));
        assertEquals(val2, backup.cache(CACHE_NAME).get(key));

        manualCacheRebalancing(ig, CACHE_NAME);

        awaitPartitionMapExchange();

        affNodes = (List<ClusterNode>)ig.affinity(CACHE_NAME).mapKeyToPrimaryAndBackups(key);

        assertEquals(primary.localNode(), affNodes.get(0));
        assertEquals(backup.localNode(), affNodes.get(1));

        assertEquals(val2, primary.cache(CACHE_NAME).get(key));
        assertEquals(val2, backup.cache(CACHE_NAME).get(key));
    }

    /**
     * @throws Exception if failed.
     */
    @Test
    public void testMetadataUpdate() throws Exception {
        startGrids(5);

        Ignite ignite3 = grid(3);

        ignite3.cluster().state(ClusterState.ACTIVE);

        CacheConfiguration<Object, Object> repCacheCfg = new CacheConfiguration<>("replicated")
            .setCacheMode(CacheMode.REPLICATED)
            .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);

        IgniteCache<Object, Object> cache = ignite3.getOrCreateCache(repCacheCfg);

        stopGrid(0);
        stopGrid(1);
        stopGrid(2);
        stopGrid(4);

        for (int i = 0; i < 100; i++)
            cache.put(i, new TestValue(i));

        stopAllGrids();

        startGrids(5);

        GridTestUtils.waitForCondition(() -> grid(0).cluster().state().active(), getTestTimeout());

        for (int g = 0; g < 5; g++) {
            for (int i = 0; i < 100; i++)
                assertEquals(new TestValue(i), grid(g).cache("replicated").get(i));
        }
    }

    /**
     * @throws Exception if failed.
     */
    @Test
    public void testClusterRestoredOnRestart() throws Exception {
        startGrids(5);

        Ignite ignite3 = grid(3);

        ignite3.cluster().state(ClusterState.ACTIVE);

        stopGrid(0);

        CacheConfiguration<Object, Object> cacheConfiguration = new CacheConfiguration<>("unknown_cache");

        cacheConfiguration.setBackups(3);

        IgniteCache<Object, Object> cache0 = ignite3.getOrCreateCache(cacheConfiguration);

        for (int i = 0; i < 2048; i++)
            cache0.put(i, 0);

        awaitPartitionMapExchange();

        stopAllGrids();

        startGrid(1); //TODO https://issues.apache.org/jira/browse/IGNITE-8717 (replace with startGrids(5); //after)
        startGrid(0);
        startGrid(2);
        startGrid(3);
        startGrid(4);

        GridTestUtils.waitForCondition(() -> grid(0).cluster().state().active(), getTestTimeout());

        for (int g = 0; g < 5; g++) {
            for (int i = 0; i < 2048; i++)
                assertEquals("For key: " + i, 0, grid(g).cache("unknown_cache").get(i));
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testNonPersistentCachesDontIgnoreBaselineTopology() throws Exception {
        Ignite ig = startGrids(4);

        ig.cluster().state(ClusterState.ACTIVE);

        IgniteCache persistentCache = ig.createCache(CACHE_NAME);

        IgniteCache inMemoryCache = ig.createCache(
            new CacheConfiguration<>().setName(CACHE_NAME + 2).setDataRegionName("memory"));

        Ignite newNode = startGrid(4);

        awaitPartitionMapExchange();

        assertEquals(0, ig.affinity(persistentCache.getName()).allPartitions(newNode.cluster().localNode()).length);
        assertEquals(0, ig.affinity(inMemoryCache.getName()).allPartitions(newNode.cluster().localNode()).length);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testMapTxPrimaryNodes() throws Exception {
        checkMapTxNodes(true, false);
    }

    /**
     *
     * @throws Exception If failed.
     */
    @Test
    public void testMapTxBackupNodes() throws Exception {
        checkMapTxNodes(false, false);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testMapNearTxPrimaryNodes() throws Exception {
        checkMapTxNodes(true, true);
    }

    /**
     *
     * @throws Exception If failed.
     */
    @Test
    public void testMapNearTxBackupNodes() throws Exception {
        checkMapTxNodes(false, true);
    }

    /**
     * @param primary Whether non-baseline node is primary.
     * @param near Whether non-baseline nod is near node.
     * @throws Exception If failed.
     */
    public void checkMapTxNodes(boolean primary, boolean near) throws Exception {
        int bltNodesCnt = 3;

        Ignite ig = startGrids(bltNodesCnt);

        ig.cluster().state(ClusterState.ACTIVE);

        ig.createCache(new CacheConfiguration<>()
            .setName(CACHE_NAME)
            .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            .setBackups(2));

        ig.createCache(
            new CacheConfiguration<>()
                .setName(CACHE_NAME + 1)
                .setDataRegionName("memory")
                .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
                .setBackups(2)
        );

        Ignite nonBltIgnite = startGrid(bltNodesCnt);

        awaitPartitionMapExchange();

        ClusterNode nonBltNode = nonBltIgnite.cluster().localNode();

        Ignite nearIgnite = near ? nonBltIgnite : ig;

        IgniteCache<Integer, Integer> persistentCache = nearIgnite.cache(CACHE_NAME);

        IgniteCache<Integer, Integer> inMemoryCache = nearIgnite.cache(CACHE_NAME + 1);

        assertEquals(0, nearIgnite.affinity(persistentCache.getName()).allPartitions(nonBltNode).length);

        assertEquals(0, nearIgnite.affinity(inMemoryCache.getName()).allPartitions(nonBltNode).length);
    }

    /**
     * @throws Exception if failed.
     */
    @Test
    public void testAffinityAssignmentChangedAfterRestart() throws Exception {
        int parts = 32;

        final List<Integer> partMapping = new ArrayList<>();

        for (int p = 0; p < parts; p++)
            partMapping.add(p);

        final AffinityFunction affFunc = new TestAffinityFunction(new RendezvousAffinityFunction(false, parts));

        TestAffinityFunction.partsAffMapping = partMapping;

        String cacheName = CACHE_NAME + 2;

        startGrids(4);

        IgniteEx ig = grid(0);

        ig.cluster().state(ClusterState.ACTIVE);

        IgniteCache<Integer, Integer> cache = ig.createCache(
            new CacheConfiguration<Integer, Integer>()
                .setName(cacheName)
                .setCacheMode(PARTITIONED)
                .setBackups(1)
                .setPartitionLossPolicy(READ_ONLY_SAFE)
                .setReadFromBackup(true)
                .setWriteSynchronizationMode(FULL_SYNC)
                .setRebalanceDelay(-1)
                .setAffinity(affFunc));

        Map<Integer, String> keyToConsId = new HashMap<>();

        for (int k = 0; k < 1000; k++) {
            cache.put(k, k);

            keyToConsId.put(k, ig.affinity(cacheName).mapKeyToNode(k).consistentId().toString());
        }

        stopAllGrids();

        Collections.shuffle(TestAffinityFunction.partsAffMapping, new Random(1));

        /* There is a problem with handling simultaneous auto activation after restart and manual activation.
           To properly catch the moment when cluster activation has finished we temporary disable auto activation. */
        disableAutoActivation = true;

        startGrids(4);

        ig = grid(0);

        ig.cluster().state(ClusterState.ACTIVE);

        cache = ig.cache(cacheName);

        GridDhtPartitionFullMap partMap = ig.cachex(cacheName).context().topology().partitionMap(false);

        for (int i = 1; i < 4; i++) {
            IgniteEx ig0 = grid(i);

            for (int p = 0; p < 32; p++) {
                assertEqualsCollections(
                    ig.affinity(cacheName).mapPartitionToPrimaryAndBackups(p),
                    ig0.affinity(cacheName).mapPartitionToPrimaryAndBackups(p)
                );
            }
        }

        for (Map.Entry<Integer, String> e : keyToConsId.entrySet()) {
            int p = ig.affinity(cacheName).partition(e.getKey());

            assertEquals("p=" + p, GridDhtPartitionState.OWNING, partMap.get(ig.affinity(cacheName).mapKeyToNode(e.getKey()).id()).get(p));
        }

        for (int k = 0; k < 1000; k++)
            assertEquals("k=" + k, Integer.valueOf(k), cache.get(k));
    }

    /**
     * Verify that in case of setting baseline topology with offline node among others
     * {@link IgniteException} is thrown.
     *
     * @throws Exception If failed.
     */
    @Test
    @SuppressWarnings({"unchecked", "ThrowableNotThrown"})
    public void testSettingBaselineTopologyWithOfflineNode() throws Exception {
        Ignite ignite = startGrids(2);

        ignite.cluster().state(ClusterState.ACTIVE);

        ignite(0).createCache(defaultCacheConfiguration().setNodeFilter(
            (IgnitePredicate<ClusterNode>)node -> node.attribute("some-attr") != null));

        Collection<ClusterNode> nodes = new ArrayList<>(ignite.cluster().nodes());
        nodes.add(new DetachedClusterNode("non-existing-node-id", null));

        GridTestUtils.assertThrows(log, (Callable<Void>)() -> {
            ignite.cluster().setBaselineTopology(nodes);

            return null;
        }, IgniteException.class, "Check arguments. Node with consistent ID [non-existing-node-id] " +
            "not found in server nodes.");
    }

    /**
     * Verify that in case of setting baseline topology with offline node among others {@link IgniteException} is
     * thrown.
     *
     * @throws Exception If failed.
     */
    @Test
    @SuppressWarnings({"unchecked", "ThrowableNotThrown"})
    public void testSettingBaselineTopologyWithOfflineNodeFromOldTopology() throws Exception {
        Ignite ignite = startGrids(2);

        ignite.cluster().baselineAutoAdjustEnabled(false);

        ignite.cluster().state(ClusterState.ACTIVE);

        stopGrid(1);

        ignite.cluster().setBaselineTopology(ignite.cluster().topologyVersion());

        GridTestUtils.assertThrows(log, (Callable<Void>)() -> {
            ignite.cluster().setBaselineTopology(ignite.cluster().topologyVersion() - 1);

            return null;
        }, IgniteException.class, "Check arguments. Node with consistent ID " +
            "[distributed.CacheBaselineTopologyTest1] not found in server nodes.");
    }

    /** */
    private Collection<BaselineNode> baselineNodes(Collection<ClusterNode> clNodes) {
        Collection<BaselineNode> res = new ArrayList<>(clNodes.size());

        res.addAll(clNodes);

        return res;
    }

    /**
     *
     */
    private static class TestValue {
        /** */
        int f1;

        /** */
        int f2;

        /** */
        int f3;

        /** */
        int f4;

        /**
         * @param a Init value.
         */
        private TestValue(int a) {
            f1 = f2 = f3 = f4 = a;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (!(o instanceof TestValue))
                return false;

            TestValue other = (TestValue)o;

            return
                f1 == other.f1 &&
                f2 == other.f2 &&
                f3 == other.f3 &&
                f4 == other.f4;

        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int result = f1;

            result = 31 * result + f2;
            result = 31 * result + f3;
            result = 31 * result + f4;

            return result;
        }
    }

    /**
     *
     */
    private static class TestAffinityFunction implements AffinityFunction {
        /** */
        private final AffinityFunction delegate;

        /** */
        private static List<Integer> partsAffMapping;

        /** */
        public TestAffinityFunction(AffinityFunction delegate) {
            this.delegate = delegate;
        }

        /** {@inheritDoc} */
        @Override public void reset() {
            delegate.reset();
        }

        /** {@inheritDoc} */
        @Override public int partitions() {
            return delegate.partitions();
        }

        /** {@inheritDoc} */
        @Override public int partition(Object key) {
            return delegate.partition(key);
        }

        /** {@inheritDoc} */
        @Override public List<List<ClusterNode>> assignPartitions(AffinityFunctionContext affCtx) {
            List<List<ClusterNode>> res0 = delegate.assignPartitions(affCtx);

            List<List<ClusterNode>> res = new ArrayList<>(res0.size());

            for (int p = 0; p < res0.size(); p++)
                res.add(p, null);

            for (int p = 0; p < res0.size(); p++)
                res.set(partsAffMapping.get(p), res0.get(p));

            return res;
        }

        /** {@inheritDoc} */
        @Override public void removeNode(UUID nodeId) {
            delegate.removeNode(nodeId);
        }
    }
}
