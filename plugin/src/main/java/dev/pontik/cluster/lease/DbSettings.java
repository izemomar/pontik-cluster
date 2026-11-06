// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

/**
 * The engine's database settings, as the engine reports them. We never log the URL, the user or
 * the password, so this class prints only the database type.
 */
public final class DbSettings {

    public final String type;
    public final String url;
    public final String user;
    public final String password;

    public DbSettings(String type, String url, String user, String password) {
        this.type = type;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public String toString() {
        return "database " + type;
    }
}
