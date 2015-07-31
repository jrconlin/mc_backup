package org.mozilla.gecko.sync.bridge;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.mozilla.gecko.GeckoSharedPrefs;
import org.mozilla.gecko.background.common.log.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

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
    private static final String BACKEND_URL = "push_backend_url";
    private static final String UAID = "push_uaid";
    private static final String CHID = "push_channel_id";
    private static final String SECRET = "push_secret";
    private static final String OLD_REG = "push_previous_registration_id";
    private static final String BRIDGE_TYPE = "gcm";
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    public static final String ENDPOINT = "push_endpoint";
    public static final String TAG = "Sync:GCM Bridge:";

    // Sender ID is the pre-registered GCM Sender ID from the Google Developer's Console
    private String SENDER_ID;

    // Values returend from the server after a registration.
    private String PushEndpoint;
    private String UserAgentId;
    private String SharedSecret;
    private String ChannelID;   // yes, this is used in one method, but this is readable & findable.

    private Activity activity;
    private Context context;
    protected GoogleCloudMessaging gcm;
    private String registrationId;

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

    /** Get application specific property values.
     *
     * @param context Application Context.
     * @return loaded properties
     * @throws IOException
     */
    protected Properties getConfig(Context context) throws IOException {
        Properties prop = new Properties();
        // If using gradle you can create this file in the res/raw/ directory
        // and load it via its Resource ID.
        // InputStream file = getResources().openRawResource(
        //  R.raw.app_properties)
        // Otherwise, you'll have to load the file in from the explicit path.
        FileInputStream file = context.openFileInput("res/raw/app_properties");
        prop.load(file);
        file.close();
        return prop;
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

    /** Get the GCM Registration ID
     *
     * @return GCM Registration ID
     * @throws BridgeException
     */
    private String getRegistrationId() throws BridgeException {
        if (!this.registrationId.equals("")) return this.registrationId;
        try {
            this.registrationId = this.gcmRegister(SENDER_ID);
        }catch(IOException x) {
            // Well, that didn't work...
            Logger.error(TAG + "getRegistrationId", x.getLocalizedMessage());
            this.registrationId = "";
            throw new BridgeException(x.toString());
        }
        return this.registrationId;
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
        editor.putString(name, value);
        editor.apply();
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
        editor.putString(UAID, this.UserAgentId);
        editor.putString(ENDPOINT, this.PushEndpoint);
        editor.putString(SECRET, this.SharedSecret);
        editor.putString(CHID, this.ChannelID);
        editor.apply();
    }

    // Convenience wrappers for local vars. Will use in memory cache or pull from preferences.
    private String getUaid () {
        if (this.UserAgentId == null) {
            this.UserAgentId = this.activity.getPreferences(Context.MODE_PRIVATE).getString(UAID, "");
        }
        return this.UserAgentId;
    }

    private String getSharedSecret () {
        if (this.SharedSecret == null) {
            this.SharedSecret = this.activity.getPreferences(Context.MODE_PRIVATE).getString(SECRET, "");
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
     * @param channelId A unique channelID
     * @return the registered endpoint.
     * @throws IOException
     * @throws BridgeException
     */
    protected String sendRegistration(final String registrationId, final String channelId) throws IOException, BridgeException {
        SharedPreferences prefs = this.activity.getPreferences(Context.MODE_PRIVATE);
        String backendUrl = prefs.getString(BACKEND_URL,
                "https://push.services.mozilla.org/register");
        // Use the pre-existing UAID if we have one.
        String uaid = this.getUaid();
        JSONObject msg = new JSONObject();
        JSONObject routerData = new JSONObject();
        if (backendUrl.equals("")){
            throw new IOException("No backend URL specified for GCM bridge. Aborting.");
        }
        if (uaid != null && ! uaid.equals("")) {
            backendUrl += "/" + uaid;
        }
        HttpPost req = new HttpPost(backendUrl);
        try {
            msg.put("type", BRIDGE_TYPE);
            msg.put("channelID", channelId);
            routerData.put("token", registrationId);
            msg.put("data", routerData);
            StringEntity body = new StringEntity(msg.toString());
            body.setContentType("application/json");
            req.setEntity(body);
        }catch(JSONException x) {
            throw new BridgeException("Could not encode body " + x.getMessage());
        }
        DefaultHttpClient client = new DefaultHttpClient();
        HttpResponse resp = client.execute(req);
        int code = resp.getStatusLine().getStatusCode();
        if (code < 200 || code >= 300) {
            Logger.error(TAG + "sendRegistration", "Failed to register " + code);
            throw new BridgeException("Registration failed:" + code);
        }
        Logger.info(TAG + "sendRegistration", "Successfully registered");
        try {
            JSONObject reply = new JSONObject(new JSONTokener(resp.getEntity().toString()));
            this.updatePrefs(reply);
            return this.PushEndpoint;
        } catch (JSONException x) {
            throw new BridgeException("Could not return endpoint " + x.getMessage());
        }
    }

    /** Update an existing push registration record to have the latest info
     *
     * @param registrationId GCM Registration ID
     * @throws BridgeException
     */
    protected void updateRegistration(final String registrationId) throws BridgeException {
        HttpResponse resp;
        JSONObject msg = new JSONObject();
        JSONObject routerData = new JSONObject();
        SharedPreferences prefs = this.activity.getPreferences(Context.MODE_PRIVATE);
        String backendUrl = prefs.getString(BACKEND_URL,
                "https://push.services.mozilla.org/register/");
        String uaid = this.getUaid();
        String secret = this.getSharedSecret();
        if (secret == null || secret.isEmpty()) {
            Logger.error(TAG + "updateRegistration",
                    "Attempted update of uninitialized connection");
            throw new BridgeException("Cannot update uninitialized connection");
        }
        backendUrl += "/" + uaid;

        HttpPut req = new HttpPut(backendUrl);
        try {
            // Routing bridges are per UAID, so all channels go over the routing bridge.
            msg.put("type", BRIDGE_TYPE);
            routerData.put("token", registrationId);
            msg.put("data", routerData);
            StringEntity body = new StringEntity(msg.toString());
            req.addHeader("Authorization", this.genSignature(body));
            req.setEntity(body);
        } catch (JSONException | IOException x) {
            Logger.error(TAG + "updateRegistration",
                    "Update failed. Could not encode request " + x.toString());
            throw new BridgeException("Failed to Update Backend " + x.toString());
        }
        DefaultHttpClient client = new DefaultHttpClient();
        try {
            resp = client.execute(req);
        } catch (IOException x) {
            Logger.error(TAG + "updateRegistration",
                    "Update failed. " + x.toString());
            throw new BridgeException(x.getCause());
        }
        int code = resp.getStatusLine().getStatusCode();
        if (code < 200 || code > 299) {
            Logger.error(TAG + "updateRegistration",
                    "Update Failed :" + code + " " + resp.getEntity().toString());
            throw new BridgeException("Failed to Update Backend " + code);
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
            if (gcm == null) {
                gcm = GoogleCloudMessaging.getInstance(context);
            }
            return gcm.register(senderId);
        } catch (IOException x) {
            Logger.error(TAG + "registerInBackground", x.getLocalizedMessage());
            throw x;
        }
    }

    // API calls.

    /** Return a new ChannelID
     *
     * @return UUID ChannelID
     */
    public static String newChannelId(){
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

        Properties config = this.getConfig(context);
        this.SENDER_ID = config.getProperty("sender_id");

        if (this.checkPlayServices(this.context, this.activity)) {
            this.gcm = GoogleCloudMessaging.getInstance(activity);
        } else {
            throw new BridgeException("Google Play Services not present");
        }
        if (this.SENDER_ID.equals(""))
            this.SENDER_ID = savedInstanceState.getString(PUSH_SENDER_ID);
        // Initialize the client registration id if required.
        if (this.registrationId.equals(""))
            this.getRegistrationId();

        // TODO: How can we register callbacks / events within GCMIntentService
        // to call sync functions on new events?

        // Check the old registration id, and update the server if required.
        final SharedPreferences prefs =
                this.activity.getPreferences(Context.MODE_PRIVATE);
        String oldReg = prefs.getString(OLD_REG, "");
        if (!oldReg.equals(this.registrationId)) {
            Logger.info(TAG + "getRegistrationIdFromPreferences",
                    "Registration value changed from " + oldReg + " to " + this.registrationId);
            this.storePref(OLD_REG, this.registrationId);
            this.updateRegistration(this.registrationId);
        }
    }

    // This presumes you always want a new endpoint. It is recommended that you
    // store the endpoint along with the associated handler in some persistent
    // data store.
    public String getPushEndpoint() throws BridgeException {
        String newChannelID = newChannelId();
        try {
            return this.sendRegistration(registrationId, newChannelID);
        }catch (IOException x) {
            Logger.error(TAG + "getPushEndpoint", "Failed to get push endpoint " + x.getMessage());
        }
        return null;
    }
}
