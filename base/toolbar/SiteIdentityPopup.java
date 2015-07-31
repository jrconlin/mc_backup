/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.toolbar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;
import android.widget.ImageView;
import android.widget.Toast;
import org.json.JSONException;
import org.json.JSONArray;
import org.mozilla.gecko.AboutPages;
import org.mozilla.gecko.AppConstants;
import org.mozilla.gecko.EventDispatcher;
import org.mozilla.gecko.R;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.SiteIdentity;
import org.mozilla.gecko.SiteIdentity.SecurityMode;
import org.mozilla.gecko.SiteIdentity.MixedMode;
import org.mozilla.gecko.SiteIdentity.TrackingMode;
import org.mozilla.gecko.Tab;
import org.mozilla.gecko.Tabs;
import org.mozilla.gecko.util.GeckoEventListener;
import org.mozilla.gecko.util.ThreadUtils;
import org.mozilla.gecko.widget.AnchoredPopup;
import org.mozilla.gecko.widget.DoorHanger;
import org.mozilla.gecko.widget.DoorHanger.OnButtonClickListener;
import org.json.JSONObject;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.mozilla.gecko.widget.DoorhangerConfig;
import org.mozilla.gecko.widget.SiteLogins;

/**
 * SiteIdentityPopup is a singleton class that displays site identity data in
 * an arrow panel popup hanging from the lock icon in the browser toolbar.
 */
public class SiteIdentityPopup extends AnchoredPopup implements GeckoEventListener {

    public static enum ButtonType { DISABLE, ENABLE, KEEP_BLOCKING, CANCEL, COPY };

    private static final String LOGTAG = "GeckoSiteIdentityPopup";

    private static final String TRACKING_CONTENT_SUPPORT_URL =
        "https://support.mozilla.org/kb/firefox-android-tracking-protection";

    // Placeholder string.
    private final static String FORMAT_S = "%s";

    private final Resources mResources;
    private SiteIdentity mSiteIdentity;

    private LinearLayout mIdentity;

    private LinearLayout mIdentityKnownContainer;

    private ImageView mIcon;
    private TextView mTitle;
    private TextView mSecurityState;
    private TextView mMixedContentActivity;
    private TextView mOwner;
    private TextView mOwnerSupplemental;
    private TextView mVerifier;
    private TextView mSiteSettingsLink;

    private View mDivider;

    private DoorHanger mTrackingContentNotification;
    private DoorHanger mSelectLoginDoorhanger;

    private final OnButtonClickListener mContentButtonClickListener;

    public SiteIdentityPopup(Context context) {
        super(context);

        mResources = mContext.getResources();

        mContentButtonClickListener = new ContentNotificationButtonListener();
        EventDispatcher.getInstance().registerGeckoThreadListener(this, "Doorhanger:Logins");
        EventDispatcher.getInstance().registerGeckoThreadListener(this, "Permissions:CheckResult");
    }

    @Override
    protected void init() {
        super.init();

        // Make the popup focusable so it doesn't inadvertently trigger click events elsewhere
        // which may reshow the popup (see bug 785156)
        setFocusable(true);

        LayoutInflater inflater = LayoutInflater.from(mContext);
        mIdentity = (LinearLayout) inflater.inflate(R.layout.site_identity, null);
        mContent.addView(mIdentity);

        mIdentityKnownContainer =
                (LinearLayout) mIdentity.findViewById(R.id.site_identity_known_container);

        mIcon = (ImageView) mIdentity.findViewById(R.id.site_identity_icon);
        mTitle = (TextView) mIdentity.findViewById(R.id.site_identity_title);
        mSecurityState = (TextView) mIdentity.findViewById(R.id.site_identity_state);
        mMixedContentActivity = (TextView) mIdentity.findViewById(R.id.mixed_content_activity);

        mOwner = (TextView) mIdentityKnownContainer.findViewById(R.id.owner);
        mOwnerSupplemental = (TextView) mIdentityKnownContainer.findViewById(R.id.owner_supplemental);
        mVerifier = (TextView) mIdentityKnownContainer.findViewById(R.id.verifier);
        mDivider = mIdentity.findViewById(R.id.divider_doorhanger);

        mSiteSettingsLink = (TextView) mIdentity.findViewById(R.id.site_settings_link);
    }

