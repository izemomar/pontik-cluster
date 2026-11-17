// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import dev.pontik.cluster.fault.Code;

/** Why this node will not protect a channel: the fault code and its message. */
final class Refusal {

    final Code code;
    final String message;

    Refusal(Code code, String message) {
        this.code = code;
        this.message = message;
    }
}
