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
 * Exercise 2: CP subsystem with ALL nodes isolated from each other.
 *
 * Expected behavior:
 * - Before partition: all nodes consistent, reads/writes work everywhere
 * - During partition: NO node can read/write (no node has a quorum)
 * - After heal: all nodes converge, value unchanged from before partition
 */
public class Ex2CPTest {

    private static final String MEMBER_1 = "127.0.0.1:5821";
    private static final String MEMBER_2 = "127.0.0.1:5822";
    private static final String MEMBER_3 = "127.0.0.1:5823";

    private List<HazelcastInstance> nodes;

    private static Config createConfig(String member) {
        Config config = new ClasspathXmlConfig("hazelcast_ex2.xml");
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
        nodes.add(newHazelcastInstance(createConfig(MEMBER_1), "ex2test-node1", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_2), "ex2test-node2", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_3), "ex2test-node3", new FirewallingNodeContext()));
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
    public void testCPWithAllNodesIsolated() throws Exception {
        // --- Before partition: all nodes see the same value ---
        IAtomicLong ref1 = nodes.get(0).getCPSubsystem().getAtomicLong("ex2test");
        IAtomicLong ref2 = nodes.get(1).getCPSubsystem().getAtomicLong("ex2test");
        IAtomicLong ref3 = nodes.get(2).getCPSubsystem().getAtomicLong("ex2test");

        assertEquals("Initial value should be 0", 0, ref1.get());

        // Increment from node 1
        ref1.addAndGet(1);
        assertEquals("All nodes should see 1", 1, ref2.get());
        assertEquals("All nodes should see 1", 1, ref3.get());

        // --- Partition: isolate ALL nodes from each other ---
        SplitBrainTestSupport.blockCommunicationBetween(nodes.get(0), nodes.get(1));
        SplitBrainTestSupport.blockCommunicationBetween(nodes.get(0), nodes.get(2));
        SplitBrainTestSupport.blockCommunicationBetween(nodes.get(1), nodes.get(2));

        // Give the cluster time to detect the partition
        Thread.sleep(5000);

        // No node should be able to perform CP operations (no quorum anywhere)
        ExecutorService executor = Executors.newFixedThreadPool(3);

        Future<Long> get1 = executor.submit(() -> ref1.get());
        Future<Long> get2 = executor.submit(() -> ref2.get());
        Future<Long> get3 = executor.submit(() -> ref3.get());

        assertOperationTimesOut("Node 1 read should time out (no quorum)", get1);
        assertOperationTimesOut("Node 2 read should time out (no quorum)", get2);
        assertOperationTimesOut("Node 3 read should time out (no quorum)", get3);

        // Writes should also time out on all nodes
        Future<Long> add1 = executor.submit(() -> ref1.addAndGet(1));
        assertOperationTimesOut("Node 1 write should time out (no quorum)", add1);

        executor.shutdownNow();

        // --- Heal the partition ---
        SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(0), nodes.get(1));
        SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(0), nodes.get(2));
        SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(1), nodes.get(2));

        // Wait for cluster to re-converge
        Thread.sleep(15000);

        // After healing, all nodes should agree on the pre-partition value
        long v1 = ref1.get();
        long v2 = ref2.get();
        long v3 = ref3.get();

        assertEquals("After heal, all nodes should agree", v1, v2);
        assertEquals("After heal, all nodes should agree", v1, v3);
        assertEquals("Value should be 1 (only the pre-partition increment succeeded)", 1, v1);
    }

    private void assertOperationTimesOut(String message, Future<Long> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
            fail(message);
        } catch (TimeoutException expected) {
            future.cancel(true);
        } catch (ExecutionException | InterruptedException e) {
            // Also acceptable: the operation may throw due to no leader
        }
    }
}