    private void updateIdentity(final SiteIdentity siteIdentity) {
        if (!mInflated) {
            init();
        }

        final boolean isIdentityKnown = (siteIdentity.getSecurityMode() != SecurityMode.UNKNOWN);
        updateConnectionState(siteIdentity);
        toggleIdentityKnownContainerVisibility(isIdentityKnown);

        if (isIdentityKnown) {
            updateIdentityInformation(siteIdentity);
        }

        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent(
            "Permissions:Check", null));
    }

    @Override
    public void handleMessage(String event, JSONObject geckoObject) {
        if ("Doorhanger:Logins".equals(event)) {
            try {
                final Tab selectedTab = Tabs.getInstance().getSelectedTab();
                if (selectedTab != null) {
                    final JSONObject data = geckoObject.getJSONObject("data");
                    addLoginsToTab(data);
                }
                if (isShowing()) {
                    addSelectLoginDoorhanger(selectedTab);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "Error accessing logins in Doorhanger:Logins message", e);
            }
        } else if ("Permissions:CheckResult".equals(event)) {
            final boolean hasPermissions = geckoObject.optBoolean("hasPermissions", false);
            if (hasPermissions) {
                mSiteSettingsLink.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Permissions:Get", null));
                        dismiss();
                    }
                });
            }

            ThreadUtils.postToUiThread(new Runnable() {
                @Override
                public void run() {
                    mSiteSettingsLink.setVisibility(hasPermissions ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    private void addLoginsToTab(JSONObject data) throws JSONException {
        final JSONArray logins = data.getJSONArray("logins");

        final SiteLogins siteLogins = new SiteLogins(logins);
        Tabs.getInstance().getSelectedTab().setSiteLogins(siteLogins);
    }

    private void addSelectLoginDoorhanger(Tab tab) throws JSONException {
        final SiteLogins siteLogins = tab.getSiteLogins();
        if (siteLogins == null) {
            return;
        }

        final JSONArray logins = siteLogins.getLogins();
        if (logins.length() == 0) {
            return;
        }

        final JSONObject login = (JSONObject) logins.get(0);

        // Create button click listener for copying a password to the clipboard.
        final OnButtonClickListener buttonClickListener = new OnButtonClickListener() {
            @Override
            public void onButtonClick(JSONObject response, DoorHanger doorhanger) {
                try {
                    final int buttonId = response.getInt("callback");
                    if (buttonId == ButtonType.COPY.ordinal()) {
                        final ClipboardManager manager = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                        String password;
                        if (response.has("password")) {
                            // Click listener being called from List Dialog.
                            password = response.optString("password");
                        } else {
                            password = login.getString("password");
                        }
                        if (AppConstants.Versions.feature11Plus) {
                            manager.setPrimaryClip(ClipData.newPlainText("password", password));
                        } else {
                            manager.setText(password);
                        }
                        Toast.makeText(mContext, R.string.doorhanger_login_select_toast_copy, Toast.LENGTH_SHORT).show();
                    }
                    dismiss();
                } catch (JSONException e) {
                    Log.e(LOGTAG, "Error handling Select login button click", e);
                    Toast.makeText(mContext, R.string.doorhanger_login_select_toast_copy_error, Toast.LENGTH_SHORT).show();
                }
            }
        };

        final DoorhangerConfig config = new DoorhangerConfig(DoorHanger.Type.LOGIN, buttonClickListener);

        // Set buttons.
        config.setButton(mContext.getString(R.string.button_cancel), ButtonType.CANCEL.ordinal(), false);
        config.setButton(mContext.getString(R.string.button_copy), ButtonType.COPY.ordinal(), true);

        // Set message.
        String username = ((JSONObject) logins.get(0)).getString("username");
        if (TextUtils.isEmpty(username)) {
            username = mContext.getString(R.string.doorhanger_login_no_username);
        }

        final String message = mContext.getString(R.string.doorhanger_login_select_message).replace(FORMAT_S, username);
        config.setMessage(message);

        // Set options.
        final JSONObject options = new JSONObject();

        // Add action text only if there are other logins to select.
        if (logins.length() > 1) {

            final JSONObject actionText = new JSONObject();
            actionText.put("type", "SELECT");

            final JSONObject bundle = new JSONObject();
            bundle.put("logins", logins);

            actionText.put("bundle", bundle);
            options.put("actionText", actionText);
        }

        config.setOptions(options);

        ThreadUtils.postToUiThread(new Runnable() {
            @Override
            public void run() {
                if (!mInflated) {
                    init();
                }

                removeSelectLoginDoorhanger();

                mSelectLoginDoorhanger = DoorHanger.Get(mContext, config);
                mContent.addView(mSelectLoginDoorhanger);
                mDivider.setVisibility(View.VISIBLE);
            }
        });
    }

    private void removeSelectLoginDoorhanger() {
        if (mSelectLoginDoorhanger != null) {
            mContent.removeView(mSelectLoginDoorhanger);
            mSelectLoginDoorhanger = null;
        }
    }

    private void toggleIdentityKnownContainerVisibility(final boolean isIdentityKnown) {
        final int identityInfoVisibility = isIdentityKnown ? View.VISIBLE : View.GONE;
        mIdentityKnownContainer.setVisibility(identityInfoVisibility);
    }

    private void updateConnectionState(final SiteIdentity siteIdentity) {
        if (siteIdentity.getEncrypted()) {
            mIcon.setImageResource(R.drawable.lock_secure);

            mSecurityState.setTextColor(mResources.getColor(R.color.affirmative_green));
            final Drawable stateIcon = ContextCompat.getDrawable(mContext, R.drawable.img_check);
            stateIcon.setBounds(0, 0, stateIcon.getIntrinsicWidth()/2, stateIcon.getIntrinsicHeight()/2);
            mSecurityState.setCompoundDrawables(stateIcon, null, null, null);
            mSecurityState.setCompoundDrawablePadding((int) mResources.getDimension(R.dimen.doorhanger_drawable_padding));
            mSecurityState.setText(R.string.identity_connection_secure);

            if (siteIdentity.getMixedMode() == MixedMode.MIXED_CONTENT_BLOCKED) {
                mMixedContentActivity.setVisibility(View.VISIBLE);
                mMixedContentActivity.setText(R.string.mixed_content_blocked_all);
            } else {
                mMixedContentActivity.setVisibility(View.GONE);
            }
        } else {
            if (siteIdentity.getMixedMode() == MixedMode.MIXED_CONTENT_LOADED) {
                mIcon.setImageResource(R.drawable.lock_disabled);
                mMixedContentActivity.setVisibility(View.VISIBLE);
                mMixedContentActivity.setText(R.string.mixed_content_protection_disabled);
            } else {
                mIcon.setImageResource(R.drawable.globe_light);
                mMixedContentActivity.setVisibility(View.GONE);
            }

            mSecurityState.setText(R.string.identity_connection_insecure);
            mSecurityState.setTextColor(mResources.getColor(R.color.placeholder_active_grey));
            mSecurityState.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            mSecurityState.setCompoundDrawablePadding(0);
        }
    }

    private void updateIdentityInformation(final SiteIdentity siteIdentity) {
        String owner = siteIdentity.getOwner();
        if (owner == null) {
            mOwner.setVisibility(View.GONE);
            mOwnerSupplemental.setVisibility(View.GONE);
        } else {
            mOwner.setVisibility(View.VISIBLE);
            mOwner.setText(owner);

            // Supplemental data is optional.
            final String supplemental = siteIdentity.getSupplemental();
            if (!TextUtils.isEmpty(supplemental)) {
                mOwnerSupplemental.setText(supplemental);
                mOwnerSupplemental.setVisibility(View.VISIBLE);
            } else {
                mOwnerSupplemental.setVisibility(View.GONE);
            }
        }

        final String verifier = siteIdentity.getVerifier();
        mVerifier.setText(verifier);
    }

    private void addTrackingContentNotification(boolean blocked) {
        // Remove any existing tracking content notification.
        removeTrackingContentNotification();

        final DoorhangerConfig config = new DoorhangerConfig(DoorHanger.Type.TRACKING, mContentButtonClickListener);

        final int icon = blocked ? R.drawable.shield_enabled: R.drawable.shield_disabled;

        final JSONObject options = new JSONObject();
        final JSONObject tracking = new JSONObject();
        try {
            tracking.put("enabled", blocked);
            options.put("tracking_protection", tracking);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Error adding tracking protection options", e);
        }
        config.setOptions(options);

        config.setLink(mContext.getString(R.string.learn_more), TRACKING_CONTENT_SUPPORT_URL);

        addNotificationButtons(config, blocked);

        mTrackingContentNotification = DoorHanger.Get(mContext, config);

        mTrackingContentNotification.setIcon(icon);

        mContent.addView(mTrackingContentNotification);
        mDivider.setVisibility(View.VISIBLE);
    }

    private void removeTrackingContentNotification() {
        if (mTrackingContentNotification != null) {
            mContent.removeView(mTrackingContentNotification);
            mTrackingContentNotification = null;
        }
    }

    private void addNotificationButtons(DoorhangerConfig config, boolean blocked) {
        if (blocked) {
            config.setButton(mContext.getString(R.string.disable_protection), ButtonType.DISABLE.ordinal(), false);
        } else {
            config.setButton(mContext.getString(R.string.enable_protection), ButtonType.ENABLE.ordinal(), true);
        }
    }

    /*
     * @param identityData A JSONObject that holds the current tab's identity data.
     */
    void setSiteIdentity(SiteIdentity siteIdentity) {
        mSiteIdentity = siteIdentity;
    }

    @Override
    public void show() {
        if (mSiteIdentity == null) {
            Log.e(LOGTAG, "Can't show site identity popup for undefined state");
            return;
        }

        // about: has an unknown SiteIdentity in code, but showing "This
        // site's identity is unknown" is misleading! So don't show a popup.
        final Tab selectedTab = Tabs.getInstance().getSelectedTab();
        if (selectedTab != null && AboutPages.isAboutPage(selectedTab.getURL())) {
            Log.d(LOGTAG, "We don't show site identity popups for about: pages");
            return;
        }

        updateIdentity(mSiteIdentity);

        final TrackingMode trackingMode = mSiteIdentity.getTrackingMode();
        if (trackingMode != TrackingMode.UNKNOWN) {
            addTrackingContentNotification(trackingMode == TrackingMode.TRACKING_CONTENT_BLOCKED);
        }

        try {
            addSelectLoginDoorhanger(selectedTab);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Error adding selectLogin doorhanger", e);
        }

        mTitle.setText(selectedTab.getBaseDomain());
        final Bitmap favicon = selectedTab.getFavicon();
        if (favicon != null) {
            final Drawable faviconDrawable = new BitmapDrawable(mResources, favicon);
            final int dimen = (int) mResources.getDimension(R.dimen.browser_toolbar_favicon_size);
            faviconDrawable.setBounds(0, 0, dimen, dimen);

            mTitle.setCompoundDrawables(faviconDrawable, null, null, null);
            mTitle.setCompoundDrawablePadding((int) mContext.getResources().getDimension(R.dimen.doorhanger_drawable_padding));
        }

        showDividers();

        super.show();
    }

    // Show the right dividers
    private void showDividers() {
        final int count = mContent.getChildCount();
        DoorHanger lastVisibleDoorHanger = null;

        for (int i = 0; i < count; i++) {
            final View child = mContent.getChildAt(i);

            if (!(child instanceof DoorHanger)) {
                continue;
            }

            DoorHanger dh = (DoorHanger) child;
            dh.showDivider();
            if (dh.getVisibility() == View.VISIBLE) {
                lastVisibleDoorHanger = dh;
            }
        }

        if (lastVisibleDoorHanger != null) {
            lastVisibleDoorHanger.hideDivider();
        }
    }

    void destroy() {
        EventDispatcher.getInstance().unregisterGeckoThreadListener(this, "Doorhanger:Logins");
        EventDispatcher.getInstance().unregisterGeckoThreadListener(this, "Permissions:CheckResult");
    }

    @Override
    public void dismiss() {
        super.dismiss();
        removeTrackingContentNotification();
        removeSelectLoginDoorhanger();
        mTitle.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        mDivider.setVisibility(View.GONE);
    }

    private class ContentNotificationButtonListener implements OnButtonClickListener {
        @Override
        public void onButtonClick(JSONObject response, DoorHanger doorhanger) {
            GeckoEvent e = GeckoEvent.createBroadcastEvent("Session:Reload", response.toString());
            GeckoAppShell.sendEventToGecko(e);
            dismiss();
        }
    }
}
