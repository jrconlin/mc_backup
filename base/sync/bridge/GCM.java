package org.mozilla.gecko.sync.bridge;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.mozilla.gecko.AppConstants;
import org.mozilla.gecko.GeckoSharedPrefs;
import org.mozilla.gecko.background.common.log.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.Buffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.mozilla.gecko.util.IOUtils;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** GCM Router client
 *
 * This class defines registration handlers to allow Gecko to use Google Cloud
 * Messaging as a routing bridge.
 *
 * Created by jrconlin on 6/5/2015.
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

    /** Initialize the GCM with the app SENDER_ID
     *
     * SenderID is the Project Number from the Google Developer's Console. It's
     * best to store that in an external configuration for obvious reasons (in
     * the Bundle?)
     *
     * @param sender_id Project Sender ID
     */
    public GCM(String sender_id) {
        this.SENDER_ID = sender_id;
    }

    /** Check to see if Google Play services are enabled for this device. If not, give up.
     *
     * @param context App Context
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

    private JSONObject toJson(HttpEntity entity) throws IOException, JSONException{
        String str = this.getString(entity);
        JSONTokener jsonTokener = new JSONTokener(str);
        return new JSONObject(jsonTokener);
    }

    private String getString(HttpEntity entity) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(entity.getContent(), "UTF-8"));
        StringBuilder builder = new StringBuilder();
        for (String line = ""; line != null ; line = reader.readLine()) {
            builder.append(line);
        }
        return builder.toString();
    }

    /** Get the GCM Registration ID
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
        if (this.ChannelID.isEmpty()){
            this.ChannelID = this.newChannelId();
        }
        return this.ChannelID;
    }

    /** Store a preference string
     *
     * This is more of a one-off convenience function. If you have more than one preference,
     * you want to store them yourself.
     *
     * @param name Name of the preference
     * @param value String value of the preference
     */
    private void storePref(String name, String value) {
        final SharedPreferences.Editor editor =
                this.activity.getPreferences(Context.MODE_PRIVATE).edit();
        Log.d(TAG + "store", ">>>>>>>>>>>>>>>> HERE");
        editor.putString(name, value);
        editor.commit();
        Log.d(TAG + "store", ">>>>>>>>>>>>>>>> THERE");
    }

    /** Update the Push server provided, persistent app preferences.
     *
     * @param reply JSON object from registration server containing new data
     * @throws JSONException
     */
    private void updatePrefs(JSONObject reply) throws JSONException {
        final SharedPreferences.Editor editor =
                GeckoSharedPrefs.forProfile(this.context).edit();
        // always use proffered values.
        this.UserAgentId = reply.getString("uaid");
        this.PushEndpoint = reply.getString("endpoint");
        this.SharedSecret = reply.getString("hash");
        this.ChannelID = reply.getString("channelID");
        // Save the preferences to non-volatile.
        editor.putString(UAID_PREF, this.UserAgentId);
        editor.putString(ENDPOINT_PREF, this.PushEndpoint);
        editor.putString(SECRET_PREF, this.SharedSecret);
        editor.putString(CHID_PREF, this.ChannelID);
        editor.commit();
    }

    // Convenience wrappers for local vars. Will use in memory cache or pull from preferences.
    private String getUaid () {
        if (this.UserAgentId == null) {
            this.UserAgentId = this.activity.getPreferences(Context.MODE_PRIVATE).getString(UAID_PREF, "");
        }
        return this.UserAgentId;
    }

    private String getSharedSecret () {
        if (this.SharedSecret == null) {
            this.SharedSecret = this.activity.getPreferences(Context.MODE_PRIVATE).getString(SECRET_PREF, "");
        }
        return this.SharedSecret;
    }

    static char[] hexChars = "0123456789ABCDEF".toCharArray();

    /** Convert a byte array into a hex value.
     *
     * @param buff incoming byte array
     * @return hex string converstion
     */
    private String bytesToHex(byte[] buff) {
        int len = buff.length;
        char [] chars = new char[len * 2];
        for (int i=0; i<len; i++) {
            int j = buff[i] & 0xFF;
            chars[i*2] = hexChars[j>>>4];
            chars[i*2+1] = hexChars[j & 0x0F];
        }
        return new String(chars);
    }

    /** Generate a valid Auth signature for non-registration calls to the push REST server
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

    /** Send a new registration to the push server
     *
     * This should only be called for new endpoints. If a registrationID
     * changes, use updateRegistration
     *
     * @param registrationId GCM Registration ID
     * @return the registered endpoint.
     * @throws IOException
     * @throws BridgeException
     */
    protected String sendRegistration(final String registrationId, String channelID) throws IOException, BridgeException {
        SharedPreferences prefs = this.activity.getPreferences(Context.MODE_PRIVATE);
        String backendUrl = prefs.getString(PUSH_URL_PREF,
                PushServerURL + "/register");
        // Use the pre-existing UAID if we have one.
        String uaid = this.getUaid();
        JSONObject msg = new JSONObject();
        JSONObject routerData = new JSONObject();
        // ChannelID needs to be unique per "channel". A channel is a separate receiver (e.g. an
        // app or endpoint.)

        if (channelID == null || channelID.isEmpty()) {
            channelID = this.getChannelID();
        }
        if (backendUrl.equals("")){
            Log.e(TAG + "sendReg", "No push server url");
            throw new IOException("No backend URL specified for GCM bridge. Aborting.");
        }
        if (uaid != null && ! uaid.equals("")) {
            backendUrl += "/" + uaid;
        }
        HttpPost req = new HttpPost(backendUrl);
        try {
            msg.put("type", BRIDGE_TYPE);
            msg.put("channelID", channelID);
            routerData.put("token", registrationId);
            msg.put("data", routerData);
            StringEntity body = new StringEntity(msg.toString());
            body.setContentType("application/json");
            req.setEntity(body);
        }catch(JSONException x) {
            Log.e(TAG + "sendReg", "Failed to send registration " + x.toString());
            throw new BridgeException("Could not encode body " + x.getMessage());
        }
        try {
            Log.d(TAG + "onCreat", ">>>>>>>>>>>>>>>>> 1" + backendUrl + " " + msg.toString());
            DefaultHttpClient client = new DefaultHttpClient();
            HttpResponse resp = client.execute(req);
            Log.d(TAG + "onCreat", ">>>>>>>>>>>>>>>>> 2");
            int code = resp.getStatusLine().getStatusCode();
            if (code < 200 || code >= 300) {
                Log.e(TAG + "sendReg", "Failed to register " + code);
                throw new BridgeException("Registration failed:" + code);
            }
            // I can't pass JSONTokener as an arg to JSONObject? Really?
            JSONObject response = this.toJson(resp.getEntity());
            Log.d(TAG + "sendReg", "Successfully registered : " + response.toString());
            this.updatePrefs(response);
            Log.d(TAG + "sendReg", "Registered endpoint: " + this.PushEndpoint);
            return this.PushEndpoint;
        } catch (Exception x) {
            Log.e(TAG + "sendReg", "Registration failed " + x.toString());
            throw new BridgeException("Could not return endpoint ");
        }
    }

    /** Update an existing push registration record to have the latest info
     *
     * @param registrationID GCM Registration ID
     * @throws BridgeException
     */
    protected void updateRegistration(final String registrationID) throws BridgeException {
        HttpResponse resp;
        JSONObject msg = new JSONObject();
        JSONObject routerData = new JSONObject();
        SharedPreferences prefs = this.activity.getPreferences(Context.MODE_PRIVATE);
        String backendUrl = prefs.getString(PUSH_URL_PREF,
                PushServerURL + "/register");
        Log.d(TAG + "upReg", "Updating registration");
        String uaid = this.getUaid();
        if (uaid.isEmpty()) {
            Log.e(TAG + "upreg", "No UAID found. Did you call sendRegistration first?");
            throw new BridgeException("No UAID. Channel not registered?");
        }

        Log.d(TAG + "upReg", "Sending updated info to " + backendUrl);
        HttpPut req = new HttpPut(backendUrl);
        try {
            // Routing bridges are per UAID, so all channels go over the routing bridge.
            msg.put("type", BRIDGE_TYPE);
            routerData.put("token", registrationID);
            msg.put("data", routerData);
            StringEntity body = new StringEntity(msg.toString());
            if (this.getSharedSecret() != "") {
                req.addHeader("Authorization", this.genSignature(body));
            }
            req.setEntity(body);
        } catch (JSONException | IOException x) {
            Log.e(TAG + "upReg",
                    "Update failed. Could not encode request "+ x.toString());
            throw new BridgeException("Failed to Update Backend " + x.toString());
        }
        DefaultHttpClient client = new DefaultHttpClient();
        try {
            resp = client.execute(req);
        } catch (IOException x) {
            Log.e(TAG + "upReg",
                    "Update failed. " + x.toString());
            throw new BridgeException(x.getCause());
        }
        int code = resp.getStatusLine().getStatusCode();
        if (code < 200 || code > 299) {
            String reason = "Unknown";
            try {
                reason = this.getString(resp.getEntity());
            } catch (IOException x) {
                // couldn't read the reason.
            }
            Logger.error(TAG + "upReg",
                    "Update Failed :" + code + " " + reason);
            throw new BridgeException("Failed to Update record on push server " + code);
        }

    }

    /** Get the registrationID from GCM
     *
     * @param senderId Google Project Sender ID
     * @return GCM Registration ID
     * @throws IOException
     */
    protected String gcmRegister(String senderId) throws IOException {
        //TODO: wrap this as an async call?
        try {
            if (this.gcm == null) {
                this.gcm = GoogleCloudMessaging.getInstance(context);
            }
            Log.d(TAG + "gcmReg", "Attempting to register in gcm for " + senderId);
            // Google Play Services may lie. The check in checkPlayServices() may return true even
            // if GPS isn't actually present. This will cause the following to generate a null
            // pointer exception deep in it's code.

            if (true) {
                // TODO:: For testing, stub out the actual GCM registration, since it can be problematic.
                Log.e(TAG+"gcmReg", "####### STUBBING OUT GCM #########");
                return senderId;
            }
            return this.gcm.register(senderId);
        } catch (Exception x) {
            // If this fails, presume that GPS is borked and Christmas is ruined, forever.
            // If this fails, presume that GPS is borked and Christmas is ruined, forever.
            this.gcmFailure = true;
            Log.e(TAG + "gcmReg", "GCM Registration failed " + x.toString());
            throw new IOException("GCM Registration Failure");
        }
    }

    // API calls.

    /** Return a new ChannelID
     *
     * @return UUID ChannelID
     */
    public String newChannelId() {
        return UUID.randomUUID().toString();
    }

    /** Instantiate the bridge connection
     *
     * While you're in your Create routine, please call this method so that the
     *  connection can be (re-)established. GCM requires this call to be
     *  invoked on app creation and any reinit.
     *
     * @param context App Context
     * @param activity App Window Activity
     * @param savedInstanceState App Bundle.
     * @throws IOException
     * @throws BridgeException
     */
    public void onCreate(Context context, Activity activity, Bundle savedInstanceState) throws IOException,BridgeException {
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
        Log.d(TAG+"onCreat", "Checking registration IDs");
        // Check the old registration id, and update the server if required.
        final SharedPreferences prefs =
                this.activity.getPreferences(Context.MODE_PRIVATE);
        String oldReg = prefs.getString(OLD_REG_PREF, "");
        String regId = this.getRegistrationID();
        if (oldReg.isEmpty()) {
            Log.d(TAG + "onCreat", "Registering new ID " + regId);
            // Generate a new ChannelID for this specific use of Push.
            this.sendRegistration(regId, this.getChannelID());
        }
        else if (!oldReg.equals(regId)) {
            Log.d(TAG + "onCreat",
                    "Registration value changed from " + oldReg + " to " + this.RegistrationID);
            this.updateRegistration(regId);
        }
        // oldRegister is the same as the current one, no need to update.
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
            return this.sendRegistration(this.getRegistrationID(), this.getChannelID());
        }catch (IOException x) {
            Logger.error(TAG + "getPushEndpoint", "Failed to get push endpoint " + x.getMessage());
        }
        return null;
    }

}
