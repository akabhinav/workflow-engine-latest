package io.tranto.core.models.annotations;

/**
 * Broad functional category a plugin belongs to. Used to group plugins in the UI
 * catalogue and documentation.
 */
public enum PluginCategory {
    CORE,
    FLOW,
    BATCH,
    CLOUD,
    DATABASE,
    MESSAGING,
    STORAGE,
    SCRIPT,
    NOTIFICATION,
    TOOL,
    ALERTING,
    MONITORING,
    OTHER
}
