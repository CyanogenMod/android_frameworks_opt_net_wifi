package com.android.server.wifi.hotspot2;

/**
 * Match score for EAP credentials:
 * None means that there is a distinct mismatch, i.e. realm, method or parameter is defined
 * and mismatches that of the credential.
 * Indeterminate means that there is no ANQP information to match against.
 * RealmOnly means that there are realm names and one of them matched but there is an empty set of
 * EAP methods.
 * MethodOnly means that a matching EAP method was found but with empty auth parameters for either
 * EAP method.
 * Exact means that an exact math of authentication parameters was found.
 * Note: Keep the literals in order of desired preference, i.e. Qualified supposedly last.
 */
public enum AuthMatch {
    None,
    Indeterminate,
    RealmOnly,
    MethodOnly,
    Exact
}
