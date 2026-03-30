package cap;

import com.hazelcast.core.HazelcastInstance;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Shared interactive CLI for CAP demo exercises.
 * Provides a clean command-line interface with:
 * - Partition status in prompt
 * - Operation timeouts (prevents indefinite blocking during CP partitions)
 * - Colored, formatted output
 * - Help command
 */
public abstract class ClusterCLI {

    // ANSI colors
    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED    = "\033[31m";
    private static final String CYAN   = "\033[36m";
    private static final String DIM    = "\033[2m";

    private static final int TIMEOUT_SECONDS = 10;

    protected final List<HazelcastInstance> nodes;
    private final String exerciseName;
    private final String dataStructure;
    private final String guaranteeType;
    private boolean partitioned = false;
    private volatile boolean shutdownDone = false;

    protected ClusterCLI(List<HazelcastInstance> nodes, String exerciseName,
                         String dataStructure, String guaranteeType) {
        this.nodes = nodes;
        this.exerciseName = exerciseName;
        this.dataStructure = dataStructure;
        this.guaranteeType = guaranteeType;
    }

    /** Perform a get operation on the given node index. */
    protected abstract long doGet(int nodeIndex) throws Exception;

    /** Perform an add (increment by 1) on the given node index, return new value. */
    protected abstract long doAdd(int nodeIndex) throws Exception;

    /** Apply the network partition. */
    protected abstract void doPartition();

    /** Heal the network partition. */
    protected abstract void doHeal();

    /** Return a description of what the partition does. */
    protected abstract String getPartitionDescription();

