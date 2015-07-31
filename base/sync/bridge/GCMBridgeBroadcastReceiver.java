package org.mozilla.gecko.sync.bridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;

import org.mozilla.gecko.background.common.log.Logger;

/** Register ...sync.bridge.GCM as a broadcast receiver to handle GCM alerts
 *
 * Created by jrconlin on 6/10/2015.
 */
public class GCMBridgeBroadcastReceiver extends WakefulBroadcastReceiver{
    @Override
    /** On GCM.receive events...
     * This is called by the android framework
     *
     */
    public void onReceive(Context context, Intent intent) {
        Logger.info("GCMBridgeBroadcastReceiver", "Received GCM message");
        ComponentName comp = new ComponentName(context.getPackageName(), GCM.class.getName());
        startWakefulService(context, intent.setComponent(comp));
        setResultCode(Activity.RESULT_OK);
    }

}
