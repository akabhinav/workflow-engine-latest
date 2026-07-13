package io.tranto.core.models.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateTypeTest {

    @Test
    void shouldClassifyTerminatedStates() {
        // Given / When / Then
        assertThat(StateType.SUCCESS.isTerminated()).isTrue();
        assertThat(StateType.FAILED.isTerminated()).isTrue();
        assertThat(StateType.KILLED.isTerminated()).isTrue();
        assertThat(StateType.RUNNING.isTerminated()).isFalse();
        assertThat(StateType.CREATED.isTerminated()).isFalse();
        assertThat(StateType.PAUSED.isTerminated()).isFalse();
    }

    @Test
    void shouldDistinguishFailureFromSuccessLikeTerminals() {
        assertThat(StateType.FAILED.isFailed()).isTrue();
        assertThat(StateType.SUCCESS.isFailed()).isFalse();

        assertThat(StateType.SUCCESS.isTerminatedNoFail()).isTrue();
        assertThat(StateType.WARNING.isTerminatedNoFail()).isTrue();
        assertThat(StateType.FAILED.isTerminatedNoFail()).isFalse();
    }

    @Test
    void shouldClassifyRunningAndCreated() {
        assertThat(StateType.RUNNING.isRunning()).isTrue();
        assertThat(StateType.KILLING.isRunning()).isTrue();
        assertThat(StateType.SUCCESS.isRunning()).isFalse();

        assertThat(StateType.CREATED.isCreated()).isTrue();
        assertThat(StateType.RESTARTED.isCreated()).isTrue();
        assertThat(StateType.RUNNING.isCreated()).isFalse();
    }

    @Test
    void shouldParseCaseInsensitivelyAndFallBackToUnknown() {
        assertThat(StateType.fromString("success")).isEqualTo(StateType.SUCCESS);
        assertThat(StateType.fromString("SUCCESS")).isEqualTo(StateType.SUCCESS);
        assertThat(StateType.fromString("Running")).isEqualTo(StateType.RUNNING);
        assertThat(StateType.fromString("nonsense")).isEqualTo(StateType.UNKNOWN);
        assertThat(StateType.fromString(null)).isEqualTo(StateType.UNKNOWN);
    }
}
