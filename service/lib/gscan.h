
#include "wifi_hal.h"

#ifndef __WIFI_HAL_GSCAN_H__
#define __WIFI_HAL_GSCAN_H__

/* AP Scans */

typedef struct {
    wifi_timestamp ts;                  // Time of discovery
    char ssid[32];                      // May not be null terminated
    mac_addr bssid;
    wifi_channel channel;               // channel number (includes all bands)
    wifi_rssi rssi;                     // in db
    wifi_timespan rtt;                  // in nanoseconds
    wifi_timespan rtt_sd;               // standard deviation in rtt

    // other fields
} wifi_scan_result;

typedef struct {
    void (*on_scan_results) (wifi_request_id id, unsigned num_results, wifi_scan_result *results);
} IScanResultsHandler;

typedef struct {
    int num_channels;
    wifi_channel channels[];            // channels to scan; these may include DFS channels
    int single_shot;                    // boolean, 0 => repeated, 1 => single
    int frequency;                      // desired frequency, in scans per minute; if this is too
                                        // high, the firmware should choose to generate results as
                                        // fast as it can instead of failing the command
} wifi_scan_cmd_params;

wifi_error wifi_start_gscan(wifi_request_id id, wifi_interface_handle iface,
        wifi_scan_cmd_params params, IScanResultsHandler handler);
wifi_error wifi_stop_gscan(wifi_request_id id, wifi_interface_handle iface);

/*
 * Expect multiple scans to be active at the same time; the firmware is free to schedule scans
 * as it sees fit; as long as frequency requirements are met. This will allow us to have separate
 * schedules for DFS scans versus 1/6/11 scans.
 * If any channel is supplied multiple times, then the last specification wins.
 */

/* Background scan - it works with above API with single_shot set to '0' */

/* BSSID Hotlist */
typedef struct {
    void (*on_hotlist_ap_found)(wifi_request_id id, unsigned num_results, wifi_scan_result *results);
} wifi_hotlist_ap_found_handler;

wifi_error wifi_set_bssid_hotlist(wifi_request_id id, wifi_interface_handle iface,
        int num_bssid, mac_addr bssid[], wifi_hotlist_ap_found_handler handler);
wifi_error wifi_reset_bssid_hotlist(wifi_request_id id, wifi_interface_handle iface);

/* Significant wifi change*/
typedef struct {
    void (*on_significant_change)(wifi_request_id id, unsigned num_results, wifi_scan_result *results);
} wifi_significant_change_handler;

wifi_error wifi_set_significant_change_handler(wifi_request_id id, wifi_interface_handle iface,
        wifi_significant_change_handler handler);
wifi_error wifi_reset_significant_change_handler(wifi_request_id id, wifi_interface_handle iface);

#endif

