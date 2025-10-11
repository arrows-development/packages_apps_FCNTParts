/*
 * Copyright (C) 2023-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import jp.project2by2.fcntparts.utils.ComponentUtils;
import jp.project2by2.fcntparts.utils.FileUtils;

public class Startup extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
    }
}
