package cap;

import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.IAtomicLong;
import com.hazelcast.instance.FirewallingNodeContext;
import com.hazelcast.test.SplitBrainTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.hazelcast.instance.impl.HazelcastInstanceFactory.newHazelcastInstance;
import static org.junit.Assert.*;

/**
 * Exercise 1: CP subsystem with 1 node isolated from 2 others.
 *
 * Expected behavior:
 * - Before partition: all nodes consistent, reads/writes work everywhere
 * - During partition: majority side (nodes 1,2) can read/write;
 *   isolated node (3) cannot (operations time out — no quorum)
 * - After heal: all nodes converge to the same value
 */
public class Ex1CPTest {

    private static final String MEMBER_1 = "127.0.0.1:5811";
    private static final String MEMBER_2 = "127.0.0.1:5812";
    private static final String MEMBER_3 = "127.0.0.1:5813";

    private List<HazelcastInstance> nodes;

    private static Config createConfig(String member) {
        Config config = new ClasspathXmlConfig("hazelcast_ex1.xml");
        NetworkConfig network = config.getNetworkConfig();
        network.setPortAutoIncrement(false);
        network.setPort(Integer.parseInt(member.split(":")[1]));

        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(List.of(MEMBER_1, MEMBER_2, MEMBER_3));

        return config;
    }

    @Before
    public void setUp() {
        nodes = new ArrayList<>();
        nodes.add(newHazelcastInstance(createConfig(MEMBER_1), "ex1test-node1", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_2), "ex1test-node2", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_3), "ex1test-node3", new FirewallingNodeContext()));
    }

    @After
    public void tearDown() {
        for (HazelcastInstance node : nodes) {
            try {
                node.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @Test(timeout = 120_000)
    public void testCPWithOneNodeIsolated() throws Exception {
        // --- Before partition: all nodes see the same value ---
        IAtomicLong ref1 = nodes.get(0).getCPSubsystem().getAtomicLong("ex1test");
        IAtomicLong ref2 = nodes.get(1).getCPSubsystem().getAtomicLong("ex1test");
        IAtomicLong ref3 = nodes.get(2).getCPSubsystem().getAtomicLong("ex1test");

        assertEquals("Initial value should be 0", 0, ref1.get());
        assertEquals("All nodes should see 0", 0, ref2.get());
        assertEquals("All nodes should see 0", 0, ref3.get());

        // Increment from node 1
        ref1.addAndGet(1);

        // All nodes should see the updated value (strong consistency)
        assertEquals("Node 1 should see 1", 1, ref1.get());
        assertEquals("Node 2 should see 1", 1, ref2.get());
        assertEquals("Node 3 should see 1", 1, ref3.get());

        // --- Partition: isolate node 3 from nodes 1 and 2 ---
        HazelcastInstance nodeToIsolate = nodes.get(2);
        for (int i = 0; i < 2; i++) {
            SplitBrainTestSupport.blockCommunicationBetween(nodes.get(i), nodeToIsolate);
        }

        // Give the cluster time to detect the partition
        Thread.sleep(5000);

        // Majority side (nodes 1, 2) should still work
        long valAfterAdd = ref1.addAndGet(1);
        assertEquals("Majority side can still increment", 2, valAfterAdd);
        assertEquals("Node 2 sees the increment", 2, ref2.get());

        // Isolated node (node 3) should NOT be able to read (times out / blocks)
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Long> isolatedGet = executor.submit(() -> ref3.get());
        try {
            isolatedGet.get(10, TimeUnit.SECONDS);
            fail("Isolated node should not be able to complete a CP read (no quorum)");
        } catch (TimeoutException expected) {
            // This is the expected behavior: CP read on isolated node times out
            isolatedGet.cancel(true);
        }
        executor.shutdownNow();

        // --- Heal the partition ---
        for (int i = 0; i < 2; i++) {
            SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(i), nodeToIsolate);
        }

        // Wait for cluster to re-converge
        Thread.sleep(10000);

        // After healing, all nodes should see the same value
        long v1 = ref1.get();
        long v2 = ref2.get();
        long v3 = ref3.get();

        assertEquals("After heal, all nodes should agree", v1, v2);
        assertEquals("After heal, all nodes should agree", v1, v3);
        assertEquals("Value should be 2 (two increments on majority side)", 2, v1);
    }
}
