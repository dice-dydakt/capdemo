package cap.Ex3;

import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.crdt.pncounter.PNCounter;
import com.hazelcast.instance.FirewallingNodeContext;
import com.hazelcast.test.SplitBrainTestSupport;

import java.util.List;
import java.util.Scanner;

import static com.hazelcast.instance.impl.HazelcastInstanceFactory.newHazelcastInstance;

public class APDemo {
  private static final String MEMBER_1 = "127.0.0.1:5731";
  private static final String MEMBER_2 = "127.0.0.1:5732";
  private static final String MEMBER_3 = "127.0.0.1:5733";

  private final List<HazelcastInstance> nodes = List.of(
    newHazelcastInstance(createConfig(MEMBER_1), "node1", new FirewallingNodeContext()),
    newHazelcastInstance(createConfig(MEMBER_2), "node2", new FirewallingNodeContext()),
    newHazelcastInstance(createConfig(MEMBER_3), "node3", new FirewallingNodeContext())
  );

  private final HazelcastInstance nodeToIsolate = nodes.get(2);

  private static Config createConfig(String member) {
    Config config = new ClasspathXmlConfig("hazelcast_ex3.xml"); //Config();
    NetworkConfig network = config.getNetworkConfig();
    network.setPortAutoIncrement(false);
    network.setPort(Integer.parseInt(member.split(":")[1]));

    JoinConfig join = network.getJoin();
    join.getMulticastConfig().setEnabled(false);
    join.getTcpIpConfig().setEnabled(true).setMembers(List.of(MEMBER_1, MEMBER_2, MEMBER_3));

    return config;
  }

  final HazelcastInstance instance = Hazelcast.newHazelcastInstance();
  final PNCounter counter = instance.getPNCounter("counter");

  private void partition(HazelcastInstance instanceToIsolate) {
    nodes.stream()
      .filter(instance -> !instance.equals(instanceToIsolate))
      .forEach(instance -> {
        SplitBrainTestSupport.blockCommunicationBetween(instance, instanceToIsolate);
        System.out.println("Blocked " + instance.getName() + " and " + instanceToIsolate.getName());
      });
  }

  private void heal(HazelcastInstance isolatedInstance) {
    nodes.stream()
      .filter(instance -> !instance.equals(isolatedInstance))
      .forEach(instance -> {
        SplitBrainTestSupport.unblockCommunicationBetween(instance, isolatedInstance);
        System.out.println("Unblocked " + instance.getName() + " and " + isolatedInstance.getName());
      });
  }

  public void run() {
    Scanner scanner = new Scanner(System.in);
    while (true) {
      System.out.println("Enter:\n- 'partition' to simulate a network partition\n- 'heal' to restore\n- 'get/add:<nodeNumber>' to get/increase value of PNCounter from node\n- 'getAll' to get value of PNCounter on all nodes\n- 'exit' to quit");
      String input = scanner.nextLine();

      if ("exit".equalsIgnoreCase(input)) {
        break;
      } else if ("partition".equalsIgnoreCase(input)) {
        // For demonstration purposes, isolating node3 from node1 and node2.
        partition(nodeToIsolate);
        System.out.println("Network partition emulated. Node 3 is isolated from Node 1, and 2.");
      } else if ("heal".equalsIgnoreCase(input)) {
        // Resetting the configurations to heal the partition.
        heal(nodeToIsolate);
        System.out.println("Network partition healed. All nodes can communicate now.");
      } else if (input.startsWith("add")) {
        var instanceIdx = getInstanceIndex(input);
        new Thread(() -> {
          var pnCounter = getPNCounterReference(instanceIdx);
          System.out.printf("Add on node %d, value: %d%n", instanceIdx + 1, pnCounter.addAndGet(1));
        }).start();
      } else if (input.startsWith("getAll")) {
        for (int i=0; i<3; i++) {
          var instanceIdx = i;
          new Thread(() -> {
            var pnCounter = getPNCounterReference(instanceIdx);
            System.out.printf("Get on node %d, value %d%n", instanceIdx + 1, pnCounter.get());
          }).start();
        }
      } else if (input.startsWith("get")) {
        var instanceIdx = getInstanceIndex(input);
        new Thread(() -> {
          var pnCounter = getPNCounterReference(instanceIdx);
          System.out.printf("Get on node %d, value %d%n", instanceIdx + 1, pnCounter.get());
        }).start();
      }
    }

    nodes.forEach(HazelcastInstance::shutdown);
  }

  private int getInstanceIndex(String input) {
    return Integer.parseInt(input.split(":")[1]) - 1;
  }

  private PNCounter getPNCounterReference(int instanceIdx) {
    return nodes.get(instanceIdx).getPNCounter("test");
  }

  public static void main(String[] args) {
    new cap.Ex3.APDemo().run();
  }
}
