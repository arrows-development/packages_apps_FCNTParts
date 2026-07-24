package jp.project2by2.fcntparts.holdondisplay;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public class HoldOnDisplayActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().beginTransaction().replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                new HoldOnDisplayFragment(), "HoldOnDisplay").commit();
    }
}
