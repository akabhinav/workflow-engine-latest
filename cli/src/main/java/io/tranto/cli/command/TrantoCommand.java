package io.tranto.cli.command;

import picocli.CommandLine.Command;

/**
 * The root {@code tranto} command. Subcommands do the work; this prints help when invoked bare.
 * {@code run} executes a single flow; {@code server} starts a distributed node (executor/worker/
 * scheduler) on a shared JDBC database.
 */
@Command(
    name = "tranto",
    mixinStandardHelpOptions = true,
    version = "Tranto 0.1.0",
    description = "Tranto — declarative workflow orchestration.",
    subcommands = {RunCommand.class, ServerCommand.class}
)
public class TrantoCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Tranto — use a subcommand. Try:  tranto run <flow.yaml>   (or --help)");
    }
}
