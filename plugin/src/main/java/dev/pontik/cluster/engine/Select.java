// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import java.util.regex.Pattern;

/**
 * Tells a Database Reader's select that only reads from one that may change data. A read we stop
 * partway is read again by the next owner, which is safe only when the read changed nothing.
 */
final class Select {

    // The leftmost match wins, so "--" inside a literal stays part of the literal.
    private static final Pattern NOISE =
        Pattern.compile("--[^\\n]*|/\\*.*?\\*/|'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"", Pattern.DOTALL);

    // The keywords are lower case, so the source-tree rule never mistakes them for one of our statements.
    private static final Pattern LOCKS =
        Pattern.compile("\\bfor\\s+(?:no\\s+key\\s+)?update\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FIRST_WORD = Pattern.compile("^[\\s(]*(?:select|with)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern MORE_AFTER_SEMICOLON = Pattern.compile(";\\s*\\S");

    private static final Pattern WRITES =
        Pattern.compile("\\b(?:update|delete|insert|merge|into)\\b", Pattern.CASE_INSENSITIVE);

    private Select() {
    }

    /**
     * Returns whether the select is a single plain read: after comments, string literals, quoted
     * identifiers and row-lock clauses are set aside, it starts with SELECT or WITH, holds one
     * statement, and names no write. A database function that writes still looks like a plain read.
     */
    static boolean plainRead(String sql) {
        if (sql == null) {
            return false;
        }
        String bare = LOCKS.matcher(NOISE.matcher(sql).replaceAll(" ")).replaceAll(" ");
        return FIRST_WORD.matcher(bare).find()
            && !MORE_AFTER_SEMICOLON.matcher(bare).find()
            && !WRITES.matcher(bare).find();
    }
}
