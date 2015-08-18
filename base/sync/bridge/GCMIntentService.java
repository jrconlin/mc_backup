/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.bridge;

import android.accounts.Account;
import android.app.IntentService;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.fxa.FirefoxAccounts;
import org.mozilla.gecko.fxa.authenticator.AndroidFxAccount;
import org.mozilla.gecko.sync.ThreadPool;

/**
 * Handle the incoming GCM intent
 * <p/>
 * This class is connected via the AndroidManifest.xml file under the
 * <application><service ... /></application>
 * tag. This handler deals with callbacks and errbacks to whatever code requires it.
 */
public class GCMIntentService extends IntentService {
  private NotificationManager notificationManager;
  public static String SERVICE_NAME = "GCMIntentService";
  static Bundle s_extras;

  public GCMIntentService() {
    super(SERVICE_NAME);
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    Bundle extras = intent.getExtras();
    GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(this);
    Context context = getApplicationContext();

    String messageType = gcm.getMessageType(intent);
    String tag = GCM.TAG + "onHandleIntent";

    if (!extras.isEmpty()) {
      if (GoogleCloudMessaging.MESSAGE_TYPE_SEND_ERROR.equals(messageType)) {
        Logger.error(tag, "GCM Send Error: " + extras.toString());
        // Error callback?
      } else if (GoogleCloudMessaging.MESSAGE_TYPE_DELETED.equals(messageType)) {
        Logger.error(tag, "GCM message Deleted: " + extras.toString());
        // Deleted Callback?
      } else if (GoogleCloudMessaging.MESSAGE_TYPE_MESSAGE.equals(messageType)) {
        Logger.info(tag, "GCM Message: " + extras.toString());
        final Account account = FirefoxAccounts.getFirefoxAccount(context);
        s_extras = extras;
        ThreadPool.run(new Runnable() {
          @Override
          public void run() {
            // Version = s_extras.getString("Ver");
            // Message = s_extras.getString("Msg");
            // Or just ignore 'em and presume that you've got work to do.
            for (String authority : AndroidFxAccount.DEFAULT_AUTHORITIES_TO_SYNC_AUTOMATICALLY_MAP.keySet()) {
              ContentResolver.requestSync(account, authority, s_extras);
            }
          }

        });
      }
    }
    GCMBridgeBroadcastReceiver.completeWakefulIntent(intent);
  }
}
