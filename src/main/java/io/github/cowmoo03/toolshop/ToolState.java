package io.github.cowmoo03.toolshop;

/** Where an upgraded tool is in its rental period. */
public enum ToolState {
    /** Ability works. */
    ACTIVE,
    /** Expired, but can still be renewed at the renewal price. */
    GRACE,
    /** Grace period over. Re-activating costs the full upgrade price. */
    LAPSED
}
