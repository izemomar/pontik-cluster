// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Rules every statement in Sql follows, checked on the strings the plugin really sends. */
class SqlTest {

    @Test
    void everyStatementReadsTheTimeOnlyFromTheDatabaseClockInUtc() throws IllegalAccessException {
        List<Pattern> forbidden = new ArrayList<>();
        for (String word : Arrays.asList("now\\s*\\(", "transaction_timestamp\\s*\\(", "statement_timestamp\\s*\\(",
                "timeofday\\s*\\(", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
                "LOCALTIME", "LOCALTIMESTAMP")) {
            forbidden.add(Pattern.compile("(?i)\\b" + word + (word.endsWith("(") ? "" : "\\b")));
        }
        for (String quoted : Arrays.asList("now", "today", "tomorrow", "yesterday")) {
            forbidden.add(Pattern.compile("(?i)'" + quoted + "'"));
        }
        Pattern clock = Pattern.compile("(?i)clock_timestamp\\s*\\(\\s*\\)");
        Pattern ruled = Pattern.compile("(?i)clock_timestamp\\s*\\(\\s*\\) AT TIME ZONE 'UTC'");

        for (Map.Entry<String, String> s : statements().entrySet()) {
            for (Pattern p : forbidden) {
                assertFalse(p.matcher(s.getValue()).find(), s.getKey() + ": " + s.getValue());
            }
            assertEquals(count(clock, s.getValue()), count(ruled, s.getValue()), s.getKey() + ": " + s.getValue());
        }
    }

    @Test
    void everyWriteNamesAPclTable() throws IllegalAccessException {
        Pattern write = Pattern.compile("(?i)\\b(?:INSERT INTO|DELETE FROM|CREATE TABLE(?: IF NOT EXISTS)?"
            + "|TRUNCATE(?: TABLE)?|DROP TABLE(?: IF EXISTS)?|ALTER TABLE(?: IF EXISTS)?"
            + "|(?<!FOR |DO )UPDATE)\\s+(\\w+)");
        int writes = 0;

        for (Map.Entry<String, String> s : statements().entrySet()) {
            Matcher m = write.matcher(s.getValue());
            while (m.find()) {
                writes++;
                assertTrue(m.group(1).startsWith("pcl_"), s.getKey() + " writes " + m.group(1));
            }
        }
        assertTrue(writes > 0, "The write pattern found no write at all.");
    }

    // Every String constant of Sql as the JVM holds it, after the compiler has joined its parts.
    private static Map<String, String> statements() throws IllegalAccessException {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Field f : Sql.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                statements.put(f.getName(), (String) f.get(null));
            }
        }
        assertFalse(statements.isEmpty());
        return statements;
    }

    private static int count(Pattern p, String text) {
        int n = 0;
        Matcher m = p.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
