/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.hardware.input.InputSettings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import com.fingerprints.extension.FpcRequest;
import com.fingerprints.extension.util.BytesUtil;
import com.fingerprints.fpc.extension.IFpcExtension;
import com.fcnt.hardware.biometrics.fingerprint.IFcntExlider;

import java.util.LinkedHashSet;
import java.util.Set;

import jp.project2by2.fcntparts.Constants;
import jp.project2by2.fcntparts.R;

/**
 * Exlider (fingerprint sensor slide) scroll service.
 *
 * <p>The FPC HAL reports one REL_WHEEL event per sensor frame, packed as
 * {@code (deltaY << 16) | detectZoneMask}. The stock FCNT framework decodes this
 * in a dedicated input mapper and feeds it to {@code ExliderScrollController},
 * which converts the sliding finger into a "gear" (stop / forward / backward)
 * and injects a small mouse wheel event on every display frame while a gear is
 * engaged. This service reproduces that pipeline without framework changes:
 *
 * <ul>
 *   <li>The generic CursorInputMapper applies mouse wheel acceleration (x1 or x4
 *       depending on the event interval) to the packed value. The exact scale is
 *       reconstructed from the event timestamps and divided out again, so every
 *       device sees the raw HAL units.</li>
 *   <li>The stock simple-scroll gear logic and long-tap state machine are ported.</li>
 *   <li>Scrolling is done by injecting one continuous touch drag, advanced once per
 *       vsync with a time-based speed. Unlike mouse wheel events, a touch drag moves
 *       every scrolling view by the same distance and drives nested scrolling as
 *       TYPE_TOUCH, which collapsing toolbars (e.g. SettingsLib's
 *       IgnoreNonTouchScrollBehavior) require. The drag always ends with
 *       ACTION_CANCEL, so it can never fling or click, and the touch slop is
 *       crossed within the first move.</li>
 * </ul>
 */
public class ExliderService extends AccessibilityService {

    private static final String TAG = ExliderService.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String FPC_EXTENSION_SERVICE =
            "com.fingerprints.fpc.extension.IFpcExtension/default";
    private static final String FCNT_EXLIDER_SERVICE =
            "com.fcnt.hardware.biometrics.fingerprint.IFcntExlider/default";
    private static final ComponentName EXLIDER_SERVICE = new ComponentName(
            "jp.project2by2.fcntparts",
            "jp.project2by2.fcntparts.exlider.ExliderService");

    // The HAL expresses its frame interval in microseconds (32 ms).
    private static final int DEFAULT_FRAME_INTERVAL_US = 32000;

    // --- Framework mouse wheel acceleration (frameworks/native VelocityControl) ---
    // VelocityTracker forgets its samples after this pause between movements.
    private static final long WHEEL_TRACKER_STOPPED_NS = 40_000_000L;
    // VelocityControl resets itself after this pause between movements.
    private static final long WHEEL_CONTROL_STOP_NS = 500_000_000L;
    // IInputConstants.DEFAULT_MOUSE_WHEEL_ACCELERATION
    private static final float WHEEL_ACCELERATION = 4f;
    // POINTER_SPEED_EXPONENT in com_android_server_input_InputManagerService.cpp
    private static final float WHEEL_SPEED_EXPONENT = 0.25f;

    // --- Stock ExliderScrollController constants ---
    private static final int DELTA_IGNORED = 1000;
    private static final int MAX_VALID_DELTA = 50;
    private static final int DETECT_ZONE_MASK = 0xFFF;
    private static final int MIN_DETECT_ZONES = 2;
    private static final long GEAR_IDLE_TIMEOUT_MS = 200;
    private static final int GEAR_ACTIVE_DELTA = 2;
    private static final long FPC_EVENT_TIMEOUT_MS = 100;

    // A lost finger-up key must not leave the contact (and scrolling) active.
    private static final long CONTACT_LOST_TIMEOUT_MS = 400;

    // --- Scroll output ---
    private static final float MIN_SCROLL_SPEED = 1.0f;
    private static final float MAX_SCROLL_SPEED = 10.0f;
    private static final float DEFAULT_SCROLL_SPEED = 5.0f;
    // Speed 5 roughly matches the stock default (0.07 wheel units per 144 Hz frame).
    private static final float SCROLL_DP_PER_SECOND_PER_SPEED = 128f;
    private static final int MAX_CATCH_UP_FRAMES = 3;
    private static final long DEFAULT_FRAME_INTERVAL_NS = 1_000_000_000L / 60;
    // The drag is re-gripped at the display centre before it gets this close
    // (fraction of the display height) to the top or bottom edge.
    private static final float STROKE_EDGE_MARGIN = 0.12f;

    private static final int STATE_INIT = 0;
    private static final int STATE_STANDBY = 1;
    private static final int STATE_COMPLETE = 2;
    private static final int STATE_PENDING = 3;

