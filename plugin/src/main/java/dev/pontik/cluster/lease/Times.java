// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** The database's UTC timestamps as ISO-8601 text with a Z, so nobody reads them in a local zone. */
public final class Times {

    private Times() {
    }

    public static String iso(LocalDateTime utc) {
        return utc == null ? null : DateTimeFormatter.ISO_INSTANT.format(utc.toInstant(ZoneOffset.UTC));
    }
}
