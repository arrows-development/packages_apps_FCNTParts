/*
 * Copyright (C) 2026 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.fcnt.hardware.biometrics.fingerprint;

/** Interface exported by newer FCNT FPC fingerprint HALs for Exlider control. */
interface IFcntExlider {
    boolean setExliderStatus(boolean enabled);
    boolean getExliderStatus();
    boolean setExliderFrameInterval(int interval);
    int getExliderFrameInterval();
}
