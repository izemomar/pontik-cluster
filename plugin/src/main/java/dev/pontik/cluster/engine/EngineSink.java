// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Sink;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Writes our lines to the engine log, and sends one error event for each fault that fires, so an
 * alert an operator configures in the engine can match it. Events go out on a thread of their own,
 * so a caller never waits on the engine's event queues, and nothing here ever throws.
 */
final class EngineSink implements Sink {

    static final String LOGGER = "dev.pontik.cluster";

    private static final Logger LOG = LogManager.getLogger(LOGGER);

    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pontik-cluster-events");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<ErrorEvent> dispatch;

    EngineSink() {
        this(event -> ControllerFactory.getFactory().createEventController().dispatchEvent(event));
    }

    EngineSink(Consumer<ErrorEvent> dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void fired(Code code, String channelId, String line) {
        try {
            if (code.level == Code.Level.WARN) {
                LOG.warn(line);
            } else {
                LOG.error(line);
            }
            ErrorEvent event = event(channelId, line);
            events.execute(() -> {
                try {
                    dispatch.accept(event);
                } catch (Throwable t) {
                    LOG.warn("The engine did not take our error event.", t);
                }
            });
        } catch (Throwable t) {
            // Once the plugin stops, the event thread takes no more work, and the line is already logged.
        }
    }

    @Override
    public void cleared(String line) {
        info(line);
    }

    @Override
    public void info(String line) {
        try {
            LOG.info(line);
        } catch (Throwable t) {
            // A broken logger must never stop a lease from being renewed.
        }
    }

    @Override
    public void warn(String line, Throwable cause) {
        try {
            LOG.warn(line, cause);
        } catch (Throwable t) {
            // A broken logger must never stop a lease from being renewed.
        }
    }

    /**
     * Lets our INFO lines reach the engine log. The engine logs only errors for a package it does not
     * name, which would hide every transition line and the WARN faults, so we raise the level of our
     * own logger to INFO, unless the operator has set a level for it in conf/log4j2.properties.
     */
    static void enableInfo(String logger) {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            if (!context.getConfiguration().getLoggers().containsKey(logger)) {
                Configurator.setLevel(logger, Level.INFO);
            }
        } catch (Throwable t) {
            // Our lines then log at the engine's level for us, and the faults still send their events.
        }
    }

    /** Stops the event thread once the events already handed to it are sent. */
    void close() {
        events.shutdown();
    }

    /** Returns the error event for a fault: on the channel's source, or on the node. */
    static ErrorEvent event(String channelId, String line) {
        return channelId == null
            ? new ErrorEvent(null, null, null, ErrorEventType.ANY, null, null, line, null)
            : new ErrorEvent(channelId, 0, null, ErrorEventType.SOURCE_CONNECTOR, "Source", null, line, null);
    }
}
