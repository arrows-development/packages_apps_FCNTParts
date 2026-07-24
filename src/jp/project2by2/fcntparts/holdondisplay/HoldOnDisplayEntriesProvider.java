/*
 * Copyright (C) 2026 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.holdondisplay;

import android.content.Context;
import android.provider.Settings;

import com.android.settingslib.drawer.CategoryKey;
import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.SwitchController;

import java.util.Collections;
import java.util.List;

import jp.project2by2.fcntparts.Constants;
import jp.project2by2.fcntparts.R;

/** Provides the HoldOnDisplay switch used by Settings' injected display preference. */
public class HoldOnDisplayEntriesProvider extends EntriesProvider {
    @Override
    protected List<? extends com.android.settingslib.drawer.EntryController>
            createEntryControllers() {
        return Collections.singletonList(new HoldOnDisplaySwitchController(getContext()));
    }

    private static final class HoldOnDisplaySwitchController extends SwitchController {
        private final Context mContext;

        HoldOnDisplaySwitchController(Context context) {
            mContext = context;
        }

        @Override
        public String getSwitchKey() {
            return Constants.KEY_HOLD_ON_DISPLAY_ENABLED;
        }

        @Override
        protected boolean isChecked() {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    Constants.KEY_HOLD_ON_DISPLAY_ENABLED, 0) != 0;
        }

        @Override
        protected boolean onCheckedChanged(boolean checked) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Constants.KEY_HOLD_ON_DISPLAY_ENABLED, checked ? 1 : 0);
            HoldOnDisplayService.updateState(mContext);
            return true;
        }

        @Override
        protected String getErrorMessage(boolean attemptedChecked) {
            return null;
        }

        @Override
        protected MetaData getMetaData() {
            final MetaData metaData = new MetaData(CategoryKey.CATEGORY_DISPLAY);
            metaData.setTitle(R.string.hold_on_display_title);
            metaData.setSummary(R.string.hold_on_display_summary);
            return metaData;
        }
    }
}
