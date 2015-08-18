/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mozilla.gecko.AppConstants;
import org.mozilla.gecko.GeckoSharedPrefs;
import org.mozilla.gecko.background.common.log.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.json.JSONException;
import org.json.simple.JSONObject;
import org.mozilla.gecko.sync.SyncConstants;
import org.mozilla.gecko.sync.net.BaseResource;
import org.mozilla.gecko.sync.net.BaseResourceDelegate;
import org.mozilla.gecko.sync.net.Resource;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ch.boye.httpclientandroidlib.HttpEntity;
import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.client.ClientProtocolException;
import ch.boye.httpclientandroidlib.entity.StringEntity;
import ch.boye.httpclientandroidlib.util.EntityUtils;

/**
 * GCM Router client
 * <p/>
 * This class defines registration handlers to allow Gecko to use Google Cloud
 * Messaging as a routing bridge.
 */
public class GCM {
  // Statics
  // Preference storage name (ideally, from Gecko)
  private static final String PUSH_SENDER_ID = "push_sender_id";
  private static final String PUSH_URL_PREF = "push_backend_url";
  // Default Push endpoint URL.
  //private static final String PushServerHost = "https://updates.services.mozilla.com";
  private static final String PushServerURL = "http://192.168.44.128:8082";
  private static final String UAID_PREF = "push_uaid";
  private static final String CHID_PREF = "push_channel_id";
  private static final String SECRET_PREF = "push_secret";
  private static final String OLD_REG_PREF = "push_previous_registration_id";
  private static final String BRIDGE_TYPE = "gcm";
  private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
  public static final String ENDPOINT_PREF = "push_endpoint";
  public static final String TAG = "Sync:GCM Bridge:";

  // The user agent we use for Push and DeviceManager doesn't really matter. Using Sync since
  // that's the only service we currently support.
  protected static final String USER_AGENT = SyncConstants.USER_AGENT;

  // Sender ID is the pre-registered GCM Sender ID from the Google Developer's Console
  private String SENDER_ID = "";

  // Values returend from the server after a registration.
  private String PushEndpoint = "";
  private String UserAgentId = "";
  private String SharedSecret = "";
  protected String ChannelID = "";

  protected String RegistrationID = "";

  private Activity activity;
  private Context context;
  protected GoogleCloudMessaging gcm;
  private boolean gcmFailure = false;
  // TODO:
  //  You need to run the server calls via a thread safe call back mechanism.
  // Have the results write to the perfs editor.
  // reduce the GCM onCreate to as small a set as possible, maybe with an async call out to register.
  //

  // use this to handle the success/error of the delegate.
  class PushResourceDelegate extends BaseResourceDelegate {

    GCM gcm;
    String POST = "Push";

    public PushResourceDelegate(GCM gcm, Resource resource) {
      super(resource);
      this.gcm = gcm;
    }

    public void Error(HttpResponse response) {
      int code = response.getStatusLine().getStatusCode();
      String msg;
      try {
        msg = GCM.entityToString(response.getEntity());
      } catch (IOException x){
        Log.e(TAG + POST, "Could not get response entity", x);
        msg = "Unknown or indecipherable response from server";
      }
      Log.e(TAG + POST, "Push URL server failure: " + code + " " + msg);
    }

    public void Error(Exception x, String state) {
      Log.e(TAG + POST, "Push URL server failure: " + state + ": " + x.getMessage());
    }

    public void Success(HttpResponse response) {
      try {
        gcm.updatePrefs(GCM.entityToJson(response.getEntity()));
      } catch (JSONException | IOException x) {
        Log.e(TAG + POST, "Could not store push response", x);
        return;
      }
      Log.d(TAG + POST, "Got Push endpoint " + gcm.PushEndpoint);
    }

    public void handleHttpResponse(HttpResponse response) {
      int code = response.getStatusLine().getStatusCode();
      if (code < 200 || code > 299) {
        this.Error(response);
        return;
      }
      this.Success(response);
    }

    public String getUserAgent() {
      return SyncConstants.USER_AGENT;
    }

    public void handleTransportException(GeneralSecurityException x) {
      this.Error(x, "Transport Error");
    }

    public void handleHttpIOException(IOException x) {
      this.Error(x, "Http Error");
    }

    public void handleHttpProtocolException(ClientProtocolException x) {
      this.Error(x, "Protocol Error");
    }

  }

  /**
   * Initialize the GCM with the app SENDER_ID
   * <p/>
   * SenderID is the Project Number from the Google Developer's Console. It's
   * best to store that in an external configuration for obvious reasons (in
   * the Bundle?)
   *
   * @param sender_id Project Sender ID
   */
  public GCM(String sender_id) {
    this.SENDER_ID = sender_id;
  }

