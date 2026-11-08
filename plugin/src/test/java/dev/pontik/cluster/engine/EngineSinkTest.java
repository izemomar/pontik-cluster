// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import dev.pontik.cluster.fault.Code;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;

class EngineSinkTest {

    @Test
    void aChannelFaultIsAnErrorEventOnTheChannelsSource() {
        ErrorEvent event = EngineSink.event("ch-1", "[PONTIK-CLUSTER] PC-106 ...");

        assertEquals("ch-1", event.getChannelId());
        assertEquals(Integer.valueOf(0), event.getMetaDataId());
        assertEquals(ErrorEventType.SOURCE_CONNECTOR, event.getType());
        assertEquals("[PONTIK-CLUSTER] PC-106 ...", event.getCustomMessage());
    }

    @Test
    void aNodeFaultIsAnErrorEventOfTypeAnyWithoutAChannel() {
        ErrorEvent event = EngineSink.event(null, "[PONTIK-CLUSTER] PC-102 ...");

        assertNull(event.getChannelId());
        assertNull(event.getMetaDataId());
        assertEquals(ErrorEventType.ANY, event.getType());
    }

    @Test
    void aFiredFaultReturnsBeforeTheEventIsSentAndOnlyAFaultSendsOne() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<ErrorEvent> sent = new ArrayBlockingQueue<>(4);
        EngineSink sink = new EngineSink(event -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sent.add(event);
        });

        sink.fired(Code.PC106, "ch-1", "line");
        sink.cleared("cleared");
        sink.info("info");
        assertTrue(sent.isEmpty());
        release.countDown();

        assertEquals("line", sent.poll(5, TimeUnit.SECONDS).getCustomMessage());
        sink.close();
        assertNull(sent.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void ourLoggerLogsInfoUnlessTheOperatorSetItsLevel() {
        EngineSink.enableInfo("dev.pontik.cluster.test.unset");
        Configurator.setLevel("dev.pontik.cluster.test.set", Level.ERROR);
        EngineSink.enableInfo("dev.pontik.cluster.test.set");

        assertTrue(LogManager.getLogger("dev.pontik.cluster.test.unset").isInfoEnabled());
        assertFalse(LogManager.getLogger("dev.pontik.cluster.test.set").isWarnEnabled());
    }

    @Test
    void aFaultFiredAfterTheSinkClosedNeverThrows() {
        EngineSink sink = new EngineSink(event -> {
            throw new IllegalStateException("no");
        });
        sink.close();

        assertDoesNotThrow(() -> sink.fired(Code.PC102, null, "line"));
    }
}
