/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.MainSwitchPreference;

import jp.project2by2.fcntparts.R;

public class ExliderFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "Exlider";

    private MainSwitchPreference mExliderEnableSwitch;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.exlider, rootKey);

        mExliderEnableSwitch = (MainSwitchPreference) findPreference("exlider");
        mExliderEnableSwitch.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mExliderEnableSwitch) {
            boolean enabled = (Boolean) newValue;
            return true;
        }
        return false;
    }
}
