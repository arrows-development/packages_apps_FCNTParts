/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import java.util.List;

import jp.project2by2.fcntparts.R;

/**
 * Scroll mode indicator: a bubble next to the fingerprint sensor.
 *
 * <p>While scrolling, the bubble shrinks and hides its icon, and an arrow head
 * comes out of it in the scroll direction, growing on its way out. The arrow is
 * pulled back into the bubble when scrolling stops or turns.
 *
 * <p>The window is an accessibility overlay: it needs no extra permission and
 * is a trusted overlay, so the touch stream injected for scrolling is never
 * blocked by untrusted touch occlusion. It is not touchable either.
 */
final class ExliderIndicator {

    private static final String TAG = "ExliderIndicator";

    static final int DIRECTION_NONE = 0;
    static final int DIRECTION_UP = 1;
    static final int DIRECTION_DOWN = -1;

    // Sizes in dp.
    private static final float BUBBLE_RADIUS_DP = 22f;
    private static final float ICON_SIZE_DP = 26f;
    private static final float EDGE_MARGIN_DP = 10f;
    // The arrow head is about as large as the idle bubble.
    private static final float ARROW_RADIUS_DP = 17f;
    private static final float ARROW_GAP_DP = 6f;
    // Bubble size while scrolling, relative to the idle bubble.
    private static final float SCROLLING_BUBBLE_SCALE = 0.5f;
    private static final float SHADOW_RADIUS_DP = 4f;
    private static final float WINDOW_PADDING_DP = 8f;
    // Half the length of SystemUI's side fingerprint indicator (sfps_pulse, 80dp).
    private static final float SFPS_INDICATOR_HALF_LENGTH_DP = 40f;

    private static final long SHOW_DURATION_MS = 350;
    private static final long HIDE_DURATION_MS = 200;
    private static final long ARROW_OUT_DURATION_MS = 400;
    private static final long ARROW_IN_DURATION_MS = 160;

    // Material 3 motion.
    private static final Interpolator EMPHASIZED_ACCELERATE =
            new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    private static final Interpolator EMPHASIZED_DECELERATE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IndicatorView mView;
    private final int mWindowWidth;
    private final int mWindowHeight;

    private boolean mAttached;
    private boolean mShowing;

    /** @param context the accessibility service, which owns the overlay window token */
    ExliderIndicator(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mView = new IndicatorView(context);
        // Leave room for the arrow head tip and its rounded corners.
        final float reach = mView.arrowDistance(mView.mBubbleRadius * SCROLLING_BUBBLE_SCALE)
                + mView.mArrowRadius * 1.4f;
        mWindowWidth = Math.round(2 * (Math.max(mView.mBubbleRadius, mView.mArrowRadius * 1.1f)
                + mView.mPadding));
        mWindowHeight = Math.round(2 * (reach + mView.mPadding));
    }

    void show() {
        if (mShowing) return;
        mShowing = true;
        updateColors();
        if (!mAttached) {
            try {
                mWindowManager.addView(mView, createLayoutParams());
                mAttached = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to add indicator window", e);
                mShowing = false;
                return;
            }
        } else {
            updateLayout();
        }
        mView.animateShow(true, null);
    }

    void hide() {
        if (!mShowing) return;
        mShowing = false;
        mView.animateShow(false, () -> {
            if (!mShowing) removeView();
        });
    }

    void setDirection(int direction) {
        if (mShowing) mView.setDirection(direction);
    }

    void onConfigurationChanged() {
        if (!mAttached) return;
        updateColors();
        updateLayout();
    }

    void destroy() {
        mShowing = false;
        mView.reset();
        removeView();
    }

    private void removeView() {
        if (!mAttached) return;
        try {
            mWindowManager.removeViewImmediate(mView);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to remove indicator window", e);
        }
        mAttached = false;
        mView.reset();
    }

    private void updateColors() {
        final boolean night = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mView.setColors(
                mContext.getColor(night ? android.R.color.system_primary_dark
                        : android.R.color.system_primary_light),
                mContext.getColor(night ? android.R.color.system_on_primary_dark
                        : android.R.color.system_on_primary_light));
    }

