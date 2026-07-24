package jp.project2by2.fcntparts.holdondisplay;

import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.PreferenceFragmentCompat;

import com.android.settingslib.widget.MainSwitchPreference;

import jp.project2by2.fcntparts.Constants;
import jp.project2by2.fcntparts.R;

public class HoldOnDisplayFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.hold_on_display, rootKey);
        MainSwitchPreference preference = findPreference(Constants.KEY_HOLD_ON_DISPLAY_ENABLED);
        if (preference != null) {
            preference.setChecked(Settings.Secure.getInt(requireContext().getContentResolver(),
                    Constants.KEY_HOLD_ON_DISPLAY_ENABLED, 0) != 0);
            preference.setOnPreferenceChangeListener((p, value) -> {
                Settings.Secure.putInt(requireContext().getContentResolver(),
                        Constants.KEY_HOLD_ON_DISPLAY_ENABLED, (Boolean) value ? 1 : 0);
                HoldOnDisplayService.updateState(requireContext());
                return true;
            });
        }
    }
}
