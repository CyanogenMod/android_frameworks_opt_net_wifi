package com.android.server.wifi.hotspot2.osu;

public enum RequestReason {
    SubRegistration,
    SubProvisioning,
    SubRemediation,
    InputComplete,
    NoClientCert,
    CertEnrollmentComplete,
    CertEnrollmentFailed,
    SubMetaDataUpdate,
    PolicyUpdate,
    NextCommand,
    MOUpload,
    Unspecified
}
