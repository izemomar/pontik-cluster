// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SelectTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "SELECT * FROM orders WHERE status = 'new'                         | true",
        "select id, last_update, updated_at from orders                     | true",
        "SELECT id FROM orders -- UPDATE orders SET x = 1                   | true",
        "SELECT id FROM orders /* DELETE FROM orders */                     | true",
        "SELECT 'please UPDATE me' AS note FROM orders                      | true",
        "SELECT \"update\" FROM orders                                      | true",
        "SELECT id FROM orders FOR UPDATE                                   | true",
        "SELECT id FROM orders FOR NO KEY UPDATE SKIP LOCKED                | true",
        "(SELECT id FROM a) UNION (SELECT id FROM b)                        | true",
        "WITH n AS (SELECT id FROM orders) SELECT id FROM n                 | true",
        "SELECT id FROM orders;                                             | true",
        "SELECT id FROM orders; TRUNCATE staging                            | false",
        "WITH d AS (DELETE FROM orders RETURNING id) SELECT id FROM d       | false",
        "WITH u AS (UPDATE orders SET done = true RETURNING id) SELECT * FROM u | false",
        "SELECT id INTO copy FROM orders                                    | false",
        "{call take_orders()}                                               | false",
        "EXEC take_orders                                                   | false",
        "CALL take_orders()                                                 | false",
        "UPDATE orders SET done = true                                      | false",
        "DO $$ BEGIN END $$                                                 | false",
    })
    void aSelectIsAPlainReadOnlyWhenItStartsAsOneAndWritesNothing(String sql, boolean plain) {
        assertEquals(plain, Select.plainRead(sql.trim()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "'' | false", "'   ' | false" })
    void anEmptySelectIsNotAPlainRead(String sql, boolean plain) {
        assertEquals(plain, Select.plainRead(sql));
    }
}
