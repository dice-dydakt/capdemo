import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import java.util.Scanner;
import java.util.List;

public class CapDemo {

    private static final String MEMBER_1 = "127.0.0.1:5701";
    private static final String MEMBER_2 = "127.0.0.1:5702";
    private static final String MEMBER_3 = "127.0.0.1:5703";
    private static final String MEMBER_4 = "127.0.0.1:5704";

    private static Config createConfig(String member) {
        Config config = new ClasspathXmlConfig("hazelcast.xml"); //Config();
        NetworkConfig network = config.getNetworkConfig();
        network.setPortAutoIncrement(false);
        network.setPort(Integer.parseInt(member.split(":")[1]));

        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(List.of(MEMBER_1, MEMBER_2, MEMBER_3, MEMBER_4));

        return config;
    }

    private static void emulatePartition(HazelcastInstance... instances) {
        for (HazelcastInstance instance : instances) {
            JoinConfig joinConfig = instance.getConfig().getNetworkConfig().getJoin();
            joinConfig.getTcpIpConfig().setMembers(List.of(instance.getCluster().getLocalMember().getAddress().toString()));
        }
    }

    public static void main(String[] args) {
        HazelcastInstance node1 = Hazelcast.newHazelcastInstance(createConfig(MEMBER_1));
        HazelcastInstance node2 = Hazelcast.newHazelcastInstance(createConfig(MEMBER_2));
        HazelcastInstance node3 = Hazelcast.newHazelcastInstance(createConfig(MEMBER_3));
        HazelcastInstance node4 = Hazelcast.newHazelcastInstance(createConfig(MEMBER_4));

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("Enter 'partition' to simulate a network partition, 'heal' to restore, or 'exit' to quit:");
            String input = scanner.nextLine();

            if ("exit".equalsIgnoreCase(input)) {
                break;
            } else if ("partition".equalsIgnoreCase(input)) {
                // For demonstration purposes, isolating node3 from node1 and node2.
                emulatePartition(node3);
                System.out.println("Network partition emulated. Node 3 is isolated from Node 1 and Node 2.");
            } else if ("heal".equalsIgnoreCase(input)) {
                // Resetting the configurations to heal the partition.
                JoinConfig joinConfigNode3 = node3.getConfig().getNetworkConfig().getJoin();
                joinConfigNode3.getTcpIpConfig().setMembers(List.of(MEMBER_1, MEMBER_2, MEMBER_3, MEMBER_4));
                System.out.println("Network partition healed. All nodes can communicate now.");
            }
        }

        node1.shutdown();
        node2.shutdown();
        node3.shutdown();
    }
}
