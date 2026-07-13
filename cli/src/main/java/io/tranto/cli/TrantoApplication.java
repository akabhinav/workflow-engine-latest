package io.tranto.cli;

import io.tranto.cli.command.TrantoCommand;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;

/**
 * The Tranto entrypoint. A Spring Boot application (non-web) that hands the command line to
 * picocli. The picocli exit code becomes the process exit code.
 */
@SpringBootApplication
public class TrantoApplication implements CommandLineRunner, ExitCodeGenerator {

    private int exitCode;

    public static void main(final String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(TrantoApplication.class, args)));
    }

    @Override
    public void run(final String... args) {
        this.exitCode = new CommandLine(new TrantoCommand()).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
