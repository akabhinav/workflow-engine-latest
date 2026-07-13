package io.tranto.core.exceptions;

/**
 * Thrown when a Pebble expression cannot be rendered by the run context (bad syntax, missing
 * variable in strict mode, etc.). Checked, because rendering is an expected failure mode a
 * task/engine must handle rather than an unrecoverable bug.
 */
public class IllegalVariableEvaluationException extends Exception {

    public IllegalVariableEvaluationException(String message) {
        super(message);
    }

    public IllegalVariableEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }

    public IllegalVariableEvaluationException(Throwable cause) {
        super(cause);
    }
}
