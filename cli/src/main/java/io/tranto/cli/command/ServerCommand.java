package io.tranto.cli.command;

import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.DistributedEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.plugin.core.CorePlugins;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@code tranto server --role executor|worker|scheduler|all} — start a distributed node backed by a
 * shared JDBC database. Point several nodes at the same {@code --jdbc-url} (use an H2 file URL with
 * {@code AUTO_SERVER=TRUE} so multiple JVMs can connect) and they coordinate through the queue
 * tables: schedulers/executors enqueue work, workers pick it up.
 */
@Command(name = "server", mixinStandardHelpOptions = true,
    description = "Run a distributed node (executor|worker|scheduler|all) on a shared JDBC database.")
public class ServerCommand implements Callable<Integer> {

    @Option(names = {"--role"}, defaultValue = "all",
        description = "Node role: all | executor | worker | scheduler (default: all).")
    private String role;

    @Option(names = {"--jdbc-url"}, defaultValue = "jdbc:h2:file:./tranto-db;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
        description = "Shared H2 JDBC URL. Use the same value on every node.")
    private String jdbcUrl;

    @Option(names = {"--for"}, description = "Run for N seconds then exit (default: until interrupted).")
    private Long forSeconds;

    @Override
    public Integer call() throws Exception {
        String r = role.toLowerCase(Locale.ROOT);
        boolean executor = r.equals("all") || r.equals("executor");
        boolean worker = r.equals("all") || r.equals("worker");
        boolean scheduler = r.equals("all") || r.equals("scheduler");
        if (!executor && !worker && !scheduler) {
            System.err.println("Unknown --role '" + role + "' (expected all|executor|worker|scheduler)");
            return 2;
        }

        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        JacksonMapper mappers = new JacksonMapper(registry);
        JdbcDatabase database = JdbcDatabase.h2(jdbcUrl);

        try (DistributedEngine node = new DistributedEngine(database, mappers, executor, worker, scheduler)) {
            System.out.println("Tranto node started");
            System.out.println("  role    : " + r);
            System.out.println("  jdbc-url: " + jdbcUrl);
            System.out.println("  (sharing the queue tables with any other node on the same URL)");

            if (forSeconds != null) {
                TimeUnit.SECONDS.sleep(forSeconds);
            } else {
                CountDownLatch latch = new CountDownLatch(1);
                Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
                latch.await();
            }
        }
        System.out.println("Tranto node stopped");
        return 0;
    }
}
