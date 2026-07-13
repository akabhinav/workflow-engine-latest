package io.tranto.plugin.core;

import io.tranto.core.models.Plugin;
import io.tranto.plugin.core.debug.Echo;
import io.tranto.plugin.core.debug.Return;
import io.tranto.plugin.core.execution.Assert;
import io.tranto.plugin.core.execution.Exit;
import io.tranto.plugin.core.execution.Fail;
import io.tranto.plugin.core.flow.If;
import io.tranto.plugin.core.flow.Loop;
import io.tranto.plugin.core.flow.Parallel;
import io.tranto.plugin.core.flow.Pause;
import io.tranto.plugin.core.flow.Sequential;
import io.tranto.plugin.core.flow.Sleep;
import io.tranto.plugin.core.flow.Subflow;
import io.tranto.plugin.core.flow.Switch;
import io.tranto.plugin.core.http.Request;
import io.tranto.plugin.core.jdbc.Execute;
import io.tranto.plugin.core.jdbc.Query;
import io.tranto.plugin.core.kv.Get;
import io.tranto.plugin.core.kv.Set;
import io.tranto.plugin.core.log.Log;
import io.tranto.plugin.core.output.OutputValues;
import io.tranto.plugin.core.runner.Process;
import io.tranto.plugin.core.script.Commands;
import io.tranto.plugin.core.trigger.FlowTrigger;
import io.tranto.plugin.core.trigger.Schedule;

import java.util.List;

/**
 * The built-in plugin catalogue. Listed explicitly here for the current phase; a later phase
 * replaces this with automatic {@code ServiceLoader} discovery (the same mechanism external
 * plugin JARs use). The engine registers each of these at startup.
 */
public final class CorePlugins {

    private CorePlugins() {
    }

    /** @return every built-in plugin class shipped in this module. */
    public static List<Class<? extends Plugin>> all() {
        return List.of(
            // logging & debug
            Log.class,
            Return.class,
            Echo.class,
            // execution control
            Fail.class,
            Exit.class,
            Assert.class,
            OutputValues.class,
            // flowable control flow
            Sequential.class,
            Parallel.class,
            If.class,
            Switch.class,
            Sleep.class,
            Loop.class,
            Pause.class,
            Subflow.class,
            // triggers
            Schedule.class,
            FlowTrigger.class,
            // script execution
            Commands.class,
            Process.class,
            // key/value store
            Set.class,
            Get.class,
            // database
            Query.class,
            Execute.class,
            // integrations
            Request.class
        );
    }
}