    // Gesture that toggles the scroll mode (Constants.KEY_EXLIDER_TOGGLE_GESTURE).
    private static final int TOGGLE_DOUBLE_TAP = 0;
    private static final int TOGGLE_LONG_TAP = 1;

    // Tunables, overridable per device by overlay.
    private long mLongTapTimeMs;
    private long mLongTapCancelTimeMs;
    private long mScrollModeTimeoutMs;
    private int mGearThreshStart;
    private int mGearThreshChange;
    private int mGearThreshIdle;
    private boolean mInvertDirection;
    private long mTapTimeoutMs;
    private long mDoubleTapTimeoutMs;
    private long mTapLiftGapMs;
    private int mTapSlop;

    private int mToggleGesture = TOGGLE_DOUBLE_TAP;

    private int mFpcFrameIntervalUs = DEFAULT_FRAME_INTERVAL_US;

    // Wheel acceleration reconstruction.
    private boolean mWheelAccelerationEnabled = true;
    private float mWheelBaseScale = 1f;
    private boolean mWheelReverseVertical = false;
    private long mWheelLastEventNs = Long.MIN_VALUE;
    private int mWheelSamples = 0;

    // Contact / state machine.
    private int mState = STATE_INIT;
    private boolean mPendingExit = false;
    private boolean mTouched = false;
    private boolean mContactPrepared = false;
    private long mFirstEventTime;
    private long mSecondEventTime;
    private long mThirdEventTime;
    private boolean mSkipLongTap;
    private long mLastFpcEventTime;

    // Tap detection (double tap toggle).
    private long mContactStartTime;
    private long mContactLastTime;
    private int mContactTravel;
    private boolean mContactMoved;
    private long mLastTapUpTime;

    // Gear (stock simple scroll).
    private int mCurrentGear;
    private int mTotalY;
    private int mGearThresh;
    private boolean mFirstTimeGearChange = true;
    private long mLastGearChangeTime;
    private boolean mIsGearChangeTimeout;

    // Scroll output.
    private float mScrollSpeed = DEFAULT_SCROLL_SPEED;
    private int mScrollGear = 0;
    private int mScrollDirection = ExliderIndicator.DIRECTION_NONE;
    private boolean mFramePosted = false;
    private long mLastFrameTimeNanos = 0;
    private float mPendingPixels = 0f;
    private float mTouchSlop;
    private float mStrokeX;
    private float mStrokeCenterY;
    private float mStrokeMinY;
    private float mStrokeMaxY;
    private float mStrokeY;
    private boolean mStrokeActive = false;
    private long mStrokeDownTime;

    private volatile boolean mInitialized = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Choreographer mChoreographer;

    private ContentResolver mContentResolver;
    private Resources mResources;
    private InputManager mInputManager;
    private KeyguardManager mKeyguardManager;
    private Vibrator mVibrator;

    private BroadcastReceiver mFingerStateReceiver;
    private BroadcastReceiver mScreenOffReceiver;

    private IFpcExtension fpcExtService;
    private IFcntExlider fcntExliderService;
    private FpcNavigationHidl fpcNavigationHidl;

