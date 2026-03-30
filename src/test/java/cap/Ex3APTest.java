package cap;

import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.crdt.pncounter.PNCounter;
import com.hazelcast.instance.FirewallingNodeContext;
import com.hazelcast.test.SplitBrainTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.hazelcast.instance.impl.HazelcastInstanceFactory.newHazelcastInstance;
import static org.junit.Assert.*;

/**
 * Exercise 3: AP guarantees with PNCounter (CRDT).
 * One node is isolated from the other two.
 *
 * Expected behavior:
 * - Before partition: all nodes see the same value
 * - During partition: ALL nodes can still read/write (availability preserved)
 *   Values may diverge between the two sides of the partition
 * - After heal: CRDT merge — all nodes converge to the sum of all increments
 */
public class Ex3APTest {

    private static final String MEMBER_1 = "127.0.0.1:5831";
    private static final String MEMBER_2 = "127.0.0.1:5832";
    private static final String MEMBER_3 = "127.0.0.1:5833";

    private List<HazelcastInstance> nodes;

    private static Config createConfig(String member) {
        Config config = new ClasspathXmlConfig("hazelcast_ex3.xml");
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
        nodes.add(newHazelcastInstance(createConfig(MEMBER_1), "ex3test-node1", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_2), "ex3test-node2", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_3), "ex3test-node3", new FirewallingNodeContext()));
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
    public void testAPWithOneNodeIsolated() throws Exception {
        // --- Before partition: all nodes see the same value ---
        PNCounter counter1 = nodes.get(0).getPNCounter("ex3test");
        PNCounter counter2 = nodes.get(1).getPNCounter("ex3test");
        PNCounter counter3 = nodes.get(2).getPNCounter("ex3test");

        assertEquals("Initial value should be 0", 0, counter1.get());
        assertEquals("Initial value should be 0", 0, counter2.get());
        assertEquals("Initial value should be 0", 0, counter3.get());

        // Increment from node 1
        counter1.addAndGet(1);

        // Give time for CRDT replication
        Thread.sleep(2000);

        assertEquals("Node 1 should see 1", 1, counter1.get());
        assertEquals("Node 2 should see 1", 1, counter2.get());
        assertEquals("Node 3 should see 1", 1, counter3.get());

        // --- Partition: isolate node 3 from nodes 1 and 2 ---
        HazelcastInstance nodeToIsolate = nodes.get(2);
        for (int i = 0; i < 2; i++) {
            SplitBrainTestSupport.blockCommunicationBetween(nodes.get(i), nodeToIsolate);
        }

        Thread.sleep(5000);

        // AP: ALL nodes should still be able to read and write
        // Increment on the majority side (node 1)
        long val1 = counter1.addAndGet(1);
        assertTrue("Majority side can increment", val1 > 1);

        // Increment on the isolated side (node 3)
        long val3 = counter3.addAndGet(1);
        assertTrue("Isolated node can also increment (AP guarantee)", val3 > 1);

        Thread.sleep(2000);

        // Values may diverge between the two sides
        long majority = counter1.get();
        long isolated = counter3.get();
        // Both sides have incremented independently; they may not agree
        System.out.println("During partition - majority side: " + majority + ", isolated: " + isolated);

        // --- Heal the partition ---
        for (int i = 0; i < 2; i++) {
            SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(i), nodeToIsolate);
        }

        // Wait for CRDT merge to propagate
        Thread.sleep(10000);

        // After healing, CRDT merge: all increments are preserved
        // Total increments: 1 (before partition) + 1 (majority) + 1 (isolated) = 3
        long final1 = counter1.get();
        long final2 = counter2.get();
        long final3 = counter3.get();

        assertEquals("After heal, all nodes should converge", final1, final2);
        assertEquals("After heal, all nodes should converge", final1, final3);
        assertEquals("CRDT should preserve all increments (1+1+1=3)", 3, final1);
    }

    @Test(timeout = 120_000)
    public void testAPReadYourWrites() throws Exception {
        // PNCounter provides Read-Your-Writes session guarantee
        PNCounter counter1 = nodes.get(0).getPNCounter("ex3test-ryw");

        // After writing, the same node should always see its own write
        long written = counter1.addAndGet(5);
        assertEquals("Should see own write immediately", 5, written);

        long read = counter1.get();
        assertTrue("Read-Your-Writes: read should be >= written value", read >= 5);
    }

    @Test(timeout = 120_000)
    public void testAPMonotonicReads() throws Exception {
        // PNCounter provides Monotonic Reads session guarantee
        PNCounter counter1 = nodes.get(0).getPNCounter("ex3test-mr");

        counter1.addAndGet(1);
        long read1 = counter1.get();

        counter1.addAndGet(1);
        long read2 = counter1.get();

        counter1.addAndGet(1);
        long read3 = counter1.get();

        assertTrue("Monotonic reads: each read >= previous", read2 >= read1);
        assertTrue("Monotonic reads: each read >= previous", read3 >= read2);
    }
}
