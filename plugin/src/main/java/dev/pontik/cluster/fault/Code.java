// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

/**
 * The fault codes. Each fires only when an operator must act. A code marked unprotected makes
 * the status call report its channel, or every channel for a node code, as unprotected while it
 * fires.
 */
public enum Code {

    PC101(Level.ERROR, true, false),
    PC102(Level.ERROR, false, false),
    PC103(Level.WARN, false, false),
    PC104(Level.ERROR, false, true),
    PC105(Level.WARN, false, true),
    PC106(Level.ERROR, false, true),
    PC107(Level.ERROR, false, true),
    PC110(Level.ERROR, false, false),
    PC112(Level.ERROR, false, true),
    PC113(Level.ERROR, false, true),
    PC114(Level.ERROR, false, true),
    PC115(Level.ERROR, false, true),
    PC116(Level.ERROR, false, true),
    PC118(Level.ERROR, false, false),
    PC119(Level.ERROR, false, true);

    public enum Level { WARN, ERROR }

    public final Level level;

    /** A one-shot code fires once per occurrence and keeps no firing state. */
    public final boolean oneShot;

    public final boolean unprotected;

    Code(Level level, boolean oneShot, boolean unprotected) {
        this.level = level;
        this.oneShot = oneShot;
        this.unprotected = unprotected;
    }

    /** Returns the code as operators see it, such as "PC-103". */
    public String label() {
        return "PC-" + name().substring(2);
    }
}
