package cmd;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

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
    public Integer call() throws Exception {
        System.out.printf("Monitoring repository: %s%n", repository);
        System.out.println("Using token: " + token.substring(0, 4) + "****");

        // TODO: Implement monitoring logic here

        return 0;
    }
}
