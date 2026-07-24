package jp.project2by2.fcntparts.holdondisplay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.app.KeyguardManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import jp.project2by2.fcntparts.Constants;

public class HoldOnDisplayService extends Service implements SensorEventListener {
    private static final String TAG = "FCNTHoldOnDisplay";
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;
    private boolean mSensorRegistered;
    private boolean mHeld;

    private final Runnable mSensorRegistrationRetry = this::registerSensor;

    public static void updateState(Context context) {
        Intent intent = new Intent(context, HoldOnDisplayService.class);
        boolean enabled = Settings.Secure.getInt(context.getContentResolver(),
                Constants.KEY_HOLD_ON_DISPLAY_ENABLED, 0) != 0;
        if (enabled) {
            context.startService(intent);
        } else {
            context.stopService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSensorManager = getSystemService(SensorManager.class);
        mPowerManager = getSystemService(PowerManager.class);
        mKeyguardManager = getSystemService(KeyguardManager.class);
        mHandler = new Handler(Looper.getMainLooper());
        mWakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG);
        mWakeLock.setReferenceCounted(false);
        registerSensor();
    }

    private void registerSensor() {
        if (mSensorRegistered) return;

        mSensor = mSensorManager.getDefaultSensor(
                Constants.FCNT_HOLD_ON_DISPLAY_SENSOR_TYPE, true);
        if (mSensor == null || !mSensorManager.registerListener(
                this, mSensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            Log.w(TAG, "HoldOnDisplay sensor is not ready; retrying");
            mHandler.removeCallbacks(mSensorRegistrationRetry);
            mHandler.postDelayed(mSensorRegistrationRetry, 1000);
            return;
        }
        mSensorRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Do not wake or keep the display on from movement while the display is
        // off or the device is still locked.
        if (!mPowerManager.isInteractive() || mKeyguardManager.isKeyguardLocked()) {
            mHeld = false;
            if (mWakeLock.isHeld()) mWakeLock.release();
            return;
        }

        // FCNT HAL: 1 = held and 0 = released.
        final boolean held = event.values.length > 0 && event.values[0] >= 0.5f;
        if (held == mHeld) return;
        mHeld = held;

        if (held) {
            // ACQUIRE_CAUSES_WAKEUP is not sufficient while the device is dozing on
            // newer PowerManager implementations. Explicitly leave doze on the
            // transition, then keep the display bright with the wake lock.
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_GESTURE, TAG);
            if (!mWakeLock.isHeld()) mWakeLock.acquire();
        } else if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        if (mHandler != null) mHandler.removeCallbacks(mSensorRegistrationRetry);
        if (mSensorManager != null && mSensorRegistered) {
            mSensorManager.unregisterListener(this);
        }
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