    public void run() {
        printBanner();
        printHelp();

        // Ensure clean shutdown on Ctrl+C so ports are released
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownCluster));

        ExecutorService executor = Executors.newCachedThreadPool();

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();

            while (true) {
                String input;
                try {
                    input = reader.readLine(getPromptString()).trim();
                } catch (UserInterruptException e) {
                    return;
                } catch (EndOfFileException e) {
                    break;
                }
                if (input.isEmpty()) continue;

                switch (input.toLowerCase()) {
                    case "exit":
                    case "quit":
                    case "q":
                        return;
                    case "help":
                    case "h":
                    case "?":
                        printHelp();
                        break;
                    case "status":
                    case "s":
                        printStatus();
                        break;
                    case "partition":
                    case "p":
                        if (partitioned) {
                            printWarn("Already partitioned. Use 'heal' first.");
                        } else {
                            doPartition();
                            partitioned = true;
                            printOk(getPartitionDescription());
                        }
                        break;
                    case "heal":
                        if (!partitioned) {
                            printWarn("No active partition. Use 'partition' first.");
                        } else {
                            doHeal();
                            partitioned = false;
                            printOk("Partition healed. All nodes can communicate.");
                        }
                        break;
                    case "getall":
                    case "ga":
                        executeGetAll(executor);
                        break;
                    default:
                        if (input.startsWith("add:") || input.startsWith("a:")) {
                            executeAdd(input, executor);
                        } else if (input.startsWith("get:") || input.startsWith("g:")) {
                            executeGet(input, executor);
                        } else {
                            printError("Unknown command: '" + input + "'. Type 'help' for available commands.");
                        }
                        break;
                }
            }
        } catch (IOException e) {
            System.err.println("Terminal error: " + e.getMessage());
        } finally {
            executor.shutdownNow();
            shutdownCluster();
        }
    }

    private void executeGetAll(ExecutorService executor) {
        System.out.println(BOLD + "  Node  |  Value" + RESET);
        System.out.println("  ------+--------");

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    ExecutorService inner = Executors.newSingleThreadExecutor();
                    Future<Long> valueFuture = inner.submit(() -> doGet(idx));
                    long value = valueFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    inner.shutdownNow();
                    return String.format("  %s%-5d%s |  %s%d%s",
                            CYAN, idx + 1, RESET, GREEN, value, RESET);
                } catch (TimeoutException e) {
                    return String.format("  %s%-5d%s |  %s(timeout - no quorum)%s",
                            CYAN, idx + 1, RESET, RED, RESET);
                } catch (Exception e) {
                    return String.format("  %s%-5d%s |  %s(error: %s)%s",
                            CYAN, idx + 1, RESET, RED, extractMessage(e), RESET);
                }
            }));
        }

        for (Future<String> future : futures) {
            try {
                System.out.println(future.get(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS));
            } catch (Exception e) {
                System.out.println("  " + RED + "(failed)" + RESET);
            }
        }
    }

    private void executeGet(String input, ExecutorService executor) {
        int idx = parseNodeIndex(input);
        if (idx < 0) return;

        Future<Long> future = executor.submit(() -> doGet(idx));
        try {
            long value = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.printf("  Node %d -> %s%d%s%n", idx + 1, GREEN, value, RESET);
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.printf("  Node %d -> %s(timeout - no quorum)%s%n", idx + 1, RED, RESET);
        } catch (Exception e) {
            System.out.printf("  Node %d -> %s(error: %s)%s%n", idx + 1, RED, extractMessage(e), RESET);
        }
    }

    private void executeAdd(String input, ExecutorService executor) {
        int idx = parseNodeIndex(input);
        if (idx < 0) return;

        Future<Long> future = executor.submit(() -> doAdd(idx));
        try {
            long value = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.printf("  Node %d += 1 -> %s%d%s%n", idx + 1, GREEN, value, RESET);
        } catch (TimeoutException e) {
            future.cancel(true);
            System.out.printf("  Node %d += 1 -> %s(timeout - no quorum)%s%n", idx + 1, RED, RESET);
        } catch (Exception e) {
            System.out.printf("  Node %d += 1 -> %s(error: %s)%s%n", idx + 1, RED, extractMessage(e), RESET);
        }
    }

    private int parseNodeIndex(String input) {
        String[] parts = input.split(":");
        if (parts.length < 2) {
            printError("Usage: " + parts[0] + ":<nodeNumber>  (e.g. " + parts[0] + ":1)");
            return -1;
        }
        try {
            int idx = Integer.parseInt(parts[1].trim()) - 1;
            if (idx < 0 || idx >= nodes.size()) {
                printError("Node number must be between 1 and " + nodes.size());
                return -1;
            }
            return idx;
        } catch (NumberFormatException e) {
            printError("Invalid node number: '" + parts[1] + "'");
            return -1;
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println(BOLD + "========================================" + RESET);
        System.out.printf( BOLD + "  %s  (%s guarantees)%n" + RESET, exerciseName, guaranteeType);
        System.out.printf( DIM  + "  Data structure: %s%n" + RESET, dataStructure);
        System.out.printf( DIM  + "  Nodes: %d | Timeout: %ds%n" + RESET, nodes.size(), TIMEOUT_SECONDS);
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println();
    }

    private void printHelp() {
        System.out.println(BOLD + "  Commands:" + RESET);
        System.out.println("    add:N / a:N    Increment counter on node N");
        System.out.println("    get:N / g:N    Get counter value from node N");
        System.out.println("    getAll / ga     Get counter value from all nodes");
        System.out.println("    partition / p   Simulate network partition");
        System.out.println("    heal           Restore network connectivity");
        System.out.println("    status / s     Show cluster status");
        System.out.println("    help / h       Show this help");
        System.out.println("    exit / q       Quit");
        System.out.println();
    }

    private void printStatus() {
        String state = partitioned
                ? RED + "PARTITIONED" + RESET
                : GREEN + "HEALTHY" + RESET;
        System.out.printf("  Cluster: %s | Nodes: %d | Type: %s%n", state, nodes.size(), guaranteeType);
    }

    private String getPromptString() {
        String status = partitioned
                ? RED + "SPLIT" + RESET
                : GREEN + "OK" + RESET;
        return String.format("%s[%s]%s> ", BOLD, status, RESET);
    }

    private synchronized void shutdownCluster() {
        if (shutdownDone) return;
        shutdownDone = true;
        System.out.println(DIM + "\nShutting down cluster..." + RESET);
        if (partitioned) {
            doHeal();
            partitioned = false;
        }
        nodes.forEach(node -> {
            try { node.shutdown(); } catch (Exception ignored) {}
        });
        System.out.println(DIM + "Done." + RESET);
    }

    private void printOk(String msg) {
        System.out.println("  " + GREEN + msg + RESET);
    }

    private void printWarn(String msg) {
        System.out.println("  " + YELLOW + msg + RESET);
    }

    private void printError(String msg) {
        System.out.println("  " + RED + msg + RESET);
    }

    private String extractMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        String msg = cause.getMessage();
        if (msg != null && msg.length() > 80) msg = msg.substring(0, 77) + "...";
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}
