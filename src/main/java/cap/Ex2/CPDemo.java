package cap.Ex2;

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
 * Exercise 2: CP subsystem - isolate ALL nodes from each other.
 * Demonstrates that with no quorum anywhere, no node can read or write.
 */
public class CPDemo extends ClusterCLI {

    private static final String MEMBER_1 = "127.0.0.1:5721";
    private static final String MEMBER_2 = "127.0.0.1:5722";
    private static final String MEMBER_3 = "127.0.0.1:5723";

    public CPDemo() {
        super(createNodes(), "Exercise 2: CP - All Nodes Isolated", "IAtomicLong (CP)", "CP");
    }

    private static List<HazelcastInstance> createNodes() {
        List<HazelcastInstance> nodes = new ArrayList<>();
        nodes.add(newHazelcastInstance(createConfig(MEMBER_1), "node1", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_2), "node2", new FirewallingNodeContext()));
        nodes.add(newHazelcastInstance(createConfig(MEMBER_3), "node3", new FirewallingNodeContext()));
        return nodes;
    }

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

    @Override
    protected long doGet(int nodeIndex) {
        return nodes.get(nodeIndex).getCPSubsystem().getAtomicLong("test").get();
    }

    @Override
    protected long doAdd(int nodeIndex) {
        return nodes.get(nodeIndex).getCPSubsystem().getAtomicLong("test").addAndGet(1);
    }

    @Override
    protected void doPartition() {
        SplitBrainTestSupport.blockCommunicationBetween(nodes.get(0), nodes.get(1));
        SplitBrainTestSupport.blockCommunicationBetween(nodes.get(0), nodes.get(2));
        SplitBrainTestSupport.blockCommunicationBetween(nodes.get(1), nodes.get(2));
    }

    @Override
    protected void doHeal() {
        SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(0), nodes.get(1));
        SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(0), nodes.get(2));
        SplitBrainTestSupport.unblockCommunicationBetween(nodes.get(1), nodes.get(2));
    }

    @Override
    protected String getPartitionDescription() {
        return "All nodes isolated from each other. (No quorum possible)";
    }

    public static void main(String[] args) {
        new CPDemo().run();
    }
}
