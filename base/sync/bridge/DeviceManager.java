package org.mozilla.gecko.sync.bridge;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ClientConnectionRequest;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.conn.ManagedClientConnection;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpRequest;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONArray;
import org.mozilla.gecko.background.common.log.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;

import ch.boye.httpclientandroidlib.client.methods.HttpPatch;

/**
 * Created by firefox-dev on 04.08.15.
 */
public class DeviceManager {

    public static final String TAG = "Sync:GCM:DeviceManager:";
    public static final String ENDPOINT = "devmgr_endpoint";
    public static final String DEVICEID = "devmgr_deviceid";

    private String endpoint;

    public DeviceManager(String endpoint) {
        this.endpoint = endpoint;
    }

    public String register(String assertion, String name, String push_endpoint) throws IOException {
        JSONObject msg = new JSONObject();
        HttpPost req = new HttpPost(this.endpoint);
        HttpClient client = new DefaultHttpClient();
        HttpResponse resp = null;
        try {
            msg.put("name", name);
            msg.put("endpoint", push_endpoint);
            StringEntity body = new StringEntity(msg.toString());
            req.addHeader("Authorization", assertion);
            req.setEntity(body);
        } catch (JSONException | IOException x) {
            Logger.error(TAG + "register", "Registration Failed:" + x.toString());
            throw new IOException("Registration failed");
        }
        try {
            resp = client.execute(req);
            int code = resp.getStatusLine().getStatusCode();
            if (code < 200 || code > 299) {
                Logger.error(TAG + "register", "Registration Failed on server " + code);
                throw new IOException("Registration failed");
            }
            JSONObject reply = new JSONObject(new JSONTokener(resp.getEntity().toString()));
            return reply.getString("device-id");
        }catch (JSONException x) {
            Logger.error(TAG + "register", "Device Manager returned invalid response");
            if (resp != null) {
                Logger.debug(TAG + "register", resp.getEntity().toString());
            }
        }
        return null;
    }

    public JSONArray getPeers(String assertion) throws IOException{
        HttpGet req = new HttpGet(this.endpoint);
        HttpClient client = new DefaultHttpClient();
        HttpResponse resp = null;

        req.setHeader("Authorization", assertion);
        try {
            resp = client.execute(req);
            int code = resp.getStatusLine().getStatusCode();
            if (code < 200 || code > 299) {
                Logger.error(TAG + "register", "Registration Failed on server " + code);
                throw new IOException("Registration failed");
            }
            JSONObject response = new JSONObject(new JSONTokener(resp.getEntity().toString()));
            return response.getJSONArray("devices");

        }catch (JSONException x) {
            Logger.error(TAG + "register", "Device Manager returned invalid response");
            if (resp != null) {
                Logger.debug(TAG + "register", resp.getEntity().toString());
            }
            throw new IOException("Invalid response from device manager");
        }
    }

    public void delete(String assertion, String deviceId) throws IOException{
        HttpDelete req = new HttpDelete(this.endpoint);
        HttpClient client = new DefaultHttpClient();
        HttpResponse resp = null;

        req.setHeader("Authorization", assertion);

        resp = client.execute(req);
        int code = resp.getStatusLine().getStatusCode();
        if (code < 200 || code > 299) {
            Logger.error(TAG + "delete", "Could not delete device from server");
            throw new IOException("Failed to delete from server");
        }
    }

    public void update(String assertion, String deviceId, String push_endpoint) throws IOException {
        HttpPatch req = new HttpPatch(this.endpoint);
        ch.boye.httpclientandroidlib.client.HttpClient client = new ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient();
        JSONObject msg = new JSONObject();

        req.setHeader("Authorization", assertion);
        try {
            msg.put("deviceid", deviceId);
            msg.put("endpoint", push_endpoint);
            ch.boye.httpclientandroidlib.entity.StringEntity entity = new ch.boye.httpclientandroidlib.entity.StringEntity(msg.toString());
            req.setEntity(entity);
            client.execute(req);
        } catch (JSONException|IOException x) {
            Logger.error(TAG + "update", "Could not update device manager: " + x.toString());
            throw new IOException("Failed to update device manager");
        }
    }
}
