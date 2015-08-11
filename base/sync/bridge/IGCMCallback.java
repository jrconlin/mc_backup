package org.mozilla.gecko.sync.bridge;

/**
 *
 */
public interface IGCMCallback {
    public void onPushEvent(String endpoint, String version, String dataJSON);
}
