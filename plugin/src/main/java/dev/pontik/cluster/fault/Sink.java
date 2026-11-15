// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

/**
 * Where faults and transition lines go. The engine side writes them to the engine log and sends
 * an error event for each fault; the tests record them.
 *
 * Every method must return promptly, and must never wait on the database or the network. Faults
 * calls {@code fired} and {@code cleared} while it holds its own lock, so a call that blocks holds up
 * every thread that raises or clears a fault, the fence timer among them. For the same reason these
 * two methods must never call back into Faults.
 */
public interface Sink {

    /** Logs a fault that fired at its code's level, and sends one error event. */
    void fired(Code code, String channelId, String line);

    /** Logs a cleared fault at INFO and sends nothing, so a recovery never alerts twice. */
    void cleared(String line);

    /** Logs a transition, such as a lease acquired or released, as one INFO line. */
    void info(String line);

    /**
     * Logs a failure that no fault code covers, such as a bug, as one WARN line with the cause. The
     * line is a fixed phrase or an SQLState, never exception text. The cause carries the exception,
     * except a driver's {@code SQLException}: its message can name the database host or user, so its
     * line carries the SQLState and the cause is null.
     */
    void warn(String line, Throwable cause);
}
