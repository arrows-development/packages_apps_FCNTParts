/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;

import com.fingerprints.extension.FpcRequest;
import com.fingerprints.extension.util.BytesUtil;
import com.fingerprints.fpc.extension.IFpcExtension;

import jp.project2by2.fcntparts.Constants;

import jp.project2by2.fcntparts.R;

public class ExliderService extends AccessibilityService
        implements Choreographer.FrameCallback {

    private static final String TAG = ExliderService.class.getSimpleName();

    private static final float AXIS_MIN = 0f;
    private static final int AXIS_MAX = 0x3FFF;

    private static final int DEFAULT_FRAME_TIME_MS = 32000;

    private long mScrollDuration;

    private int mFpcFrameTimeMs;

    private int mScrollDirection;
    private float mScrollScale;

    private float mScrollSpeed;

    private boolean mEnabled = false;
    private boolean mFingerDown = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private Context context;
    private ContentResolver contentResolver;
    private Resources resources;

    private BroadcastReceiver mFingerStateReceiver;

    private IFpcExtension fpcExtService;

    private volatile boolean mInitialized = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Do nothing
    }

    @Override
    protected void onServiceConnected() {
        Log.d(TAG, "onServiceConnected");
        super.onServiceConnected();

        context = this;
        contentResolver = context.getContentResolver();
        resources = context.getResources();

        // Init variables and configs.
        mScrollDirection = 0;
        mFingerDown = false;
        mScrollSpeed = Settings.Secure.getFloat(contentResolver, Constants.KEY_EXLIDER_SCROLL_SPEED, 10.0f);

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
        setFpcNavigationFrametime(DEFAULT_FRAME_TIME_MS);

        // Init completed
        Log.i(TAG, "Exlider service is ready");
        mInitialized = true;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Do nothing
    }

    @Override
    public void onMotionEvent(@NonNull MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL) return;

        // Do not scroll if not enabled
        if (!mEnabled) return;

        // Get vertical wheel events, and decode it
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        FpcPacket packet = decodePacked(v);
        if (packet == null) return;
        Log.d(TAG, "Pos: " + packet.pos + ", Accel: " + packet.accel);

        // When finger has moved
        if (mScrollDirection == 0 && packet.accel != 0.0f) {
            if (packet.accel > 0) {
                mScrollDirection = 1;  // Scroll to down
            } else if (packet.accel < 0) {
                mScrollDirection = -1;  // Scroll to up
            }

            // Define Scroll duration
            mScrollDuration = Math.max(1L, Math.round(mScrollSpeed * 10f));

            // Start scroll gestures
            mHandler.postDelayed(mScrollingRunnable, mScrollDuration / 2);

            // Stop long press toggle checks
            mHandler.removeCallbacks(mToggleEnabledRunnable);
        }
    }

    private final Runnable mScrollingRunnable = new Runnable() {
        @Override public void run() {
            // Dispatch the scroll gestures
            mScrollScale = mScrollDirection == 1 ? mScrollSpeed : 0 - mScrollSpeed;
            performScrollGesture(mScrollScale, mScrollDuration);

            // Continue gesture until onFingerUp
            if (mFingerDown) {
                mHandler.postDelayed(mScrollingRunnable, mScrollDuration / 2);
            }
        }
    };

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
        mFingerDown = true;

        // Long press for toggle enabled
        mHandler.postDelayed(mToggleEnabledRunnable, 750);
    }

    private void onFingerUp() {
        Log.d(TAG, "onFingerUp");
        mFingerDown = false;

        // Stop long press toggle checks
        mHandler.removeCallbacks(mToggleEnabledRunnable);

        // Stop scroll gestures
        mHandler.removeCallbacks(mScrollingRunnable);

        if (mEnabled && mScrollDirection != 0) {
            // Perform the small scroll for properly stop
            mScrollScale = mScrollDirection == 1 ? mScrollSpeed : 0 - mScrollSpeed;
            performScrollGesture(mScrollScale / 8, mScrollDuration / 8);

            // Reset direction
            mScrollDirection = 0;
        }
    }

    private final Runnable mToggleEnabledRunnable = new Runnable() {
        @Override public void run() {
            toggleEnabled();
        }
    };

    private void toggleEnabled() {
        if (!mInitialized) {
            Log.e(TAG, "Exlider not initialized yet!");
            return;
        }
        if (!mEnabled) {
            mEnabled = true;
            Toast.makeText(this, resources.getString(R.string.exlider_enabled_message), Toast.LENGTH_SHORT).show();
        } else {
            mEnabled = false;
            Toast.makeText(this, resources.getString(R.string.exlider_disabled_message), Toast.LENGTH_SHORT).show();
        }
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
            mFpcFrameTimeMs = ms;
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

    private void performScrollGesture(float scale, long duration) {
        if (scale == 0.0f || duration <= 0) {
            return;
        }

        final Point size = new Point();
        try {
            final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            final Display d = wm != null ? wm.getDefaultDisplay() : null;
            if (d != null) d.getRealSize(size);
        } catch (Throwable t) {
            // Ignore
        }
        if (size.x <= 0 || size.y <= 0) {
            try {
                size.x = getResources().getDisplayMetrics().widthPixels;
                size.y = getResources().getDisplayMetrics().heightPixels;
            } catch (Throwable t) {
                // Ignore
            }
            if (size.x <= 0) size.x = 1080;
            if (size.y <= 0) size.y = 2400;
        }

        final float w = size.x;
        final float h = size.y;
        if (h <= 0f || w <= 0f || !Float.isFinite(scale)) return;

        final float cx = w * 0.5f;
        final float cy = h * 0.5f;

        final float dy = -scale * 20f;
        float y1 = cy;
        float y2 = cy + dy;

        y1 = clampf(y1, 0.5f, h - 0.5f);
        y2 = clampf(y2, 0.5f, h - 0.5f);

        if (Math.abs(y2 - y1) < 1f) {
            y2 = clampf(y1 + (dy >= 0 ? 1f : -1f), 0.5f, h - 0.5f);
            if (Math.abs(y2 - y1) < 1f) {
                return;
            }
        }

        final Path p = new Path();
        p.moveTo(cx, y1);
        p.lineTo(cx, y2);

        final GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, duration));

        try {
            dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture failed", t);
        }
    }

    private static float clampf(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // Util for parse wheel event value
    private static final class FpcPacket {
        final int accel; // signed 16-bit
        final int pos;   // 0..16383
        FpcPacket(int accel, int pos) { this.accel = accel; this.pos = pos; }
    }
    @androidx.annotation.Nullable
    private FpcPacket decodePacked(float v) {
        final int packed = Math.round(v);
        final int lo = packed & 0xFFFF;
        final int hi = (packed >>> 16) & 0xFFFF;
        final int accel = (short) hi;
        int pos = lo & 0xFFFF;
        pos &= AXIS_MAX;
        return new FpcPacket(accel, pos);
    }
}
