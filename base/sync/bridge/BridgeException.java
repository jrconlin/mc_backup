package org.mozilla.gecko.sync.bridge;

/** Bridge exception returns if there is a GCM routing bridge issue.
 *
 */
public class BridgeException extends Exception {

    public BridgeException(final Throwable e) {
        super(e);
    }

    public BridgeException(String msg) {
        super(msg);
    }

    private static final long serialVersionUID = 1;
}
