/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.keyguard;

import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.util.slim.LockscreenTargetUtils;
import com.android.internal.util.slim.DeviceUtils;
import com.android.internal.util.slim.SlimActions;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";
    private static final String ASSIST_ICON_METADATA_NAME =
        "com.android.systemui.action_assist_icon";

    private KeyguardSecurityCallback mCallback;
    private GlowPadView mGlowPadView;
    private KeyguardShortcuts mShortcuts;
    private ObjectAnimator mAnim;
    private View mFadeView;
    private boolean mIsBouncing;
    private boolean mCameraDisabled;
    private boolean mSearchDisabled;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private Drawable mBouncerFrame;
    private String[] mStoredTargets;
    private int mTargetOffset;

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(View v, int target) {
            if (mStoredTargets == null) {
                final int resId = mGlowPadView.getResourceIdForTarget(target);
                switch (resId) {
                case R.drawable.ic_action_assist_generic:
                    Intent assistIntent =
                            ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                            .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
                    if (assistIntent != null) {
                        mActivityLauncher.launchActivity(assistIntent, false, true, null, null);
                    } else {
                        Log.w(TAG, "Failed to get intent for assist activity");
                    }
                    mCallback.userActivity(0);
                    break;

                case R.drawable.ic_lockscreen_camera:
                    mActivityLauncher.launchCamera(null, null);
                    mCallback.userActivity(0);
                    break;

                case R.drawable.ic_lockscreen_unlock_phantom:
                case R.drawable.ic_lockscreen_unlock:
                    mCallback.userActivity(0);
                    mCallback.dismiss(false);
                    break;
                }
            } else {
                if (target == mTargetOffset) {
                    mCallback.dismiss(false);
                } else {
                    int realTarget = target - mTargetOffset - 1;
                    String targetUri = realTarget < mStoredTargets.length
                            ? mStoredTargets[realTarget] : null;

                    if (GlowPadView.EMPTY_TARGET.equals(targetUri)) {
                        mCallback.dismiss(false);
                    } else {
                        SlimActions.processAction(mContext, targetUri, false);
                    }
                }
            }
        }

        public void onReleased(View v, int handle) {
            if (!mIsBouncing) {
                doTransition(mFadeView, 1.0f);
            }
        }

        public void onGrabbed(View v, int handle) {
            mCallback.userActivity(0);
            doTransition(mFadeView, 0.0f);
        }

        public void onGrabbedStateChange(View v, int handle) {

        }

        public void onTargetChange(View v, int target) {

        }

        public void onFinishFinalAnimation() {

        }

    };

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(State simState) {
            updateTargets();
        }
    };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        Context getContext() {
            return mContext;
        }
    };

    public KeyguardSelectorView(Context context) {
        this(context, null);
    }

    public KeyguardSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
        mTargetOffset = LockscreenTargetUtils.getTargetOffset(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources res = getResources();

        LinearLayout glowPadContainer = (LinearLayout) findViewById(
                R.id.keyguard_glow_pad_container);
        if (glowPadContainer != null) {
            glowPadContainer.bringToFront();
        }
        final boolean isLandscape = res.getSystem().getConfiguration()
                .orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (glowPadContainer != null &&  isLandscape &&
                LockscreenTargetUtils.isShortcuts(mContext) &&
                DeviceUtils.isPhone(mContext) &&
                !LockscreenTargetUtils.isEightTargets(mContext)) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
            );
            int pxBottom = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                60,
                res.getDisplayMetrics());
            params.setMargins(0, 0, 0, -pxBottom);
            glowPadContainer.setLayoutParams(params);
        }

        if (glowPadContainer != null &&
                LockscreenTargetUtils.isEightTargets(mContext)) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            );
            int pxBottom = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                10,
                res.getDisplayMetrics());
            params.setMargins(0, 0, 0, -pxBottom);
            glowPadContainer.setLayoutParams(params);
        }

        LinearLayout msgAndShortcutsContainer = (LinearLayout) findViewById(
                R.id.keyguard_message_and_shortcuts);
        msgAndShortcutsContainer.bringToFront();

        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        updateTargets();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        View bouncerFrameView = findViewById(R.id.keyguard_selector_view_frame);
        mBouncerFrame = bouncerFrameView.getBackground();
    }

    public void setCarrierArea(View carrierArea) {
        mFadeView = carrierArea;
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    @Override
    public void showUsabilityHint() {
        mGlowPadView.ping();
    }

    private void updateTargets() {
        int currentUserHandle = mLockPatternUtils.getCurrentUser();
        DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUserHandle);
        boolean secureCameraDisabled = mLockPatternUtils.isSecure()
                && (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
        boolean cameraDisabledByAdmin = dpm.getCameraDisabled(null, currentUserHandle)
                || secureCameraDisabled;
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
        boolean disabledBySimState = monitor.isSimLocked();
        boolean cameraPresent =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
        boolean searchTargetPresent =
                isTargetPresent(com.android.internal.R.drawable.ic_action_assist_generic);

        if (cameraDisabledByAdmin) {
            Log.v(TAG, "Camera disabled by Device Policy");
        } else if (disabledBySimState) {
            Log.v(TAG, "Camera disabled by Sim State");
        }
        boolean currentUserSetup = 0 != Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0 /*default */,
                currentUserHandle);
        boolean searchActionAvailable =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT) != null;
        mCameraDisabled = cameraDisabledByAdmin || disabledBySimState || !cameraPresent
                || !currentUserSetup;
        mSearchDisabled = disabledBySimState || !searchActionAvailable || !searchTargetPresent
                || !currentUserSetup;
        updateResources();
    }

    public void updateResources() {
        String storedTargets = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_TARGETS, UserHandle.USER_CURRENT);
        if (storedTargets == null) {
            // Update the search icon with drawable from the search .apk
            if (!mSearchDisabled) {
                Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                        .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
                if (intent != null) {
                    // XXX Hack. We need to substitute the icon here but haven't formalized
                    // the public API. The "_google" metadata will be going away, so
                    // DON'T USE IT!
                    ComponentName component = intent.getComponent();
                    boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                            ASSIST_ICON_METADATA_NAME + "_google",
                            R.drawable.ic_action_assist_generic);

                    if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                                ASSIST_ICON_METADATA_NAME,
                                R.drawable.ic_action_assist_generic)) {
                            Slog.w(TAG, "Couldn't grab icon from package " + component);
                    }
                }
            }

            mGlowPadView.setEnableTarget(R.drawable.ic_lockscreen_camera, !mCameraDisabled);
            mGlowPadView.setEnableTarget(R.drawable.ic_action_assist_generic, !mSearchDisabled);
            mGlowPadView.setMagneticTargets(true);
        } else {
            mGlowPadView.setMagneticTargets(false);
            mStoredTargets = storedTargets.split("\\|");
            ArrayList<TargetDrawable> storedDrawables = new ArrayList<TargetDrawable>();
            final Resources res = getResources();
            final Drawable blankActiveDrawable = res.getDrawable(
                    R.drawable.ic_lockscreen_target_activated);
            final InsetDrawable activeBack = new InsetDrawable(blankActiveDrawable, 0, 0, 0, 0);

            // Shift targets for landscape lockscreen on phones
            for (int i = 0; i < mTargetOffset; i++) {
                storedDrawables.add(new TargetDrawable(res, null));
            }
            // Add unlock target
            storedDrawables.add(new TargetDrawable(res,
                    res.getDrawable(R.drawable.ic_lockscreen_unlock)));
            for (int i = 0; i < 8 - mTargetOffset - 1; i++) {
                if (i >= mStoredTargets.length) {
                    storedDrawables.add(new TargetDrawable(res, 0));
                    continue;
                }

                String uri = mStoredTargets[i];
                if (uri.equals(GlowPadView.EMPTY_TARGET)) {
                    storedDrawables.add(new TargetDrawable(res, 0));
                    continue;
                }

                try {
                    Intent intent = Intent.parseUri(uri, 0);
                    Drawable front = null;
                    Drawable back = activeBack;
                    boolean frontBlank = false;
                    String type = null;

                    if (intent.hasExtra(GlowPadView.ICON_FILE)) {
                        type = GlowPadView.ICON_FILE;
                        front = LockscreenTargetUtils.getDrawableFromFile(mContext,
                                intent.getStringExtra(GlowPadView.ICON_FILE));
                    } else if (intent.hasExtra(GlowPadView.ICON_RESOURCE)) {
                        String source = intent.getStringExtra(GlowPadView.ICON_RESOURCE);
                        String packageName = intent.getStringExtra(GlowPadView.ICON_PACKAGE);

                        if (source != null) {
                            front = LockscreenTargetUtils.getDrawableFromResources(mContext,
                                    packageName, source, false);
                            back = LockscreenTargetUtils.getDrawableFromResources(mContext,
                                    packageName, source, true);
                            type = GlowPadView.ICON_RESOURCE;
                            frontBlank = true;
                        }
                    }
                    if (front == null || back == null) {
                        front = LockscreenTargetUtils.getDrawableFromIntent(mContext, intent);
                    }

                    int inset = LockscreenTargetUtils.getInsetForIconType(mContext, type);
                    Drawable drawable = LockscreenTargetUtils.getLayeredDrawable(mContext,
                            back,front, inset, frontBlank);
                    TargetDrawable targetDrawable = new TargetDrawable(res, drawable);

                    ComponentName compName = intent.getComponent();
                    String className = compName == null ? null : compName.getClassName();
                    if (TextUtils.equals(className, "com.android.camera.CameraLauncher")) {
                        targetDrawable.setEnabled(!mCameraDisabled);
                    } else if (TextUtils.equals(className, "SearchActivity")) {
                        targetDrawable.setEnabled(!mSearchDisabled);
                    }

                    storedDrawables.add(targetDrawable);
                } catch (URISyntaxException e) {
                    Log.w(TAG, "Invalid target uri " + uri);
                    storedDrawables.add(new TargetDrawable(res, 0));
                }
            }
            mGlowPadView.setTargetResources(storedDrawables);
        }
    }

    void doTransition(View view, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
        mShortcuts = (KeyguardShortcuts) findViewById(R.id.shortcuts);
        if (mShortcuts != null) {
            mShortcuts.setKeyguardCallback(callback);
        }
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mGlowPadView.reset(false);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mUpdateCallback);
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mUpdateCallback);
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        mIsBouncing = true;
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        mIsBouncing = false;
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }
}