    private void updateLayout() {
        try {
            mWindowManager.updateViewLayout(mView, createLayoutParams());
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to update indicator window", e);
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mWindowWidth, mWindowHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle(TAG);

        final Rect bounds = mWindowManager.getMaximumWindowMetrics().getBounds();
        final float[] center = computeBubbleCenter(bounds.width(), bounds.height());
        lp.x = Math.round(center[0] - mWindowWidth / 2f);
        lp.y = Math.round(center[1] - mWindowHeight / 2f);
        return lp;
    }

    /**
     * Places the bubble next to the side fingerprint sensor, interpreting its
     * location like SystemUI's SideFpsSensorInteractor: a non-zero Y means the
     * sensor sits on the right edge in the natural orientation, starting at Y,
     * otherwise it sits on the top edge starting at X.
     *
     * <p>The bubble is centred where SystemUI centres its side fingerprint
     * indicator: SideFpsOverlayViewModel puts the indicator window at the sensor
     * start and the indicator (sfps_pulse) is 80dp long, so its centre is 40dp
     * past the sensor start. The sensor radius is not used for that.
     */
    private float[] computeBubbleCenter(int screenW, int screenH) {
        final Display display = mContext.getDisplay();
        final int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        final boolean natural = rotation == Surface.ROTATION_0
                || rotation == Surface.ROTATION_180;
        final int naturalW = natural ? screenW : screenH;
        final int naturalH = natural ? screenH : screenW;

        // Sensor centre in the natural orientation.
        float nx = naturalW;
        float ny = naturalH / 2f;
        final SensorLocationInternal location = getSensorLocation(display);
        if (location != null && location.sensorLocationY != 0) {
            nx = naturalW;
            ny = location.sensorLocationY + dp(SFPS_INDICATOR_HALF_LENGTH_DP);
        } else if (location != null && location.sensorLocationX != 0) {
            nx = location.sensorLocationX + dp(SFPS_INDICATOR_HALF_LENGTH_DP);
            ny = 0;
        }

        float x;
        float y;
        switch (rotation) {
            case Surface.ROTATION_90:
                x = ny;
                y = naturalW - nx;
                break;
            case Surface.ROTATION_180:
                x = naturalW - nx;
                y = naturalH - ny;
                break;
            case Surface.ROTATION_270:
                x = naturalH - ny;
                y = nx;
                break;
            default:
                x = nx;
                y = ny;
                break;
        }

        // Move inwards from the nearest edge.
        final float inset = mView.mBubbleRadius + dp(EDGE_MARGIN_DP);
        final float toLeft = x;
        final float toRight = screenW - x;
        final float toTop = y;
        final float toBottom = screenH - y;
        final float nearest = Math.min(Math.min(toLeft, toRight), Math.min(toTop, toBottom));
        if (nearest == toRight) {
            x = screenW - inset;
        } else if (nearest == toLeft) {
            x = inset;
        } else if (nearest == toTop) {
            y = inset;
        } else {
            y = screenH - inset;
        }

        // Keep the whole window, including the arrows, on screen.
        x = clamp(x, mWindowWidth / 2f, screenW - mWindowWidth / 2f);
        y = clamp(y, mWindowHeight / 2f, screenH - mWindowHeight / 2f);
        return new float[] { x, y };
    }

    private SensorLocationInternal getSensorLocation(Display display) {
        final FingerprintManager fpm = mContext.getSystemService(FingerprintManager.class);
        if (fpm == null) return null;
        final List<FingerprintSensorPropertiesInternal> props;
        try {
            props = fpm.getSensorPropertiesInternal();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to get fingerprint sensor properties", e);
            return null;
        }
        if (props == null) return null;
        for (FingerprintSensorPropertiesInternal prop : props) {
            if (!prop.isAnySidefpsType()) continue;
            SensorLocationInternal location =
                    display != null ? prop.getLocation(display.getUniqueId()) : null;
            if (location == null) location = prop.getLocation("");
            if (location != null) return location;
        }
        return null;
    }

    private float dp(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                mContext.getResources().getDisplayMetrics());
    }

