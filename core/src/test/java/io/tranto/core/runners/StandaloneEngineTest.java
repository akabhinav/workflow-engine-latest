package io.tranto.core.runners;

import io.tranto.core.fixtures.LogFixture;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.tasks.Task;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase-3 milestone: a real flow runs end-to-end through queue -> executor -> worker and
 * reaches SUCCESS, with every task executed in order. This proves the whole architecture.
 */
class StandaloneEngineTest {

    @Test
    void shouldRunHelloWorldFlowToSuccess() {
        // Given a two-task flow
        Flow flow = Flow.builder()
            .id("hello_world")
            .namespace("dev")
            .revision(1)
            .tasks(List.of(
                LogFixture.builder().id("first").type(LogFixture.class.getName()).message("Hello").build(),
                LogFixture.builder().id("second").type(LogFixture.class.getName()).message("World").build()
            ))
            .build();

        // When
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(10));

            // Then: execution succeeded and both tasks ran successfully, in order
            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.getTaskRunList()).hasSize(2);
            assertThat(execution.getTaskRunList())
                .extracting(tr -> tr.getTaskId())
                .containsExactly("first", "second");
            assertThat(execution.getTaskRunList())
                .allMatch(tr -> tr.getState().current() == StateType.SUCCESS);
        }
    }

    @Test
    void shouldFailFastWhenATaskThrows() {
        // Given a flow whose middle task fails (FailingFixture throws)
        Flow flow = Flow.builder()
            .id("failing")
            .namespace("dev")
            .revision(1)
            .tasks(List.of(
                LogFixture.builder().id("ok").type(LogFixture.class.getName()).message("ok").build(),
                FailingFixture.builder().id("boom").type(FailingFixture.class.getName()).build(),
                LogFixture.builder().id("never").type(LogFixture.class.getName()).message("never").build()
            ))
            .build();

        // When
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(10));

            // Then: execution failed and the task after the failure never ran
            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            assertThat(execution.findTaskRunByTaskId("boom")).isPresent();
            assertThat(execution.findTaskRunByTaskId("boom").get().getState().current())
                .isEqualTo(StateType.FAILED);
            assertThat(execution.findTaskRunByTaskId("never")).isEmpty();
        }
    }

    /** A task that always throws, to exercise the fail-fast path. */
    @lombok.experimental.SuperBuilder(toBuilder = true)
    @lombok.Getter
    @lombok.NoArgsConstructor
    public static class FailingFixture extends Task
        implements io.tranto.core.models.tasks.RunnableTask<io.tranto.core.models.tasks.VoidOutput> {

        @Override
        public io.tranto.core.models.tasks.VoidOutput run(final RunContext runContext) {
            throw new RuntimeException("boom");
        }
    }
}
