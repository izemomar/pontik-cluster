// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

import java.util.ArrayList;
import java.util.List;

/** A sink that keeps every line, for the tests to read back. */
public final class RecordingSink implements Sink {

    public final List<String> fired = new ArrayList<>();
    public final List<Code> codes = new ArrayList<>();
    public final List<String> cleared = new ArrayList<>();
    public final List<String> info = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    @Override
    public synchronized void fired(Code code, String channelId, String line) {
        codes.add(code);
        fired.add(line);
    }

    @Override
    public synchronized void cleared(String line) {
        cleared.add(line);
    }

    @Override
    public synchronized void info(String line) {
        info.add(line);
    }

    @Override
    public synchronized void warn(String line, Throwable cause) {
        warnings.add(line + " " + cause);
    }

    public synchronized long count(Code code) {
        return codes.stream().filter(code::equals).count();
    }
}
