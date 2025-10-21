/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.actionkey;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.settingslib.widget.LayoutPreference;

import jp.project2by2.fcntparts.Constants;
import jp.project2by2.fcntparts.CustomSeekBarPreference;
import jp.project2by2.fcntparts.R;
import jp.project2by2.fcntparts.utils.TileUtils;

import java.util.Arrays;

public class ActionKeyFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private Context ctx;
    private Resources res;
    private ContentResolver cr;

    private ListPreference mShortPressPreference;
    private ListPreference mLongPressPreference;
    private CustomSeekBarPreference mDurationPreference;

    private String[] mActionEntries;
    private String[] mActionEntryValues;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        ctx = getContext();
        res = ctx.getResources();
        cr = ctx.getContentResolver();

        setPreferencesFromResource(R.xml.actionkey, rootKey);

        mShortPressPreference = (ListPreference) findPreference(Constants.KEY_ACTIONKEY_PERFORM_TYPE_SHORT);
        mShortPressPreference.setOnPreferenceChangeListener(this);

        mLongPressPreference = (ListPreference) findPreference(Constants.KEY_ACTIONKEY_PERFORM_TYPE_LONG);
        mLongPressPreference.setOnPreferenceChangeListener(this);

        mDurationPreference = (CustomSeekBarPreference) findPreference(Constants.KEY_ACTIONKEY_HOLD_DURATION);
        mDurationPreference.setOnPreferenceChangeListener(this);

        updatePreferences();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mShortPressPreference || preference == mLongPressPreference) {
            String s = (String) newValue;
            int v = Integer.parseInt(s);
            Settings.Secure.putInt(cr, preference.getKey(), v);
            return true;
        } else if (preference == mDurationPreference) {
            int v = (Integer) newValue;
            Settings.Secure.putInt(cr, preference.getKey(), v);
            return true;
        }
        return false;
    }

    private void updatePreferences() {
        int valueShortPressAction = Settings.Secure.getInt(cr, Constants.KEY_ACTIONKEY_PERFORM_TYPE_SHORT, 0);
        mShortPressPreference.setValue(Integer.toString(valueShortPressAction));

        int valueLongPressAction = Settings.Secure.getInt(cr, Constants.KEY_ACTIONKEY_PERFORM_TYPE_LONG, 1);
        mLongPressPreference.setValue(Integer.toString(valueLongPressAction));
    }

    public static boolean isActionKeySupported(Context context) {
        return context.getResources().getBoolean(R.bool.config_actionKeySupported);
    }
}
