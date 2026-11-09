/*
 * Copyright (C) 2025 The 2by2 Project
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.project2by2.fcntparts.exlider;

import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.NoSuchElementException;

/**
 * Minimal client for FPC's HIDL navigation extension
 * (com.fingerprints.extension@1.0::IFingerprintNavigation), used by older
 * FPC HALs which do not expose the AIDL IFpcExtension service.
 *
 * Transaction codes follow the order of the methods in the HIDL interface.
 */
final class FpcNavigationHidl {

    static final String DESCRIPTOR = "com.fingerprints.extension@1.0::IFingerprintNavigation";
    private static final String INSTANCE = "default";

    private static final int TRANSACTION_SET_NAVIGATION_FRAMETIME = 1;
    private static final int TRANSACTION_GET_NAVIGATION_FRAMETIME = 2;
    private static final int TRANSACTION_SET_NAVIGATION = 3;
    private static final int TRANSACTION_IS_ENABLED = 6;

    private final IHwBinder mRemote;

    private FpcNavigationHidl(IHwBinder remote) {
        mRemote = remote;
    }

    /** Returns the service if it is registered, without retrying. */
    @Nullable
    static FpcNavigationHidl getService() {
        try {
            IHwBinder binder = HwBinder.getService(DESCRIPTOR, INSTANCE, /* retry= */ false);
            return binder != null ? new FpcNavigationHidl(binder) : null;
        } catch (RemoteException | NoSuchElementException e) {
            return null;
        }
    }

    void setNavigation(boolean enabled) throws RemoteException {
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(DESCRIPTOR);
        request.writeBool(enabled);
        transact(TRANSACTION_SET_NAVIGATION, request).release();
    }

    boolean isEnabled() throws RemoteException {
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(DESCRIPTOR);
        HwParcel reply = transact(TRANSACTION_IS_ENABLED, request);
        try {
            return reply.readBool();
        } finally {
            reply.release();
        }
    }

    void setNavigationFrametime(int frametime) throws RemoteException {
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(DESCRIPTOR);
        request.writeInt32(frametime);
        transact(TRANSACTION_SET_NAVIGATION_FRAMETIME, request).release();
    }

    int getNavigationFrametime() throws RemoteException {
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(DESCRIPTOR);
        HwParcel reply = transact(TRANSACTION_GET_NAVIGATION_FRAMETIME, request);
        try {
            return reply.readInt32();
        } finally {
            reply.release();
        }
    }

    private HwParcel transact(int code, HwParcel request) throws RemoteException {
        HwParcel reply = new HwParcel();
        try {
            mRemote.transact(code, request, reply, 0 /* flags */);
            reply.verifySuccess();
            request.releaseTemporaryStorage();
            return reply;
        } catch (RemoteException | RuntimeException e) {
            reply.release();
            throw e;
        }
    }
}
