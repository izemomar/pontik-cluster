// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.SQLException;

/** A bootstrap that found something an operator must fix before this node can take part. */
public final class BootstrapException extends SQLException {

    private static final long serialVersionUID = 1L;

    public enum Kind { CANNOT_CREATE, MISSING_PRIVILEGE, WRONG_COLUMN }

    public final Kind kind;

    /** The table, the privileges or the column, as an operator reads them. */
    public final String subject;

    /** The column's type, or "missing", for {@link Kind#WRONG_COLUMN}. */
    public final String detail;

    private BootstrapException(Kind kind, String subject, String detail, String sqlState) {
        super(kind + ": " + subject + (detail == null ? "" : " " + detail), sqlState);
        this.kind = kind;
        this.subject = subject;
        this.detail = detail;
    }

    static BootstrapException cannotCreate(String table) {
        return new BootstrapException(Kind.CANNOT_CREATE, table, null, "42501");
    }

    static BootstrapException missingPrivileges(String privileges) {
        return new BootstrapException(Kind.MISSING_PRIVILEGE, privileges, null, "42501");
    }

    static BootstrapException wrongColumn(String column, String type) {
        return new BootstrapException(Kind.WRONG_COLUMN, column, type == null ? "missing" : type, "42804");
    }
}
