#include <stdint.h>
#include "wifi_hal.h"
#include "wifi_hal_stub.h"

wifi_error wifi_initialize_stub(wifi_handle *handle) {
    return WIFI_ERROR_NOT_SUPPORTED;
}

void wifi_cleanup_stub(wifi_handle handle, wifi_cleaned_up_handler handler) {
}

void wifi_event_loop_stub(wifi_handle handle) {

}

void wifi_get_error_info_stub(wifi_error err, const char **msg) {
    *msg = NULL;
}

wifi_error wifi_get_supported_feature_set_stub(wifi_interface_handle handle, feature_set *set) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_concurrency_matrix_stub(wifi_interface_handle handle, int max_size,
        feature_set *matrix, int *size) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_scanning_mac_oui_stub(wifi_interface_handle handle, unsigned char *oui) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* List of all supported channels, including 5GHz channels */
wifi_error wifi_get_supported_channels_stub(wifi_handle handle, int *size, wifi_channel *list) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* Enhanced power reporting */
wifi_error wifi_is_epr_supported_stub(wifi_handle handle) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* multiple interface support */
wifi_error wifi_get_ifaces_stub(wifi_handle handle, int *num_ifaces, wifi_interface_handle **ifaces) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_iface_name_stub(wifi_interface_handle iface, char *name, size_t size) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_iface_event_handler_stub(wifi_request_id id,
            wifi_interface_handle iface, wifi_event_handler eh) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_reset_iface_event_handler_stub(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_start_gscan_stub(wifi_request_id id, wifi_interface_handle iface,
        wifi_scan_cmd_params params, wifi_scan_result_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_stop_gscan_stub(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_cached_gscan_results_stub(wifi_interface_handle iface, byte flush,
        int max, wifi_cached_scan_results *results, int *num) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_bssid_hotlist_stub(wifi_request_id id, wifi_interface_handle iface,
        wifi_bssid_hotlist_params params, wifi_hotlist_ap_found_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_reset_bssid_hotlist_stub(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_significant_change_handler_stub(wifi_request_id id, wifi_interface_handle iface,
        wifi_significant_change_params params, wifi_significant_change_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_reset_significant_change_handler_stub(wifi_request_id id, wifi_interface_handle iface) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_gscan_capabilities_stub(wifi_interface_handle handle,
        wifi_gscan_capabilities *capabilities) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_set_link_stats_stub(wifi_interface_handle iface, wifi_link_layer_params params) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_link_stats_stub(wifi_request_id id,
        wifi_interface_handle iface, wifi_stats_result_handler handler) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_clear_link_stats_stub(wifi_interface_handle iface,
      u32 stats_clear_req_mask, u32 *stats_clear_rsp_mask, u8 stop_req, u8 *stop_rsp) {
    return WIFI_ERROR_UNINITIALIZED;
}

wifi_error wifi_get_valid_channels_stub(wifi_interface_handle handle,
        int band, int max_channels, wifi_channel *channels, int *num_channels) {
    return WIFI_ERROR_UNINITIALIZED;
}

/* API to request RTT measurement */
wifi_error wifi_rtt_range_request_stub(wifi_request_id id, wifi_interface_handle iface,
        unsigned num_rtt_config, wifi_rtt_config rtt_config[], wifi_rtt_event_handler handler) {
    return WIFI_ERROR_NOT_SUPPORTED;
}

/* API to cancel RTT measurements */
wifi_error wifi_rtt_range_cancel_stub(wifi_request_id id,  wifi_interface_handle iface,
        unsigned num_devices, mac_addr addr[]) {
    return WIFI_ERROR_NOT_SUPPORTED;
}

/* API to get RTT capability */
wifi_error wifi_get_rtt_capabilities_stub(wifi_interface_handle iface,
        wifi_rtt_capabilities *capabilities)
{
    return WIFI_ERROR_NOT_SUPPORTED;
}

wifi_error wifi_set_nodfs_flag_stub(wifi_interface_handle iface, u32 nodfs) {
    return WIFI_ERROR_NOT_SUPPORTED;
}

wifi_error wifi_start_logging_stub(wifi_interface_handle iface, u32 verbose_level, u32 flags,
        u32 max_interval_sec, u32 min_data_size, u8 *buffer_name,
        wifi_ring_buffer_data_handler handler) {
            return WIFI_ERROR_NOT_SUPPORTED;
}

wifi_error wifi_set_epno_list_stub(int id, wifi_interface_info *iface, int num_networks,
        wifi_epno_network *networks, wifi_epno_handler handler) {
    return WIFI_ERROR_NOT_SUPPORTED;
}

wifi_error wifi_set_country_code_stub(wifi_interface_handle iface, const char *code) {
    return WIFI_ERROR_NOT_SUPPORTED;
}
