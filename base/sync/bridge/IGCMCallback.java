package org.mozilla.gecko.sync.bridge;

/**
 * Created by jrconlin on 6/5/2015.
 */
public interface IGCMCallback {
    public void onPushEvent(String endpoint, String version, String dataJSON);
}
