/*
 * Copyright (C) 2026 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.content.Context;
import android.util.AttributeSet;

import java.util.Locale;

import jp.project2by2.fcntparts.CustomSeekBarPreference;

/** A seek bar whose integer storage represents tenths of the Exlider speed. */
public class ExliderSpeedPreference extends CustomSeekBarPreference {

    public ExliderSpeedPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMinValue = 10;
        mMaxValue = 100;
        mInterval = 1;
        mDefaultValueExists = true;
        mDefaultValue = 50;
        mValue = 100;
    }

    @Override
    protected String getTextValue(int value) {
        return String.format(Locale.getDefault(), "%.1f", value / 10.0f);
    }
}