    private ExliderIndicator mIndicator;

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            loadSettings();
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Do nothing
    }

    @Override
    protected void onServiceConnected() {
        Log.d(TAG, "onServiceConnected");
        super.onServiceConnected();

        mContentResolver = getContentResolver();
        mResources = getResources();
        mInputManager = getSystemService(InputManager.class);
        mKeyguardManager = getSystemService(KeyguardManager.class);
        mVibrator = getSystemService(Vibrator.class);
        mChoreographer = Choreographer.getInstance();
        mIndicator = new ExliderIndicator(this);

        loadConfig();
        loadSettings();
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Constants.KEY_EXLIDER_SCROLL_SPEED),
                false, mSettingsObserver);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Constants.KEY_EXLIDER_TOGGLE_GESTURE),
                false, mSettingsObserver);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.MOUSE_SCROLLING_ACCELERATION),
                false, mSettingsObserver);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.MOUSE_SCROLLING_SPEED),
                false, mSettingsObserver);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.MOUSE_REVERSE_VERTICAL_SCROLLING),
                false, mSettingsObserver);

        // Catch the mouse events.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.setMotionEventSources(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_TRACKBALL);
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                   | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);

        // Init broadcast receivers.
        mFingerStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                final String state = intent.getStringExtra(Constants.EXLIDER_ACTION_FINGER_STATE);
                if ("down".equals(state)) {
                    onFingerDown(SystemClock.uptimeMillis());
                } else if ("up".equals(state)) {
                    onFingerUp(SystemClock.uptimeMillis());
                }
            }
        };
        registerReceiver(
            mFingerStateReceiver,
            new IntentFilter(Constants.EXLIDER_ACTION_FINGER_EVENT),
            /* broadcastPermission = */ null,
            /* scheduler = */ null,
            /* flags = */ Context.RECEIVER_EXPORTED
        );
        mScreenOffReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                onScreenOff();
            }
        };
        registerReceiver(mScreenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_EXPORTED);

        // Do not use waitForService() here.  It attempts to start a missing lazy
        // service repeatedly, which blocks this service and spams servicemanager.
        // The extension must already be registered for Exlider to be available.
        if (!initializeFingerprintExtension()) {
            handleFpcExtensionUnavailable();
            return;
        }

        // Init completed
        Log.i(TAG, "Exlider service is ready");
        mInitialized = true;
    }

    @Override
    public void onMotionEvent(@NonNull MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_SCROLL) return;

        // Always decode first: the wheel acceleration model must see every event
        // the framework has seen.
        final long packed = decodePacked(event);
        if (!mInitialized) return;
        if (isKeyguardLocked()) {
            if (DEBUG) Log.d(TAG, "Skip exlider event while keyguard is locked");
            return;
        }

        int delta = (int) (packed >> 16);
        final int zone = (int) (packed & 0xFFFF);
        if (DEBUG) {
            Log.d(TAG, "ev t=" + event.getEventTime()
                    + " v=" + event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    + " n=" + mWheelSamples + " delta=" + delta
                    + " zone=" + Integer.toHexString(zone) + " state=" + mState
                    + " gear=" + mCurrentGear + " totalY=" + mTotalY);
        }
        if (fcntExliderService != null) {
            // FCNT's OEM service inverts rawDeltaY for this HAL.
            delta = -delta;
        }
        if (mInvertDirection) delta = -delta;

        final long eventTime = event.getEventTime();
        if (!mTouched) {
            if (!isValidDetectZone(zone)) {
                if (DEBUG) Log.d(TAG, "Discard event: not enough detect zones " + zone);
                return;
            }
            if (!mContactPrepared) prepareContact(eventTime);
            mTouched = true;
            if (DEBUG) Log.d(TAG, "Exlider touched");
        }
        mLastFpcEventTime = SystemClock.uptimeMillis();
        mContactLastTime = eventTime;
        mHandler.removeCallbacks(mContactLostRunnable);
        // The HAL reports a frame per frame interval while the finger rests on the
        // sensor, so a short gap already means the finger was lifted. Detecting that
        // quickly is required to tell the two taps of a double tap apart.
        mHandler.postDelayed(mContactLostRunnable, isDoubleTapToggle()
                ? mTapLiftGapMs : CONTACT_LOST_TIMEOUT_MS);

        if (delta == DELTA_IGNORED) delta = 0;
        if (Math.abs(delta) > MAX_VALID_DELTA) {
            if (DEBUG) Log.d(TAG, "Discard event: delta " + delta);
            return;
        }
        if (delta != 0) {
            mTotalY += delta;
            mContactTravel += delta;
            if (Math.abs(mContactTravel) > mTapSlop) mContactMoved = true;
        }

        processScrollModeEvent(eventTime, delta);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_BUTTON_THUMBL
                && keyCode != KeyEvent.KEYCODE_BUTTON_THUMBR) {
            return super.onKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                onFingerDown(event.getEventTime());
            } else {
                onFingerUp(event.getEventTime());
            }
        }
        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mIndicator != null) mIndicator.onConfigurationChanged();
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopScrolling();
        mHandler.removeCallbacksAndMessages(null);
        if (mIndicator != null) {
            mIndicator.destroy();
            mIndicator = null;
        }
        if (mFingerStateReceiver != null) {
            unregisterReceiver(mFingerStateReceiver);
            mFingerStateReceiver = null;
        }
        if (mScreenOffReceiver != null) {
            unregisterReceiver(mScreenOffReceiver);
            mScreenOffReceiver = null;
        }
        if (mContentResolver != null) {
            mContentResolver.unregisterContentObserver(mSettingsObserver);
        }
        if (fcntExliderService != null || fpcExtService != null || fpcNavigationHidl != null) {
            setFpcNavigationEnabled(false);
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // Packed value decoding
    // ---------------------------------------------------------------------

    /**
     * Returns the raw HAL value {@code (deltaY << 16) | detectZone} by undoing the
     * framework mouse wheel acceleration (see SimpleVelocityControl).
     */
    private long decodePacked(MotionEvent event) {
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (mWheelReverseVertical && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            v = -v;
        }

        final long t = event.getEventTimeNanos();
        if (mWheelLastEventNs == Long.MIN_VALUE || t - mWheelLastEventNs >= WHEEL_CONTROL_STOP_NS
                || t - mWheelLastEventNs > WHEEL_TRACKER_STOPPED_NS || t < mWheelLastEventNs) {
            mWheelSamples = 0;
        }
        mWheelLastEventNs = t;
        mWheelSamples++;

        // With a velocity estimate (2+ samples) the speed of the packed value is far
        // above the high threshold, so the full acceleration is applied.
        float scale = mWheelBaseScale;
        if (mWheelAccelerationEnabled && mWheelSamples >= 2) {
            scale *= WHEEL_ACCELERATION;
            // Sanity check: an accelerated value is always a multiple of 4.
            if (Math.abs(v) < (1 << 24) && Math.round(v) % 4 != 0) {
                if (DEBUG) Log.d(TAG, "Unexpected unaccelerated wheel value " + v);
                scale = mWheelBaseScale;
            }
        }
        return Math.round(v / scale);
    }

    private static boolean isValidDetectZone(int zone) {
        return Integer.bitCount(zone & DETECT_ZONE_MASK) >= MIN_DETECT_ZONES;
    }

    // ---------------------------------------------------------------------
    // Contact handling and state machine (stock ExliderScrollController)
    // ---------------------------------------------------------------------

    private void onFingerDown(long eventTime) {
        if (DEBUG) Log.d(TAG, "onFingerDown");
        if (mTouched || mContactPrepared) return;
        prepareContact(eventTime);
    }

    private void onFingerUp(long eventTime) {
        if (DEBUG) Log.d(TAG, "onFingerUp");
        if (mTouched || mContactPrepared) {
            mContactLastTime = Math.max(mContactLastTime, eventTime);
        }
        endContact();
    }

    private final Runnable mContactLostRunnable = () -> {
        if (isDoubleTapToggle()) {
            if (DEBUG) Log.d(TAG, "No exlider event for " + mTapLiftGapMs + "ms, finger up");
        } else {
            Log.w(TAG, "No exlider event for " + CONTACT_LOST_TIMEOUT_MS
                    + "ms, assume finger up");
        }
        endContact();
    };

    private boolean isDoubleTapToggle() {
        return mToggleGesture == TOGGLE_DOUBLE_TAP;
    }

    private void prepareContact(long eventTime) {
        mContactPrepared = true;
        mFirstEventTime = eventTime;
        mSecondEventTime = 0;
        mThirdEventTime = 0;
        mSkipLongTap = false;
        mFirstTimeGearChange = true;
        mContactStartTime = eventTime;
        mContactLastTime = eventTime;
        mContactTravel = 0;
        mContactMoved = false;
        clearGear();
        stopScrolling();
        mHandler.removeCallbacks(mScrollModeTimeoutRunnable);
    }

    private void endContact() {
        mHandler.removeCallbacks(mContactLostRunnable);
        final boolean wasActive = mTouched || mContactPrepared;
        final boolean wasTouched = mTouched;
        mTouched = false;
        mContactPrepared = false;
        stopScrolling();
        clearGear();
        if (!wasActive) return;

        if (isDoubleTapToggle()) {
            // A contact without a valid sensor frame (e.g. a key event only) is
            // not counted as a tap, but still breaks a pending double tap.
            if (wasTouched) {
                onContactEnded();
            } else {
                mLastTapUpTime = 0;
            }
        } else if (mState == STATE_STANDBY) {
            setState(STATE_COMPLETE);
            Log.i(TAG, "Scroll mode enabled");
            Toast.makeText(this, mResources.getString(R.string.exlider_enabled_message),
                    Toast.LENGTH_SHORT).show();
        } else if (mState == STATE_PENDING) {
            final boolean exit = mPendingExit;
            setState(STATE_INIT);
            if (exit) {
                Log.i(TAG, "Scroll mode disabled");
                Toast.makeText(this, mResources.getString(R.string.exlider_disabled_message),
                        Toast.LENGTH_SHORT).show();
            }
        }
        if (mState == STATE_COMPLETE && mScrollModeTimeoutMs > 0) {
            mHandler.removeCallbacks(mScrollModeTimeoutRunnable);
            mHandler.postDelayed(mScrollModeTimeoutRunnable, mScrollModeTimeoutMs);
        }
    }

    /** Detects a double tap from two consecutive short, still contacts. */
    private void onContactEnded() {
        final long duration = mContactLastTime - mContactStartTime;
        if (mContactMoved || duration > mTapTimeoutMs) {
            if (DEBUG) {
                Log.d(TAG, "Not a tap: moved=" + mContactMoved + " duration=" + duration);
            }
            mLastTapUpTime = 0;
            return;
        }
        final long interval = mContactStartTime - mLastTapUpTime;
        if (mLastTapUpTime != 0 && interval >= 0 && interval <= mDoubleTapTimeoutMs) {
            if (DEBUG) Log.d(TAG, "Double tap: duration=" + duration + " interval=" + interval);
            mLastTapUpTime = 0;
            toggleScrollMode();
            return;
        }
        if (DEBUG) Log.d(TAG, "Tap: duration=" + duration);
        mLastTapUpTime = mContactLastTime;
    }

    private void toggleScrollMode() {
        performHaptic();
        if (mState == STATE_COMPLETE) {
            mHandler.removeCallbacks(mScrollModeTimeoutRunnable);
            setState(STATE_INIT);
            Log.i(TAG, "Scroll mode disabled");
            Toast.makeText(this, mResources.getString(R.string.exlider_disabled_message),
                    Toast.LENGTH_SHORT).show();
        } else {
            setState(STATE_COMPLETE);
            Log.i(TAG, "Scroll mode enabled");
            Toast.makeText(this, mResources.getString(R.string.exlider_enabled_message),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final Runnable mScrollModeTimeoutRunnable = () -> {
        if (mState == STATE_COMPLETE && !mTouched) {
            Log.i(TAG, "Scroll mode disabled by timeout");
            setState(STATE_INIT);
        }
    };

    private void onScreenOff() {
        mHandler.removeCallbacks(mScrollModeTimeoutRunnable);
        mHandler.removeCallbacks(mContactLostRunnable);
        mTouched = false;
        mContactPrepared = false;
        mLastTapUpTime = 0;
        stopScrolling();
        clearGear();
        setState(STATE_INIT);
    }

    private void setState(int state) {
        if (DEBUG) Log.d(TAG, "State " + mState + " -> " + state);
        if (state != STATE_PENDING) mPendingExit = false;
        mState = state;
        if (mIndicator != null) {
            if (state == STATE_COMPLETE) {
                mIndicator.show();
            } else {
                mIndicator.hide();
            }
        }
    }

    private void processScrollModeEvent(long eventTime, int delta) {
        final int gear = updateGear(eventTime, delta);
        if (gear != 0) mContactMoved = true;

        if (isDoubleTapToggle()) {
            // The scroll mode is toggled by a double tap only (see onContactEnded()).
            if (mState != STATE_COMPLETE) return;
            if (gear == 0) {
                stopScrolling();
            } else {
                startScrolling(gear);
            }
            return;
        }

        if (mState == STATE_INIT) {
            if (gear != 0) {
                // The finger slid: this is not a long tap.
                mFirstEventTime = eventTime;
                return;
            }
            if (eventTime - mFirstEventTime < mLongTapTimeMs) return;
            if (DEBUG) Log.d(TAG, "Long tap, STANDBY");
            setState(STATE_STANDBY);
            performHaptic();
        }

        if (mState == STATE_STANDBY) {
            if (mSecondEventTime == 0 || gear != 0) mSecondEventTime = eventTime;
            if (eventTime - mSecondEventTime >= mLongTapCancelTimeMs) {
                if (DEBUG) Log.d(TAG, "Held too long in STANDBY, PENDING");
                setState(STATE_PENDING);
            }
        }

        if (mState != STATE_COMPLETE) return;

        if (gear == 0) {
            stopScrolling();
            if (mThirdEventTime == 0) mThirdEventTime = eventTime;
            if (mSkipLongTap || eventTime - mThirdEventTime < mLongTapCancelTimeMs) return;
            if (DEBUG) Log.d(TAG, "Long tap in COMPLETE, PENDING");
            setState(STATE_PENDING);
            mPendingExit = true;
            performHaptic();
            return;
        }

        mSkipLongTap = true;
        mThirdEventTime = 0;
        startScrolling(gear);
    }

    private void clearGear() {
        mCurrentGear = 0;
        mTotalY = 0;
        mLastGearChangeTime = 0;
        mIsGearChangeTimeout = false;
    }

    /** Port of the stock getGear() / getScaleAndNotifyGearChange() in simple mode. */
    private int updateGear(long now, int delta) {
        final int prev = mCurrentGear;
        if (mFirstTimeGearChange) {
            // Faster frame rates report smaller deltas per frame but more frames.
            mGearThresh = mFpcFrameIntervalUs <= 20000 ? mGearThreshStart * 2 : mGearThreshStart;
        }
        if (mLastGearChangeTime != 0 && now - mLastGearChangeTime >= GEAR_IDLE_TIMEOUT_MS) {
            mLastGearChangeTime = 0;
            if (!mIsGearChangeTimeout) {
                mTotalY = 0;
                mGearThresh = mGearThreshIdle;
                mIsGearChangeTimeout = true;
            }
            return prev;
        }

        int next = prev;
        if (mTotalY > mGearThresh) {
            mTotalY = 0;
            next = prev + 1;
        } else if (mTotalY < -mGearThresh) {
            mTotalY = 0;
            next = prev - 1;
        }

        if (prev != next || Math.abs(delta) > GEAR_ACTIVE_DELTA) {
            mLastGearChangeTime = now;
            mIsGearChangeTimeout = false;
        }
        if (prev != next) {
            mFirstTimeGearChange = false;
            mGearThresh = mGearThreshChange;
        }

        mCurrentGear = Math.max(-1, Math.min(1, next));
        if (DEBUG && prev != mCurrentGear) {
            Log.d(TAG, "Gear " + prev + " -> " + mCurrentGear + " delta=" + delta);
        }
        return mCurrentGear;
    }

    // ---------------------------------------------------------------------
    // Scroll output: one continuous touch drag, advanced once per display frame
    // ---------------------------------------------------------------------

    private void startScrolling(int gear) {
        if (gear == mScrollGear && mFramePosted) return;
        if (mScrollGear == 0) {
            // Pick the target only when a scroll starts, so the target stays stable.
            final Point size = getDisplaySize();
            mStrokeX = size.x * 0.5f;
            mStrokeCenterY = size.y * 0.5f;
            mStrokeMinY = size.y * STROKE_EDGE_MARGIN;
            mStrokeMaxY = size.y * (1f - STROKE_EDGE_MARGIN);
            mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            mLastFrameTimeNanos = 0;
            mPendingPixels = 0f;
        } else if (gear != mScrollGear) {
            mPendingPixels = 0f;
            cancelStroke(SystemClock.uptimeMillis());
        }
        mScrollGear = gear;
        // Positive gear drags the content up, i.e. scrolls towards the bottom of it.
        setScrollDirection(gear > 0
                ? ExliderIndicator.DIRECTION_DOWN : ExliderIndicator.DIRECTION_UP);
        if (!mFramePosted) {
            mFramePosted = true;
            mChoreographer.postFrameCallback(mFrameCallback);
        }
    }

    private void stopScrolling() {
        setScrollDirection(ExliderIndicator.DIRECTION_NONE);
        mScrollGear = 0;
        mPendingPixels = 0f;
        mLastFrameTimeNanos = 0;
        if (mFramePosted && mChoreographer != null) {
            mChoreographer.removeFrameCallback(mFrameCallback);
        }
        mFramePosted = false;
        mHandler.removeCallbacks(mInjectRunnable);
        cancelStroke(SystemClock.uptimeMillis());
    }

    /** Updates the indicator and gives haptic feedback when scrolling starts, turns or stops. */
    private void setScrollDirection(int direction) {
        if (direction == mScrollDirection) return;
        mScrollDirection = direction;
        if (mIndicator != null) mIndicator.setDirection(direction);
        performHaptic(direction == ExliderIndicator.DIRECTION_NONE
                ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK);
    }

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            mFramePosted = false;
            if (mScrollGear == 0 || !mTouched || mState != STATE_COMPLETE
                    || SystemClock.uptimeMillis() - mLastFpcEventTime > FPC_EVENT_TIMEOUT_MS) {
                stopScrolling();
                return;
            }

            long interval = mChoreographer.getFrameIntervalNanos();
            if (interval <= 0) interval = DEFAULT_FRAME_INTERVAL_NS;
            long frames = 1;
            if (mLastFrameTimeNanos != 0) {
                frames = Math.round((frameTimeNanos - mLastFrameTimeNanos) / (double) interval);
                frames = Math.max(1, Math.min(MAX_CATCH_UP_FRAMES, frames));
            }
            mLastFrameTimeNanos = frameTimeNanos;

            final float pixelsPerSecond = mScrollSpeed * SCROLL_DP_PER_SECOND_PER_SPEED
                    * mResources.getDisplayMetrics().density;
            mPendingPixels += pixelsPerSecond * interval * frames / 1e9f;

            mFramePosted = true;
            mChoreographer.postFrameCallback(this);

            // Inject in the middle of the frame, like the stock controller (4 ms after
            // vsync), so the target app consumes it well before its next frame.
            mHandler.removeCallbacks(mInjectRunnable);
            mHandler.postDelayed(mInjectRunnable, Math.max(1, interval / 2_000_000L));
        }
    };

    private final Runnable mInjectRunnable = () -> {
        if (mScrollGear == 0) return;
        final int pixels = (int) mPendingPixels;
        if (pixels <= 0) return;
        mPendingPixels -= pixels;

        // Positive gear scrolls the content forward, i.e. the finger moves up.
        final float direction = -mScrollGear;
        final long now = SystemClock.uptimeMillis();
        float distance = pixels;
        if (!mStrokeActive) {
            beginStroke(now);
            // Views subtract the touch slop from the move that exceeds it.
            distance += mTouchSlop;
        }
        float y = mStrokeY + direction * distance;
        if (y < mStrokeMinY || y > mStrokeMaxY) {
            // Re-grip at the centre within the same frame.
            cancelStroke(now);
            beginStroke(now);
            y = mStrokeY + direction * (pixels + mTouchSlop);
        }
        mStrokeY = y;
        injectTouch(MotionEvent.ACTION_MOVE, now);
    };

    private void beginStroke(long now) {
        mStrokeActive = true;
        mStrokeDownTime = now;
        mStrokeY = mStrokeCenterY;
        injectTouch(MotionEvent.ACTION_DOWN, now);
    }

    /** Ends the drag without a fling or a click. */
    private void cancelStroke(long now) {
        if (!mStrokeActive) return;
        injectTouch(MotionEvent.ACTION_CANCEL, now);
        mStrokeActive = false;
    }

    private void injectTouch(int action, long now) {
        final MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        final MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = mStrokeX;
        coords[0].y = mStrokeY;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        final MotionEvent ev = MotionEvent.obtain(mStrokeDownTime, now, action,
                1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        ev.setDisplayId(Display.DEFAULT_DISPLAY);
        try {
            mInputManager.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject touch event", e);
        } finally {
            ev.recycle();
        }
    }

    private void performHaptic() {
        performHaptic(VibrationEffect.EFFECT_CLICK);
    }

    private void performHaptic(int effect) {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;
        try {
            mVibrator.vibrate(VibrationEffect.createPredefined(effect),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        } catch (Exception e) {
            Log.w(TAG, "Failed to vibrate", e);
        }
    }

    private boolean isKeyguardLocked() {
        try {
            return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    private Point getDisplaySize() {
        final Point size = new Point();
        try {
            final WindowManager wm = getSystemService(WindowManager.class);
            final Display d = wm != null ? wm.getDefaultDisplay() : null;
            if (d != null) d.getRealSize(size);
        } catch (Throwable t) {
            // Ignore
        }
        if (size.x <= 0 || size.y <= 0) {
            size.x = mResources.getDisplayMetrics().widthPixels;
            size.y = mResources.getDisplayMetrics().heightPixels;
            if (size.x <= 0) size.x = 1080;
            if (size.y <= 0) size.y = 2400;
        }
        return size;
    }

    // ---------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------

    private void loadConfig() {
        mLongTapTimeMs = mResources.getInteger(R.integer.config_exliderLongTapTime);
        mLongTapCancelTimeMs = mResources.getInteger(R.integer.config_exliderLongTapCancelTime);
        mScrollModeTimeoutMs = mResources.getInteger(R.integer.config_exliderScrollModeTimeout);
        mGearThreshStart = mResources.getInteger(R.integer.config_exliderGearThresholdStart);
        mGearThreshChange = mResources.getInteger(R.integer.config_exliderGearThresholdChange);
        mGearThreshIdle = mResources.getInteger(R.integer.config_exliderGearThresholdIdle);
        mInvertDirection = mResources.getBoolean(R.bool.config_exliderInvertDirection);
        mTapTimeoutMs = mResources.getInteger(R.integer.config_exliderTapTimeout);
        mDoubleTapTimeoutMs = mResources.getInteger(R.integer.config_exliderDoubleTapTimeout);
        mTapLiftGapMs = mResources.getInteger(R.integer.config_exliderTapLiftGap);
        mTapSlop = mResources.getInteger(R.integer.config_exliderTapSlop);
        mGearThresh = mGearThreshStart;
    }

    private void loadSettings() {
        float speed = Settings.Secure.getFloat(
                mContentResolver, Constants.KEY_EXLIDER_SCROLL_SPEED, DEFAULT_SCROLL_SPEED);
        if (!Float.isFinite(speed)) speed = DEFAULT_SCROLL_SPEED;
        mScrollSpeed = Math.max(MIN_SCROLL_SPEED, Math.min(MAX_SCROLL_SPEED, speed));

        final int gesture = Settings.Secure.getInt(mContentResolver,
                Constants.KEY_EXLIDER_TOGGLE_GESTURE, TOGGLE_DOUBLE_TAP);
        if (gesture != mToggleGesture) {
            mToggleGesture = gesture;
            // Drop a half-finished gesture of the other kind.
            mLastTapUpTime = 0;
            if (mState != STATE_COMPLETE) setState(STATE_INIT);
        }

        try {
            mWheelAccelerationEnabled = InputSettings.isMouseScrollingAccelerationEnabled(this);
            final int wheelSpeed = Math.max(InputSettings.MIN_MOUSE_SCROLLING_SPEED,
                    Math.min(InputSettings.MAX_MOUSE_SCROLLING_SPEED,
                            InputSettings.getMouseScrollingSpeed(this)));
            mWheelBaseScale = mWheelAccelerationEnabled
                    ? 1f : (float) Math.pow(2, wheelSpeed * WHEEL_SPEED_EXPONENT);
            mWheelReverseVertical = InputSettings.isMouseReverseVerticalScrollingEnabled(this);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read mouse scrolling settings", e);
            mWheelAccelerationEnabled = true;
            mWheelBaseScale = 1f;
            mWheelReverseVertical = false;
        }
        Log.d(TAG, "Toggle gesture: " + mToggleGesture + ", scroll speed: " + mScrollSpeed
                + ", wheel acceleration: "
                + mWheelAccelerationEnabled + ", wheel scale: " + mWheelBaseScale
                + ", reverse: " + mWheelReverseVertical);
    }

    // ---------------------------------------------------------------------
    // Fingerprint HAL extension
    // ---------------------------------------------------------------------

    private void handleFpcExtensionUnavailable() {
        Log.e(TAG, "Cannot initialize fingerprint sensor extension");
        Toast.makeText(this, R.string.exlider_fingerprint_extension_error,
                Toast.LENGTH_LONG).show();
        disableExliderAccessibilityService();
    }

    /** Removes only Exlider from the enabled accessibility-service list. */
    private void disableExliderAccessibilityService() {
        Set<String> services = new LinkedHashSet<>();
        String setting = Settings.Secure.getString(
                mContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!TextUtils.isEmpty(setting)) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(setting);
            for (String service : splitter) {
                ComponentName component = ComponentName.unflattenFromString(service);
                if (!EXLIDER_SERVICE.equals(component) && !TextUtils.isEmpty(service)) {
                    services.add(service);
                }
            }
        }

        if (!Settings.Secure.putString(mContentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", services))) {
            Log.e(TAG, "Failed to disable Exlider accessibility service");
            return;
        }
        if (services.isEmpty()) {
            Settings.Secure.putInt(mContentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        }
    }

    public boolean setFpcNavigationEnabled(boolean enabled) {
        Log.d(TAG, "setFpcNavigationEnabled: " + enabled);
        if (fcntExliderService != null) {
            try {
                return fcntExliderService.setExliderStatus(enabled);
            } catch (Exception e) {
                Log.e(TAG, "Failed to transact with IFcntExlider", e);
                return false;
            }
        }
        if (fpcNavigationHidl != null) {
            try {
                fpcNavigationHidl.setNavigation(enabled);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to transact with IFingerprintNavigation", e);
                return false;
            }
        }
        if (fpcExtService == null) return false;
        try {
            fpcExtService.request(FpcRequest.NAVIGATION_SET_NAVIGATION, BytesUtil.boolToBytes(enabled));
        } catch (Exception e) {
            Log.e(TAG, "Failed to transact with IFpcExtension", e);
            return false;
        }
        return true;
    }

    public boolean setFpcNavigationFrametime(int intervalUs) {
        Log.d(TAG, "setFpcNavigationFrametime: " + intervalUs);
        if (fcntExliderService != null) {
            try {
                if (!fcntExliderService.setExliderFrameInterval(intervalUs)) return false;
                mFpcFrameIntervalUs = intervalUs;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to transact with IFcntExlider", e);
                return false;
            }
        }
        if (fpcNavigationHidl != null) {
            try {
                fpcNavigationHidl.setNavigationFrametime(intervalUs);
                mFpcFrameIntervalUs = intervalUs;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to transact with IFingerprintNavigation", e);
                return false;
            }
        }
        if (fpcExtService == null) return false;
        try {
            fpcExtService.request(FpcRequest.NAVIGATION_SET_NAVIGATION_FRAME_RATE,
                    BytesUtil.intToBytes(intervalUs));
            mFpcFrameIntervalUs = intervalUs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to transact with IFpcExtension", e);
            return false;
        }
        return true;
    }

    public boolean getFpcNavigationEnabled() {
        if (fcntExliderService != null) {
            try {
                return fcntExliderService.getExliderStatus();
            } catch (Exception e) {
                Log.e(TAG, "Failed to transact with IFcntExlider", e);
                return false;
            }
        }
        if (fpcNavigationHidl != null) {
            try {
                return fpcNavigationHidl.isEnabled();
            } catch (Exception e) {
                Log.e(TAG, "Failed to transact with IFingerprintNavigation", e);
                return false;
            }
        }
        if (fpcExtService == null) return false;
        byte[] enableInfo = new byte[1];
        try {
            fpcExtService.request(FpcRequest.NAVIGATION_IS_ENABLE, enableInfo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to transact with IFpcExtension", e);
            return false;
        }
        return enableInfo[0] == 1;
    }

    /**
     * Newer FCNT HALs expose a typed Exlider AIDL interface. Older devices use
     * FPC's generic request interface, so only probe it when the FCNT service
     * is not registered. Even older FPC HALs only provide the HIDL navigation
     * extension, which is probed last. All lookups are deliberately non-blocking.
     */
    private boolean initializeFingerprintExtension() {
        try {
            IBinder binder = ServiceManager.checkService(FCNT_EXLIDER_SERVICE);
            if (binder != null) {
                fcntExliderService = IFcntExlider.Stub.asInterface(binder);
                if (fcntExliderService == null) return false;
                Log.i(TAG, "Using IFcntExlider");
                return setFpcNavigationEnabled(true)
                        && setFpcNavigationFrametime(DEFAULT_FRAME_INTERVAL_US);
            }

            binder = ServiceManager.checkService(FPC_EXTENSION_SERVICE);
            if (binder != null) {
                fpcExtService = IFpcExtension.Stub.asInterface(binder);
                if (fpcExtService == null) return false;
                Log.i(TAG, "Using IFpcExtension");
                return setFpcNavigationEnabled(true)
                        && setFpcNavigationFrametime(DEFAULT_FRAME_INTERVAL_US);
            }

            fpcNavigationHidl = FpcNavigationHidl.getService();
            if (fpcNavigationHidl == null) return false;
            Log.i(TAG, "Using HIDL IFingerprintNavigation");
            // The unit of the HIDL frame time is not documented, so keep the
            // HAL default instead of overriding it.
            try {
                Log.i(TAG, "HIDL navigation frametime: "
                        + fpcNavigationHidl.getNavigationFrametime());
            } catch (Exception e) {
                Log.w(TAG, "Failed to get HIDL navigation frametime", e);
            }
            return setFpcNavigationEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize fingerprint sensor extension", e);
            return false;
        }
    }
}
