// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

/** A REST call we answer with an error: its HTTP status, and the body that explains it. */
public final class Rejection extends Exception {

    private static final long serialVersionUID = 1L;

    public final int status;
    public final transient Object body;

    Rejection(int status, Object body) {
        super(String.valueOf(status));
        this.status = status;
        this.body = body;
    }
}