  /**
   * Check to see if Google Play services are enabled for this device. If not, give up.
   *
   * @param context  App Context
   * @param activity App Activity
   * @return boolean indicating presence.
   * @throws IOException
   */
  public boolean checkPlayServices(Context context, Activity activity) throws IOException {
    if (context == null || activity == null) {
      throw (new IOException("Uninitialized call to GCM"));
    }
    // see note in gcmRegister()
    if (this.gcmFailure) {
      return false;
    }
    int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(context);
    if (resultCode != ConnectionResult.SUCCESS) {
      if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
        // Prompt the user to allow Play access
        GooglePlayServicesUtil.getErrorDialog(resultCode, activity,
                PLAY_SERVICES_RESOLUTION_REQUEST).show();
      } else {
        return false;
      }
    }
    return true;
  }

  protected static JSONObject entityToJson(HttpEntity entity) throws IOException, JSONException {
    try {
      JSONParser parser = new JSONParser();
      return (JSONObject)parser.parse(entityToString(entity));
    } catch (ParseException x) {
      throw new JSONException(x.getMessage());
    }
  }

  protected static String entityToString(HttpEntity entity) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(entity.getContent(), "UTF-8"));
    StringBuilder builder = new StringBuilder();
    for (String line = ""; line != null; line = reader.readLine()) {
      builder.append(line);
    }
    return builder.toString();
  }

  /**
   * Get the GCM Registration ID
   *
   * @return GCM Registration ID
   * @throws BridgeException
   */
  public String getRegistrationID() throws BridgeException {
    if (this.RegistrationID.isEmpty()) {
      try {
        this.RegistrationID = this.gcmRegister(this.SENDER_ID);
        Logger.debug(TAG, "Got registration ID:" + this.RegistrationID);
      } catch (IOException x) {
        // Well, that didn't work...
        Logger.error(TAG + "getRegistrationId", x.getLocalizedMessage());
        this.RegistrationID = "";
        throw new BridgeException(x.toString());
      }
    }
    return this.RegistrationID;
  }

  public String getChannelID() {
    if (this.ChannelID.isEmpty()) {
      this.ChannelID = this.newChannelId();
    }
    return this.ChannelID;
  }

  /**
   * Store a preference string
   * <p/>
   * This is more of a one-off convenience function. If you have more than one preference,
   * you want to store them yourself.
   *
   * @param name  Name of the preference
   * @param value String value of the preference
   */
  private void storePref(String name, String value) {
    final SharedPreferences.Editor editor =
            GeckoSharedPrefs.forProfile(this.context).edit();
    editor.putString(name, value);
    editor.commit();
  }

  /**
   * Update the Push server provided, persistent app preferences.
   *
   * @param reply JSON object from registration server containing new data
   * @throws JSONException
   */
  private void updatePrefs(JSONObject reply) throws JSONException {
    final SharedPreferences.Editor editor =
            GeckoSharedPrefs.forProfile(this.context).edit();

    // always use proffered values.
    try {
      this.UserAgentId = (String) reply.get("uaid");
      this.PushEndpoint = (String) reply.get("endpoint");
      this.SharedSecret = (String) reply.get("secret");
      this.ChannelID = (String) reply.get("channelID");
    } catch(ClassCastException x) {
      Log.e(TAG + "update", "Push reply contained unexpected value", x);
      throw new JSONException(x.getMessage());
    }

    // Save the preferences to non-volatile memory
    editor.putString(UAID_PREF, this.UserAgentId);
    editor.putString(ENDPOINT_PREF, this.PushEndpoint);
    editor.putString(SECRET_PREF, this.SharedSecret);
    editor.putString(CHID_PREF, this.ChannelID);
    editor.commit();
  }

  // Convenience wrappers for local vars. Will use in memory cache or pull from preferences.
  private String getUaid() {
    if (this.UserAgentId == null) {
      this.UserAgentId = GeckoSharedPrefs.forProfile(this.context).getString(UAID_PREF, "");
    }
    return this.UserAgentId;
  }

  private String getSharedSecret() {
    if (this.SharedSecret == null) {
      this.SharedSecret = GeckoSharedPrefs.forProfile(this.context).getString(SECRET_PREF, "");
    }
    return this.SharedSecret;
  }

  static char[] hexChars = "0123456789ABCDEF".toCharArray();

  /**
   * Convert a byte array into a hex value.
   *
   * @param buff incoming byte array
   * @return hex string converstion
   */
  private String bytesToHex(byte[] buff) {
    int len = buff.length;
    char[] chars = new char[len * 2];
    for (int i = 0; i < len; i++) {
      int j = buff[i] & 0xFF;
      chars[i * 2] = hexChars[j >>> 4];
      chars[i * 2 + 1] = hexChars[j & 0x0F];
    }
    return new String(chars);
  }

  /**
   * Generate a valid Auth signature for non-registration calls to the push REST server
   *
   * @param body String Entity containing the body to send.
   * @return a string containing the hash value to use in the Authorization header.
   * @throws IOException
   */
  private String genSignature(StringEntity body) throws IOException {
    String content = EntityUtils.toString(body);
    SecretKeySpec key = new SecretKeySpec(this.SharedSecret.getBytes("UTF-8"), "HmacSHA256");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      byte[] bytes = mac.doFinal(content.getBytes("UTF-8"));
      return bytesToHex(bytes);
    } catch (NoSuchAlgorithmException x) {
      Logger.error(TAG + "genSignature",
              "Invalid hash algo specified, failing " + x.toString());
      throw new IOException("HmacSHA256 unavailable");
    } catch (InvalidKeyException x) {
      Logger.error(TAG + "genSignature",
              "Invalid key specified, failing " + x.toString());
      throw new IOException("Invalid Key");
    }
  }

  /**
   * Send a new registration to the push server
   * <p/>
   * This should only be called for new endpoints. If a registrationID
   * changes, use updateRegistration
   *
   * @param registrationID GCM Registration ID
   * @return the registered endpoint.
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  protected void sendRegistration(final String registrationID, String channelID) throws IOException{
    final String POST = "sendReg";
    final BaseResource httpResource;
    SharedPreferences prefs = GeckoSharedPrefs.forApp(this.activity.getApplicationContext());
    String backendURL = prefs.getString(PUSH_URL_PREF,
            PushServerURL + "/register");
    // Use the pre-existing UAID if we have one.
    String uaid = this.getUaid();
    final JSONObject msg = new JSONObject();
    final JSONObject routerData = new JSONObject();
    // ChannelID needs to be unique per "channel". A channel is a separate receiver (e.g. an
    // app or endpoint.)

    if (channelID == null || channelID.isEmpty()) {
      channelID = this.getChannelID();
    }
    if (backendURL.equals("")) {
      Log.e(TAG + POST, "No push server URL");
      throw new IOException("No backend URL specified for GCM bridge. Aborting.");
    }
    if (uaid != null && !uaid.equals("")) {
      backendURL += "/" + uaid;
    }

    try {
      httpResource = new BaseResource(backendURL);
    } catch (URISyntaxException x) {
      Log.e(TAG + POST, "Invalid push server URL", x);
      throw new IOException(x);
    }

    msg.put("type", BRIDGE_TYPE);
    msg.put("channelID", channelID);
    routerData.put("token", registrationID);
    msg.put("data", routerData);
    class Runner implements Runnable {
      GCM self;
      JSONObject body;

      public Runner(GCM gcm, JSONObject body){
        super();
        this.self = gcm;
        this.body = body;
      }

      @Override
      public void run(){
        httpResource.delegate = new PushResourceDelegate(this.self, httpResource);
        try {
          httpResource.post(this.body);
        } catch (UnsupportedEncodingException x) {
          Log.e (TAG + POST, "Could not register with Push Server ", x);
        }
      }
    }

    new Thread(new Runner(this, msg)).start();
  }

  /**
   * Update an existing push registration record to have the latest info
   *
   * @param registrationID GCM Registration ID
   * @throws BridgeException
   */
  @SuppressWarnings("unchecked")
  protected void updateRegistration(final String registrationID) throws BridgeException, IOException {
    final String POST = "sendReg";
    final BaseResource httpResource;

    JSONObject msg = new JSONObject();
    JSONObject routerData = new JSONObject();
    SharedPreferences prefs = GeckoSharedPrefs.forApp(this.activity.getApplicationContext());
    String backendURL = prefs.getString(PUSH_URL_PREF, PushServerURL + "/register");
    Log.d(TAG + "upReg", "Updating registration");
    String uaid = this.getUaid();
    if (uaid.isEmpty()) {
      Log.e(TAG + "upreg", "No UAID found. Did you call sendRegistration first?");
      throw new BridgeException("No UAID. Channel not registered?");
    }

    Log.d(TAG + "upReg", "Sending updated info to " + backendURL);

    try {
      httpResource = new BaseResource(backendURL);
    } catch (URISyntaxException x) {
      Log.e(TAG + POST, "Invalid push server URL", x);
      throw new IOException(x);
    }
    msg.put("type", BRIDGE_TYPE);
    routerData.put("token", registrationID);
    msg.put("data", routerData);

    class Runner implements Runnable {
      GCM self;
      JSONObject body;

      public Runner(GCM gcm, JSONObject body){
        super();
        this.self = gcm;
        this.body = body;
      }

      @Override
      public void run(){
        httpResource.delegate = new PushResourceDelegate(this.self, httpResource);
        try {
          httpResource.put(this.body);
        } catch (UnsupportedEncodingException x) {
          Log.e (TAG + POST, "Could not update Push Server ", x);
        }
      }
    }

    new Thread(new Runner(this, msg)).start();
  }

  /**
   * Get the registrationID from GCM
   *
   * @param senderID Google Project Sender ID
   * @return GCM Registration ID
   * @throws IOException
   */
  protected String gcmRegister(String senderID) throws IOException {
    //TODO: wrap this as an async call?
    try {
      if (this.gcm == null) {
        this.gcm = GoogleCloudMessaging.getInstance(context);
      }
      Log.d(TAG + "gcmReg", "Attempting to register in gcm for " + senderID);
      // Google Play Services may lie. The check in checkPlayServices() may return true even
      // if GPS isn't actually present. This will cause the following to generate a null
      // pointer exception deep in it's code.
      if (true) {
        // TODO:: For testing, stub out the actual GCM registration, since it can be
        // problematic with the emulators
        Log.e(TAG + "gcmReg", "####### STUBBING OUT GCM #########");
        return senderID;
      }
      return this.gcm.register(senderID);
    } catch (Exception x) {
      // If this fails, presume that GPS is borked and Christmas is ruined, forever.
      // If this fails, presume that GPS is borked and Christmas is ruined, forever.
      this.gcmFailure = true;
      Log.e(TAG + "gcmReg", "GCM Registration failed " + x.toString());
      throw new IOException("GCM Registration Failure");
    }
  }

  // API calls.

  /**
   * Return a new ChannelID
   *
   * @return UUID ChannelID
   */
  public String newChannelId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Instantiate the bridge connection
   * <p/>
   * While you're in your Create routine, please call this method so that the
   * connection can be (re-)established. GCM requires this call to be
   * invoked on app creation and any reinit.
   *
   * @param context            App Context
   * @param activity           App Window Activity
   * @param savedInstanceState App Bundle.
   * @throws IOException
   * @throws BridgeException
   */
  public void onCreate(Context context, Activity activity, Bundle savedInstanceState) throws IOException, BridgeException {

    this.context = context;
    this.activity = activity;

    if (this.checkPlayServices(this.context, this.activity)) {
      Log.d(TAG + "onCreat", "Play services returned true");
      this.gcm = GoogleCloudMessaging.getInstance(activity);
    } else {
      throw new BridgeException("Google Play Services not present");
    }
    if (this.SENDER_ID.equals(""))
      this.SENDER_ID = AppConstants.MOZ_ANDROID_GCM_SENDERID;
    // Initialize the client registration id if required.
    Log.d(TAG + "onCreat", "Checking registration IDs");
    // Check the old registration id, and update the server if required.
    final SharedPreferences prefs = GeckoSharedPrefs.forApp(this.context);
    String oldReg = prefs.getString(OLD_REG_PREF, "");
    String regId = this.getRegistrationID();
    if (oldReg.isEmpty()) {
      Log.d(TAG + "onCreat", "Registering new ID " + regId);
      // Generate a new ChannelID for this specific use of Push.
      this.sendRegistration(regId, this.getChannelID());
    } else if (!oldReg.equals(regId)) {
      Log.d(TAG + "onCreat",
              "Registration value changed from " + oldReg + " to " + this.RegistrationID);
      this.updateRegistration(regId);
    }
    Log.d(TAG + "onCreat", "recording registration");
    prefs.edit().putString(OLD_REG_PREF, regId).commit();
    Log.d(TAG + "onCreat", "Push channel registered.");
  }

  // This presumes you always want a new endpoint. It is recommended that you
  // store the endpoint along with the associated handler in some persistent
  // data store.
  public String getPushEndpoint() throws BridgeException {
    this.ChannelID = newChannelId();
    try {
      sendRegistration(this.getRegistrationID(), this.getChannelID());
    } catch (IOException x) {
      Logger.error(TAG + "getPushEndpoint", "Failed to get push endpoint " + x.getMessage());
    }
    return null;
  }

}
