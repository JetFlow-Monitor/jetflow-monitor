import cmd.MonitorCmd;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "jetflow", mixinStandardHelpOptions = true,
        version = "jetflow 1.0",
        description = "JetFlow CLI tool",
        subcommands = {
            MonitorCmd.class
        })
public class JetFlowCLI implements Runnable {
    public void run() {
        System.out.println("Use a subcommand. Try --help.");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JetFlowCLI()).execute(args);
        System.exit(exitCode);
    }
}
