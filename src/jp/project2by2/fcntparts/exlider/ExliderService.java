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
import android.database.ContentObserver;
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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

import com.fingerprints.extension.FpcRequest;
import com.fingerprints.extension.util.BytesUtil;
import com.fingerprints.fpc.extension.IFpcExtension;

import jp.project2by2.fcntparts.Constants;

import jp.project2by2.fcntparts.R;

public class ExliderService extends AccessibilityService {

    private static final String TAG = ExliderService.class.getSimpleName();

    private static final int AXIS_MAX = 0x3FFF;

    private static final int DEFAULT_FRAME_TIME_MS = 32000;
    private static final long MIN_SCROLL_DURATION_MS = 32;
    private static final long MAX_SCROLL_DURATION_MS = 100;
    private static final long TOGGLE_DELAY_MS = 700;
    private static final long POSITION_DETECTION_DELAY_MS = 100;
    private static final int MIN_SCROLL_ACCEL = 32;
    private static final int POSITION_DELTA_THRESHOLD = AXIS_MAX / 8;
    private static final int DIRECTION_START_THRESHOLD = 64;
    private static final int RETURN_DEAD_ZONE = 32;
    private static final int MAX_RELATIVE_TRAVEL = 1024;
    private static final float MIN_SCROLL_SPEED = 1.0f;
    private static final float MAX_SCROLL_SPEED = 10.0f;
    private static final float DEFAULT_SCROLL_SPEED = 5.0f;

    // Keep the existing 20 px / 100 ms / scale speed while aligning gesture
    // updates with the stock FPC navigation frame (32 ms).
    private static final float SCROLL_PIXELS_PER_MS_PER_SCALE = 0.2f;

    private int mFpcFrameTimeMs;

    private int mScrollDirection;
    private int mInitialAxisPos;
    private int mLastAxisPos;
    private int mRelativeTravel;
    private float mScrollScale;
    private long mFingerDownTime;

    private float mScrollSpeed;

    private boolean mEnabled = false;
    private boolean mFingerDown = false;
    private boolean mHasScrolled = false;
    private boolean mScrolling = false;
    private boolean mGestureInProgress = false;