    private static float clamp(float v, float min, float max) {
        return max < min ? (min + max) / 2f : Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class IndicatorView extends View {

        final float mBubbleRadius;
        final float mArrowRadius;
        final float mArrowGap;
        final float mPadding;
        private final float mIconSize;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrowPath = new Path();
        private final Drawable mIcon;

        private float mShowProgress;
        private float mArrowProgress;
        private float mShrinkProgress;
        private int mArrowDirection = DIRECTION_NONE;
        private int mTargetDirection = DIRECTION_NONE;

        private ValueAnimator mShowAnimator;
        private ValueAnimator mArrowAnimator;
        private ValueAnimator mShrinkAnimator;

        IndicatorView(Context context) {
            super(context);
            final float density = context.getResources().getDisplayMetrics().density;
            mBubbleRadius = BUBBLE_RADIUS_DP * density;
            mArrowRadius = ARROW_RADIUS_DP * density;
            mArrowGap = ARROW_GAP_DP * density;
            mPadding = WINDOW_PADDING_DP * density;
            mIconSize = ICON_SIZE_DP * density;

            mShadowPaint.setShadowLayer(SHADOW_RADIUS_DP * density, 0f, density, 0x40000000);
            // Rounded corners for the arrow head.
            mArrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mArrowPaint.setStrokeJoin(Paint.Join.ROUND);
            mIcon = context.getDrawable(R.drawable.ic_exlider_scroll).mutate();
        }

        /** Distance between the bubble and the arrow head centres once the arrow is out. */
        float arrowDistance(float bubbleRadius) {
            return bubbleRadius + mArrowGap + mArrowRadius;
        }

        void setColors(int primary, int onPrimary) {
            mPaint.setColor(primary);
            mShadowPaint.setColor(primary);
            mArrowPaint.setColor(primary);
            mIcon.setTint(onPrimary);
            invalidate();
        }

        void animateShow(boolean show, Runnable endAction) {
            if (mShowAnimator != null) mShowAnimator.cancel();
            if (!show) setDirection(DIRECTION_NONE);
            final ValueAnimator animator = ValueAnimator.ofFloat(mShowProgress, show ? 1f : 0f);
            animator.setDuration(show ? SHOW_DURATION_MS : HIDE_DURATION_MS);
            animator.setInterpolator(show ? new OvershootInterpolator(1.5f)
                    : EMPHASIZED_ACCELERATE);
            animator.addUpdateListener(a -> {
                mShowProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new EndListener(() -> {
                mShowAnimator = null;
                if (endAction != null) endAction.run();
            }));
            mShowAnimator = animator;
            animator.start();
        }

        void setDirection(int direction) {
            if (direction == mTargetDirection) return;
            final boolean wasScrolling = mTargetDirection != DIRECTION_NONE;
            mTargetDirection = direction;
            if (wasScrolling != (direction != DIRECTION_NONE)) {
                animateShrink(direction != DIRECTION_NONE ? 1f : 0f);
            }
            if (mArrowDirection == DIRECTION_NONE) {
                if (direction != DIRECTION_NONE) {
                    mArrowDirection = direction;
                    animateArrow(1f);
                }
            } else if (direction == mArrowDirection) {
                animateArrow(1f);
            } else {
                // Pull the arrow back in first; a new one comes out afterwards.
                animateArrow(0f);
            }
        }

        void reset() {
            if (mShowAnimator != null) mShowAnimator.cancel();
            if (mArrowAnimator != null) mArrowAnimator.cancel();
            if (mShrinkAnimator != null) mShrinkAnimator.cancel();
            mShowAnimator = null;
            mArrowAnimator = null;
            mShrinkAnimator = null;
            mShowProgress = 0f;
            mArrowProgress = 0f;
            mShrinkProgress = 0f;
            mArrowDirection = DIRECTION_NONE;
            mTargetDirection = DIRECTION_NONE;
        }

        private void animateShrink(float target) {
            if (mShrinkAnimator != null) mShrinkAnimator.cancel();
            // Same timing as the arrow, so that the bubble and the arrow move in step.
            final boolean shrink = target > mShrinkProgress;
            final ValueAnimator animator = ValueAnimator.ofFloat(mShrinkProgress, target);
            animator.setDuration(shrink ? ARROW_OUT_DURATION_MS : ARROW_IN_DURATION_MS);
            animator.setInterpolator(shrink ? EMPHASIZED_DECELERATE : EMPHASIZED_ACCELERATE);
            animator.addUpdateListener(a -> {
                mShrinkProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new EndListener(() -> mShrinkAnimator = null));
            mShrinkAnimator = animator;
            animator.start();
        }

        private void animateArrow(float target) {
            if (mArrowAnimator != null) mArrowAnimator.cancel();
            final boolean out = target > mArrowProgress;
            final ValueAnimator animator = ValueAnimator.ofFloat(mArrowProgress, target);
            animator.setDuration(out ? ARROW_OUT_DURATION_MS : ARROW_IN_DURATION_MS);
            // No overshoot: the arrow must not move back once it is out.
            animator.setInterpolator(out ? EMPHASIZED_DECELERATE : EMPHASIZED_ACCELERATE);
            animator.addUpdateListener(a -> {
                mArrowProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new EndListener(() -> {
                mArrowAnimator = null;
                if (target == 0f) {
                    mArrowDirection = mTargetDirection;
                    if (mArrowDirection != DIRECTION_NONE) animateArrow(1f);
                }
            }));
            mArrowAnimator = animator;
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mShowProgress <= 0f) return;
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float scale = Math.max(0f, mShowProgress);
            final float bubbleRadius = mBubbleRadius * scale
                    * lerp(1f, SCROLLING_BUBBLE_SCALE, mShrinkProgress);
            final int alpha = Math.round(255 * clamp01(mShowProgress * 1.5f));

            // The arrow head comes out from behind the bubble.
            drawArrow(canvas, cx, cy, scale, alpha);

            mShadowPaint.setAlpha(alpha);
            mPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, bubbleRadius, mShadowPaint);
            canvas.drawCircle(cx, cy, bubbleRadius, mPaint);

            // The icon fades out quickly while the bubble shrinks for scrolling.
            final float iconAlpha = clamp01(1f - mShrinkProgress * 2f);
            if (iconAlpha > 0f) {
                final float half = mIconSize * scale
                        * lerp(1f, SCROLLING_BUBBLE_SCALE, mShrinkProgress) / 2f;
                mIcon.setBounds(Math.round(cx - half), Math.round(cy - half),
                        Math.round(cx + half), Math.round(cy + half));
                mIcon.setAlpha(Math.round(alpha * iconAlpha));
                mIcon.draw(canvas);
            }
        }

        /**
         * The arrow head (pointed tip, notched back, rounded corners) comes out of
         * the bubble centre, growing from 0% to 100% on its way to its final position.
         */
        private void drawArrow(Canvas canvas, float cx, float cy, float scale, int alpha) {
            final float p = mArrowProgress;
            if (mArrowDirection == DIRECTION_NONE || p <= 0f) return;
            // Screen "up" is negative y.
            final float sign = mArrowDirection == DIRECTION_UP ? -1f : 1f;
            final float r = mArrowRadius * scale;
            final float ax = cx;
            // The final position depends on the fully shrunk bubble only, so the arrow
            // stays put while the bubble is still shrinking.
            final float ay = cy + sign * arrowDistance(mBubbleRadius * SCROLLING_BUBBLE_SCALE)
                    * scale * p;

            mArrowPath.reset();
            mArrowPath.moveTo(ax, ay + sign * 1.15f * r);             // tip
            mArrowPath.lineTo(ax + 0.95f * r, ay - sign * 0.75f * r); // right wing
            mArrowPath.lineTo(ax, ay - sign * 0.3f * r);              // notch
            mArrowPath.lineTo(ax - 0.95f * r, ay - sign * 0.75f * r); // left wing
            mArrowPath.close();
            mArrowPaint.setStrokeWidth(0.35f * r);
            mArrowPaint.setAlpha(alpha);

            canvas.save();
            canvas.scale(p, p, ax, ay);
            canvas.drawPath(mArrowPath, mArrowPaint);
            canvas.restore();
        }
    }

    private static final class EndListener extends AnimatorListenerAdapter {
        private final Runnable mAction;
        private boolean mCancelled;

        EndListener(Runnable action) {
            mAction = action;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mCancelled = true;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (!mCancelled) mAction.run();
        }
    }
}
