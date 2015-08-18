/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.simple.JSONObject;
import org.mozilla.gecko.GeckoSharedPrefs;
import org.mozilla.gecko.sync.SyncConstants;
import org.mozilla.gecko.sync.net.AuthHeaderProvider;
import org.mozilla.gecko.sync.net.BaseResource;
import org.mozilla.gecko.sync.net.BaseResourceDelegate;
import org.mozilla.gecko.sync.net.Resource;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.HashMap;


import ch.boye.httpclientandroidlib.Header;
import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.client.ClientProtocolException;
import ch.boye.httpclientandroidlib.client.methods.HttpRequestBase;
import ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient;
import ch.boye.httpclientandroidlib.message.BasicHeader;
import ch.boye.httpclientandroidlib.protocol.BasicHttpContext;

/**
 * Device manager handles communication with the Device Manager service
 * <p/>
 * In order to coordinate clients, the server needs to have a means to associate instances with
 * a user (so that when an sync event occurs, the server can then poll each of the clients). This
 * is handled via the DeviceManager service, which can associate endpoints with a given set of
 * FxA credentials.
 * <p/>
 * Note: The DeviceManager is not yet production ready and is expected to be part of the FxA suite.
 */
public class DeviceManager {

  private static final String DEVICEMANAGER_DEVID_PREF = "devicemanager_deviceid";

  class DeviceManagerDelegate extends BaseResourceDelegate{
    String POST = "DMD";
    String token = "";
    Context context = null;

    class DM_AuthHeader implements AuthHeaderProvider {
      String token = "";
      public DM_AuthHeader(String token) {
        super();
        this.token = token;
      }

      @Override
      public Header getAuthHeader(HttpRequestBase request, BasicHttpContext context, DefaultHttpClient client) throws GeneralSecurityException {
        if (this.token.isEmpty()) {
          return null;
        }
        return new BasicHeader("Authorization", token);
      }
    }

    public DeviceManagerDelegate(Resource resource, String token, Context context) {
      super(resource);
      this.token = token;
      this.context = context;
    }

    public AuthHeaderProvider getAuthHeaderProvider() {
      return new DM_AuthHeader(this.token);
    }

    public void Error(HttpResponse response) {
      int code = response.getStatusLine().getStatusCode();
      String msg;
      try {
        msg = GCM.entityToString(response.getEntity());
      } catch (IOException x) {
        Log.e(TAG + POST, "Could not register with device manager", x);
        msg = "Unknown or indecipherable response from server";
      }
      Log.e(TAG + POST, "Device Manager server failure: " + code + " " + msg);
    }

    protected void protocol_error(Exception x, String state) {
      Log.e(TAG + POST, "Device Manager server failure: " + state + " " + x.getMessage());
    }

    public void Success(HttpResponse response) {
      String deviceid = "";
      try {
        JSONObject body = GCM.entityToJson(response.getEntity());
        SharedPreferences prefs = GeckoSharedPrefs.forApp(this.context);
        deviceid = (String) body.get("deviceid");
        prefs.edit().putString(DEVICEMANAGER_DEVID_PREF, deviceid);
      } catch (JSONException | IOException | ClassCastException x) {
        Log.e(TAG + POST, "Could not store push response", x);
        return;
      }
      Log.d(TAG + POST, "Got deviceID: " + deviceid);
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
      this.protocol_error(x, "Transport Error");
    }

    public void handleHttpIOException(IOException x) {
      this.protocol_error(x, "Http Error");
    }

    public void handleHttpProtocolException(ClientProtocolException x) {
      this.protocol_error(x, "Protocol Error");
    }

  }

  public static final String TAG = "Sync:GCM:DeviceManager:";
  public static final String ENDPOINT = "devmgr_endpoint";
  public static final String DEVICEID = "devmgr_deviceid";

  private String endpoint;
  private Context context;

  public DeviceManager(String endpoint, Context context) throws IOException{
    this.context = context;
    this.endpoint = endpoint;
  }

  /**
   * Register a push endpoint with the current user.
   *
   * @param oauthToken    The FxA assertion containing the user credential set.
   * @param name          The user friendly name for the device.
   * @param push_endpoint The Push endpoint to store.
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public void register(final String oauthToken, String name, String push_endpoint) throws IOException {
    final String POST = "dm_reg";
    final BaseResource resource;
    try {
      resource = new BaseResource(this.endpoint);
    } catch (URISyntaxException x) {
      throw new IOException(x.getMessage());
    }

    JSONObject msg = new JSONObject();
    msg.put("name", name);
    msg.put("endpoint", push_endpoint);
    resource.delegate = new DeviceManagerDelegate(resource, oauthToken, this.context);

    class Runner implements Runnable {
      String token;
      Context context;
      JSONObject body;

      public Runner(String token, Context context, JSONObject body){
        super();
        this.token = token;
        this.context = context;
        this.body = body;
      }

      @Override
      public void run(){
        resource.delegate = new DeviceManagerDelegate(resource, this.token, this.context);
        try {
          resource.post(this.body);
        } catch (UnsupportedEncodingException x) {
          Log.e (TAG + POST, "Could not update Push Server ", x);
        }
      }
    }

    new Thread(new Runner(oauthToken, this.context, msg)).start();
  }

  /**
   * Delete the current device from the peer set.
   *
   * @param oauthToken The FxA assertion containing the user credential set.
   * @param deviceId   (optional) The unique device ID to remove, defaults to the current one.
   * @throws IOException
   */
  public void delete(String oauthToken, String deviceId) throws IOException {
    final String POST = "dm_del";
    final BaseResource resource;
    try {
      resource = new BaseResource(this.endpoint);
    } catch (URISyntaxException x) {
      throw new IOException(x.getMessage());
    }

    class DM_Del extends DeviceManagerDelegate {
      public DM_Del(Resource resource, String token, Context context) {
        super(resource, token, context);
      }

      @Override
      public void Error(HttpResponse response) {
        Log.e(TAG, "Could not delete device from manager");
      }
    }

    class Runner implements Runnable {
      String token;
      Context context;

      public Runner(String token, Context context){
        super();
        this.token = token;
        this.context = context;
      }

      @Override
      public void run(){
        resource.delegate = new DM_Del(resource, this.token, this.context);
        resource.delete();
      }
    }
    new Thread(new Runner(oauthToken, this.context)).start();
  }

  /**
   * Update the peer information for this device
   *
   * @param oauthToken    The FxA assertion containing the user credential set.
   * @param deviceId      the current device ID.
   * @param push_endpoint the new Push endpoint.
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public void update(String oauthToken, String deviceId, String push_endpoint) throws IOException {
    final String POST = "dm_reg";
    final BaseResource resource;
    try {
      resource = new BaseResource(this.endpoint);
    } catch (URISyntaxException x) {
      throw new IOException(x.getMessage());
    }

    JSONObject msg = new JSONObject();
    msg.put("deviceid", deviceId);
    msg.put("endpoint", push_endpoint);
    resource.delegate = new DeviceManagerDelegate(resource, oauthToken, this.context);

    class Runner implements Runnable {
      String token;
      JSONObject body;
      Context context;

      public Runner(String token, JSONObject body, Context context){
        super();
        this.token = token;
        this.body = body;
        this.context = context;
      }

      @Override
      public void run(){
        resource.delegate = new DeviceManagerDelegate(resource, this.token, this.context);
        try {
          resource.put(this.body);
        } catch (UnsupportedEncodingException x) {
          Log.e (TAG + POST, "Could not update Push Server ", x);
        }
      }
    }
    new Thread(new Runner(oauthToken, msg, this.context)).start();
  }
}