    private GestureDescription.StrokeDescription mContinuedStroke;
    private float mStrokeX;
    private float mStrokeY;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mScrollSpeedObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            loadScrollSpeed();
        }
    };

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
        loadScrollSpeed();
        contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Constants.KEY_EXLIDER_SCROLL_SPEED),
                false, mScrollSpeedObserver);
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
                if ("down".equals(stringExtra)) {
                    onFingerDown();
                }
                else if ("up".equals(stringExtra)) {
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
    public void onMotionEvent(@NonNull MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL) return;

        // Get vertical wheel events, and decode it
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        FpcPacket packet = decodePacked(v);
        if (packet == null) return;
        Log.d(TAG, "Pos: " + packet.pos + ", Accel: " + packet.accel);

        if (!mFingerDown) return;

        final int previousAxisPos = mLastAxisPos;
        final int positionDelta = previousAxisPos < 0
                ? 0 : circularAxisDistance(packet.pos, previousAxisPos);
        mLastAxisPos = packet.pos;

        final long now = SystemClock.uptimeMillis();
        final boolean accelMovement = Math.abs(packet.accel) >= MIN_SCROLL_ACCEL;
        final boolean positionMovement =
                now - mFingerDownTime >= POSITION_DETECTION_DELAY_MS
                && positionDelta >= POSITION_DELTA_THRESHOLD;
        if (!accelMovement && !positionMovement) return;

        // Once this contact has moved, it can never toggle the mode. This also
        // handles upward motion where this FPC firmware sometimes reports zero
        // acceleration while its absolute position moves to an axis edge.
        mHasScrolled = true;
        mHandler.removeCallbacks(mToggleEnabledRunnable);
        if (!mEnabled) return;

        if (mInitialAxisPos < 0) {
            mInitialAxisPos = previousAxisPos >= 0 ? previousAxisPos : packet.pos;
            Log.d(TAG, "Initial axis position: " + mInitialAxisPos);
        }

        // Accumulate travel relative to the position at which the finger first
        // started moving. Acceleration is the reliable delta on this firmware;
        // a large circular axis delta is retained as a zero-acceleration fallback.
        final int travelDelta = accelMovement
                ? packet.accel
                : Integer.signum(circularSignedAxisDelta(packet.pos, previousAxisPos))
                        * MIN_SCROLL_ACCEL;
        mRelativeTravel = Math.max(-MAX_RELATIVE_TRAVEL,
                Math.min(MAX_RELATIVE_TRAVEL, mRelativeTravel + travelDelta));

        // Returning to the recorded origin brakes the active scroll. Crossing
        // beyond the origin's dead zone is required before the opposite direction
        // can start, avoiding an abrupt reversal at the centre position.
        if (mScrolling && (Math.abs(mRelativeTravel) <= RETURN_DEAD_ZONE
                || Integer.signum(mRelativeTravel) != mScrollDirection)) {
            Log.d(TAG, "Stopping scroll at initial position: travel=" + mRelativeTravel);
            stopScrolling();
            return;
        }

        if (mScrolling || Math.abs(mRelativeTravel) < DIRECTION_START_THRESHOLD) return;

        mScrollDirection = Integer.signum(mRelativeTravel);
        mScrollScale = mScrollDirection * mScrollSpeed;
        mScrolling = true;
        Log.d(TAG, "Starting relative scroll: direction=" + mScrollDirection
                + ", travel=" + mRelativeTravel + ", scale=" + mScrollScale);
        dispatchNextScrollSegment();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.onKeyEvent(event);
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            Log.d(TAG, "Finger down key event");
            onFingerDown();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            Log.d(TAG, "Finger up key event");
            onFingerUp();
            return true;
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mHandler.removeCallbacksAndMessages(null);
        if (mFingerStateReceiver != null) {
            unregisterReceiver(mFingerStateReceiver);
            mFingerStateReceiver = null;
        }
        if (contentResolver != null) {
            contentResolver.unregisterContentObserver(mScrollSpeedObserver);
        }
        setFpcNavigationEnabled(false);
        super.onDestroy();
    }

    private void onFingerDown() {
        Log.d(TAG, "onFingerDown");
        if (mFingerDown) return;
        mFingerDown = true;
        mHasScrolled = false;
        mFingerDownTime = SystemClock.uptimeMillis();
        mInitialAxisPos = -1;
        mLastAxisPos = -1;
        mRelativeTravel = 0;
        mScrollDirection = 0;
        mScrollScale = 0f;
        mScrolling = false;

        // Long press for toggle enabled
        mHandler.postDelayed(mToggleEnabledRunnable, TOGGLE_DELAY_MS);
    }

    private void onFingerUp() {
        Log.d(TAG, "onFingerUp");
        if (!mFingerDown) return;
        mFingerDown = false;

        // Stop long press toggle checks
        mHandler.removeCallbacks(mToggleEnabledRunnable);

        stopScrolling();
    }

    private final Runnable mToggleEnabledRunnable = new Runnable() {
        @Override public void run() {
            if (mFingerDown && !mHasScrolled && !mScrolling) {
                toggleEnabled();
            }
        }
    };

    private void toggleEnabled() {
        if (!mInitialized) {
            Log.e(TAG, "Exlider not initialized yet!");
            return;
        }
        if (!mEnabled) {
            mEnabled = true;
            Log.i(TAG, "Scroll mode enabled");
            Toast.makeText(this, resources.getString(R.string.exlider_enabled_message), Toast.LENGTH_SHORT).show();
        } else {
            mEnabled = false;
            stopScrolling();
            Log.i(TAG, "Scroll mode disabled");
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

    private void loadScrollSpeed() {
        float speed = Settings.Secure.getFloat(
                contentResolver, Constants.KEY_EXLIDER_SCROLL_SPEED, DEFAULT_SCROLL_SPEED);
        if (!Float.isFinite(speed)) speed = DEFAULT_SCROLL_SPEED;
        mScrollSpeed = clampf(speed, MIN_SCROLL_SPEED, MAX_SCROLL_SPEED);
        Log.d(TAG, "Scroll speed: " + mScrollSpeed);
    }

    private Point getDisplaySize() {
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
        return size;
    }

    private void dispatchNextScrollSegment() {
        if (mGestureInProgress || !mScrolling || !mEnabled || !mFingerDown
                || mScrollDirection == 0 || mScrollScale == 0f) {
            return;
        }

        final Point size = getDisplaySize();

        final float w = size.x;
        final float h = size.y;
        if (h <= 0f || w <= 0f || !Float.isFinite(mScrollScale)) {
            stopScrolling();
            return;
        }

        if (mContinuedStroke == null) {
            mStrokeX = w * 0.5f;
            mStrokeY = h * 0.5f;
        }

        final float minimumDistance = ViewConfiguration.get(this).getScaledTouchSlop() * 2f;
        final float pixelsPerMs = Math.abs(mScrollScale)
                * SCROLL_PIXELS_PER_MS_PER_SCALE;
        final long duration = Math.max(MIN_SCROLL_DURATION_MS,
                Math.min(MAX_SCROLL_DURATION_MS,
                        (long) Math.ceil((minimumDistance + 1f) / pixelsPerMs)));
        final float dy = -mScrollScale * SCROLL_PIXELS_PER_MS_PER_SCALE * duration;
        if (Math.abs(dy) <= minimumDistance) {
            stopScrolling();
            return;
        }

        final float nextY = clampf(mStrokeY + dy, 0.5f, h - 0.5f);
        if (Math.abs(nextY - mStrokeY) <= minimumDistance) {
            finishContinuedGesture();
            return;
        }

        final Path p = new Path();
        p.moveTo(mStrokeX, mStrokeY);
        p.lineTo(mStrokeX, nextY);

        final GestureDescription.StrokeDescription nextStroke;
        if (mContinuedStroke == null) {
            nextStroke = new GestureDescription.StrokeDescription(
                    p, 0, duration, true);
        } else {
            nextStroke = mContinuedStroke.continueStroke(
                    p, 0, duration, true);
        }
        mContinuedStroke = nextStroke;
        mStrokeY = nextY;

        final GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(nextStroke);

        try {
            mGestureInProgress = dispatchGesture(
                    b.build(), mScrollGestureCallback, mHandler);
            if (!mGestureInProgress) {
                resetGestureState();
                mScrolling = false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture failed", t);
            resetGestureState();
            mScrolling = false;
        }
    }

    private void finishContinuedGesture() {
        if (mContinuedStroke == null || mGestureInProgress) return;

        final Path p = new Path();
        p.moveTo(mStrokeX, mStrokeY);
        final GestureDescription.StrokeDescription endingStroke =
                mContinuedStroke.continueStroke(p, 0, 1, false);
        mContinuedStroke = null;

        final GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(endingStroke);
        try {
            mGestureInProgress = dispatchGesture(
                    b.build(), mScrollGestureCallback, mHandler);
            if (!mGestureInProgress) resetGestureState();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to finish continued gesture", t);
            resetGestureState();
        }
    }

    private void stopScrolling() {
        mScrolling = false;
        mScrollDirection = 0;
        mScrollScale = 0f;
        if (!mGestureInProgress) finishContinuedGesture();
    }

    private void resetGestureState() {
        mGestureInProgress = false;
        mContinuedStroke = null;
    }

    private final GestureResultCallback mScrollGestureCallback = new GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            mGestureInProgress = false;
            if (mScrolling && mEnabled && mFingerDown) {
                dispatchNextScrollSegment();
            } else {
                finishContinuedGesture();
            }
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            resetGestureState();
            if (mScrolling && mEnabled && mFingerDown) {
                dispatchNextScrollSegment();
            }
        }
    };

    private static float clampf(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int circularAxisDistance(int a, int b) {
        final int range = AXIS_MAX + 1;
        final int difference = Math.abs(a - b);
        return Math.min(difference, range - difference);
    }

    private static int circularSignedAxisDelta(int current, int previous) {
        final int range = AXIS_MAX + 1;
        final int halfRange = range / 2;
        int difference = current - previous;
        if (difference > halfRange) difference -= range;
        if (difference < -halfRange) difference += range;
        return difference;
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
