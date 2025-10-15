/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.Choreographer;
import android.view.MotionEvent;

import com.fingerprints.extension.FpcRequest;
import com.fingerprints.extension.util.BytesUtil;
import com.fingerprints.fpc.extension.IFpcExtension;

import jp.project2by2.fcntparts.Constants;

public class ExliderService extends AccessibilityService
        implements Choreographer.FrameCallback {

    private static final String TAG = ExliderService.class.getSimpleName();

    private static final float AXIS_MIN = 0f;
    private static final float AXIS_MAX = 16320f;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver mFingerStateReceiver;

    private IFpcExtension fpcExtService;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Do nothing
    }

    @Override
    protected void onServiceConnected() {
        Log.d(TAG, "onServiceConnected");
        super.onServiceConnected();

        // Catch the mouse events.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.setMotionEventSources(android.view.InputDevice.SOURCE_MOUSE);
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                   | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);

        // Init broadcast receiver.
        IntentFilter intentFilter = new IntentFilter(Constants.EXLIDER_ACTION_FINGER_EVENT);
        mFingerStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                final String stringExtra = intent.getStringExtra(Constants.EXLIDER_ACTION_FINGER_STATE);
                if (stringExtra.equals("down")) {
                    onFingerDown();
                }
                else if (stringExtra.equals("up")) {
                    onFingerUp();
                }
            }
        };
        registerReceiver(
            mFingerStateReceiver,
            intentFilter,
            /* broadcastPermission = */ null,
            /* scheduler = */ null,
            /* flags = */ Context.RECEIVER_EXPORTED
        );

        // Init IFpcExtension service.
        IBinder binder = ServiceManager.waitForService("com.fingerprints.fpc.extension.IFpcExtension/default");
        fpcExtService = IFpcExtension.Stub.asInterface(binder);
        setFpcNavigationEnabled(true);
        setFpcNavigationFrametime(32000);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Do nothing
    }

    @Override
    public void onMotionEvent(@NonNull MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL) return;

        // Get vertical wheel events
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        Log.d(TAG, "AXIS_VSCROLL: " + v + "f");
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        setFpcNavigationEnabled(false);
        super.onDestroy();
    }

    private void onFingerDown() {
        Log.d(TAG, "onFingerDown");
    }

    private void onFingerUp() {
        Log.d(TAG, "onFingerUp");
    }

    public boolean setFpcNavigationEnabled(boolean enabled) {
        Log.d(TAG, "setFpcNavigationEnabled: " + enabled);
        try {
            fpcExtService.request(FpcRequest.NAVIGATION_SET_NAVIGATION, BytesUtil.boolToBytes(enabled));
        } catch (Exception e) {
            Log.e(TAG, "Failed to transact with IFpcExtension", e);
            return false;
        }
        return true;
    }

    public boolean setFpcNavigationFrametime(int ms) {
        Log.d(TAG, "setFpcNavigationFrametime: " + ms);
        try {
            fpcExtService.request(FpcRequest.NAVIGATION_SET_NAVIGATION_FRAME_RATE, BytesUtil.intToBytes(ms));
        } catch (Exception e) {
            Log.e(TAG, "Failed to transact with IFpcExtension", e);
            return false;
        }
        return true;
    }

    public boolean getFpcNavigationEnabled() {
        byte[] enableInfo = new byte[1];
        try {
            fpcExtService.request(FpcRequest.NAVIGATION_IS_ENABLE, enableInfo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to transact with IFpcExtension", e);
            return false;
        }
        return enableInfo[0] == 1;
    }
}
