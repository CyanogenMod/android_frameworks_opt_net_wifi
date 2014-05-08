#include <stdint.h>
#include "wifi_hal.h"

wifi_error wifi_initialize(wifi_handle *handle) {
    return WIFI_ERROR_NOT_SUPPORTED;
}

void wifi_cleanup(wifi_handle handle, wifi_cleaned_up_handler handler) {
}

void wifi_event_loop(wifi_handle handle) {

}

void wifi_get_error_info(wifi_error err, const char **msg) {
    *msg = NULL;
}

wifi_error wifi_get_supported_feature_set(wifi_handle handle, feature_set *set) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_concurrency_matrix(wifi_handle handle, int *size, feature_set **matrix) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* List of all supported channels, including 5GHz channels */
wifi_error wifi_get_supported_channels(wifi_handle handle, int *size, wifi_channel *list) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* Enhanced power reporting */
wifi_error wifi_is_epr_supported(wifi_handle handle) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* multiple interface support */
wifi_error wifi_get_ifaces(wifi_handle handle, int *num_ifaces, wifi_interface_handle **ifaces) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_iface_name(wifi_interface_handle iface, char *name, size_t size) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_iface_event_handler(wifi_request_id id,
            wifi_interface_handle iface, wifi_event_handler eh) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_reset_iface_event_handler(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_start_gscan(wifi_request_id id, wifi_interface_handle iface,
        wifi_scan_cmd_params params, wifi_scan_result_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_stop_gscan(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_cached_results(wifi_interface_handle iface, byte flush,
        wifi_scan_result_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_bssid_hotlist(wifi_request_id id, wifi_interface_handle iface,
        wifi_bssid_hotlist_params params, wifi_hotlist_ap_found_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_reset_bssid_hotlist(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_significant_change_handler(wifi_request_id id, wifi_interface_handle iface,
        wifi_significant_change_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_reset_significant_change_handler(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

