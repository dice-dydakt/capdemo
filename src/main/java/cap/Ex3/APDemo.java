package cap.Ex3;

import cap.ClusterCLI;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.FirewallingNodeContext;
import com.hazelcast.test.SplitBrainTestSupport;

import java.util.ArrayList;
import java.util.List;

import static com.hazelcast.instance.impl.HazelcastInstanceFactory.newHazelcastInstance;

/**
 * Exercise 3: AP guarantees with PNCounter (CRDT).
 * Demonstrates that all nodes remain available during partition,
 * with eventual consistency after healing via CRDT merge.
 */
public class APDemo extends ClusterCLI {

    private static final String MEMBER_1 = "127.0.0.1:5731";
    private static final String MEMBER_2 = "127.0.0.1:5732";
    private static final String MEMBER_3 = "127.0.0.1:5733";

    public APDemo() {
        super(createNodes(), "Exercise 3: AP - PNCounter (CRDT)", "PNCounter (AP)", "AP");
    }

    private static List<HazelcastInstance> createNodes() {
        List<HazelcastInstance> nodes = new ArrayList<>();
        nodes.add(newHazelcastInstance(createConfig(MEMBER_1), "node1", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_2), "node2", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_3), "node3", new FirewallingNodeContext()));
        return nodes;
    }

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

    @Override
    protected long doGet(int nodeIndex) {
        return nodes.get(nodeIndex).getPNCounter("test").get();
    }

    @Override
    protected long doAdd(int nodeIndex) {
        return nodes.get(nodeIndex).getPNCounter("test").addAndGet(1);
    }

    @Override
    protected void doPartition() {
        HazelcastInstance isolated = nodes.get(2);
        for (int i = 0; i < 2; i++) {
            SplitBrainTestSupport.blockCommunicationBetween(nodes.get(i), isolated);
        }
    }

    @Override
    protected void doHeal() {
        HazelcastInstance isolated = nodes.get(2);
        for (int i = 0; i < 2; i++) {
            SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(i), isolated);
        }
    }

    @Override
    protected String getPartitionDescription() {
        return "Node 3 isolated from Nodes 1 and 2.";
    }

    public static void main(String[] args) {
        new APDemo().run();
    }
}
