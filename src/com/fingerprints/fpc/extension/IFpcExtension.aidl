package com.fingerprints.fpc.extension;

import com.fingerprints.fpc.extension.IFpcExtensionCallback;

interface IFpcExtension {

    int request(int i, inout byte[] bArr);

    int setCallback(IFpcExtensionCallback iFpcExtensionCallback);

}
