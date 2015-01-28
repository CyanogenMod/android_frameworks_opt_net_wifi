package com.android.server.wifi.hotspot2;

/**
 * Match score for EAP credentials:
 * RealmOnly means that there is an empty set of EAP methods, i.e. only the realm matched.
 * Unqualified means that a matching EAP method was found but with empty auth parameters for either
 * EAP method.
 * Qualified means that an exact math of authentication parameters was found.
 * Note: Keep the literals in order of desired preference, i.e. Qualified supposedly last.
 */
public enum AuthMatch {
    None,
    RealmOnly,
    Unqualified,
    Qualified
}
