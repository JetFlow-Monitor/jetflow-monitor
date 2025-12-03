package cmd;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import services.MonitorService;

import java.util.concurrent.Callable;

@Command(
        name = "monitor",
        description = "Monitors GitHub workflow runs for a repository in real-time."
)
public class MonitorCmd implements Callable<Integer> {

    @Parameters(index = "0", description = "Repository in format owner/repo")
    private String repository;

    @Parameters(index = "1", description = "GitHub Personal Access Token")
    private String token;

    @Override
    public Integer call() {
        System.out.printf("Monitoring repository: %s%n", repository);
        System.out.println("Using token: " + "****" + token.substring(token.length() - 4) + "\n");

        try {
            MonitorService monitorService = new MonitorService(repository, token);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down monitoring service...");
                monitorService.stop();
            }));

            int intervalSeconds = 3; // Polling interval
            monitorService.start(intervalSeconds);

            System.out.println("✓ Connected to GitHub.");
            System.out.println("✓ Initial state snapshot taken.");
            System.out.println("Waiting for new workflow events... (Trigger a run to test)");

            Thread.currentThread().join(); // Keep the main thread alive
            return 0;
        } catch (IllegalArgumentException e) {
            // Specific error for Token/Repo issues
            System.err.println("Configuration Error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            // Generic error
            System.err.println("Fatal Error:" + e.getMessage());
            return 1;
        }
    }
}
