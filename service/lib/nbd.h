
#include "wifi_hal.h"

#ifndef __WIFI_HAL_NBD_H__
#define __WIFI_HAL_NBD_H__

/*****************************************************************************
 * NearBy Discovery
 *****************************************************************************/

#define NBD_MAC_ADDR_LEN 6
#define NBD_COUNTRY_STRING_LEN 3

typedef struct {
    u8 addr[NBD_MAC_ADDR_LEN];
    u8 channel;
    u8 role;
    u8 country_string[NBD_COUNTRY_STRING_LEN];
    u8 operating_class;
    u32 availability_bitmap;
} NbdWlanInfrastructureAttr;

typedef struct {
    u8 addr[NBD_MAC_ADDR_LEN];
    u8 channel;
    u8 role;
    u8 country_string[NBD_COUNTRY_STRING_LEN];
    u8 operating_class;
    u32 availability_bitmap;
} NbdP2pOperationAttr;

typedef struct {
    u8 addr[NBD_MAC_ADDR_LEN];
    u8 channel;
    u8 reserved;
    u8 country_string[NBD_COUNTRY_STRING_LEN];
    u8 operating_class;
    u32 availability_bitmap;
} NbdWlanIbssAttr;

typedef struct {
    u8 addr[NBD_MAC_ADDR_LEN];
    u8 channel;
    u8 reserved;
    u8 country_string[NBD_COUNTRY_STRING_LEN];
    u8 operating_class;
    u32 availability_bitmap;
    u8 tlvs[];
} NbdWlanMeshAttr;

typedef enum {
    NBD_RESPONSE_ENABLED,
    NBD_RESPONSE_PUBLISH,
    NBD_RESPONSE_PUBLISH_CANCEL,
    NBD_RESPONSE_PUBLISH_SSI,
    NBD_RESPONSE_SUBSCRIBE,
    NBD_RESPONSE_SUBSCRIBE_CANCEL,
    NBD_RESPONSE_SUBSCRIBE_FOLLOWUP,
    NBD_RESPONSE_STATS,
    NBD_RESPONSE_DISABLED
} NbdWlanRspType;

typedef struct {
    u16 version : 4;
    u16 message_id : 12;
    u16 message_length;
    u16 handle;
    u16 transaction_id;
} NbdWlanHeader;

typedef struct {

} NbdHeader;

typedef struct {
    NbdWlanHeader header;
    u16 status;
    u16 value;
    u8 *tlvs;
    u16 tlv_len;
} NbdWlanRspData;

typedef struct {
    NbdHeader header;
    u8 addr[NBD_MAC_ADDR_LEN];
} NbdPublishRepliedIndType;

typedef struct {
    NbdHeader header;
    u16 reason;
} NbdPublishTerminatedIndType;

typedef struct {
    NbdHeader header;
    u8 addr[NBD_MAC_ADDR_LEN];
    u8 *tlvs;
    u16 tlv_len;
} NbdPublishFollowupIndType;

typedef struct {
    NbdHeader header;
    u16 match_handle;
    u8 addr[NBD_MAC_ADDR_LEN];
    u8 *tlvs;
    u16 tlv_len;
} NbdSubscribeMatchIndType;

typedef struct {
    NbdHeader header;
    u16 match_handle;
} NbdSubscribeUnmatchIndType;

typedef struct {
    NbdHeader header;
    u16 reason;
} NbdSubscribeTerminatedIndType;

typedef struct {
    NbdHeader header;
    u16 match_handle;
    u8 *tlvs;
    u16 tlv_len;
} NbdSubscribeSsiIndType;

typedef struct {
    NbdHeader header;
    u8 event_id;
    u8 *tlvs;
    u16 tlv_len;
} NbdDeEventIndType;

typedef struct {
    NbdHeader header;
    u16 reason;
} NbdDisableIndType;

/* Response and Event Callbacks */
typedef struct {
    /* NotifyResponse invoked to notify the status of the Request */
    void (*NotifyResponse)(NbdWlanRspType rsp_type, NbdWlanRspData* rsp_data);
    /* Various Event Callback */
    void (*EventPublishReplied)(NbdPublishRepliedIndType* event_data);
    void (*EventPublishTerminated)(NbdPublishTerminatedIndType* event_data);
    void (*EventPublishFollowup) (NbdPublishFollowupIndType* event_data);
    void (*EventSubscribeMatch) (NbdSubscribeMatchIndType* event_data);
    /* void (*EventSubscribeUnMatch) (NbdSubscribeUnMatchIndType* event_data); */
    void (*EventSubscribeTerminated) (NbdSubscribeTerminatedIndType* event_data);
    void (*EventSubscribeSSI) (NbdSubscribeSsiIndType* event_data);
    void (*EventNbdDeEvent) (NbdDeEventIndType* event_data);
    void (*EventNbdDisabled) (NbdDisableIndType* event_data);
} NbdCallbackHandler;

/* Enable NBD in driver*/
wifi_error wifi_nbd_enable(
        wifi_request_id id,
        wifi_interface_handle handle,
        u8 support_5g,
        u16 cluster_low,
        u16 cluster_high,
        u8 sid,
        u8 sync_disc_5g,
        u8 rssi_close,
        u8 rssi_med,
        u8 hc_limit,
        u8 random_update_time,
        u8 master_pref,
        u8 periodic_scan_interval,
        NbdWlanInfrastructureAttr* wlan_infra,
        NbdP2pOperationAttr* p2p_oper,
        NbdWlanIbssAttr* wlan_ibss,
        NbdWlanMeshAttr* wlan_mesh,
        size_t wlan_mesh_len,
        NbdCallbackHandler handler
        /* TODO : Add support for Google Specific IE */
        );

/* Disable NBD request*/
wifi_error wifi_nbd_disable(
        wifi_request_id id);

/* Cluster discovery/joining */
typedef struct {
    void (*NotifyClusterDiscovery)(wifi_request_id id, mac_addr addr);
    void (*NotifyClusterLoss)(wifi_request_id id);
} NbdClusterDiscoveryHandler;

wifi_error wifi_nbd_discover(
        wifi_interface_handle handle,
        NbdClusterDiscoveryHandler handler,
        int single_shot
        );

wifi_error wifi_nbd_start_or_join_cluster(
        wifi_interface_handle handle,
        int preference,                     // Local Master preference
        byte token[64],                     // token to publish in discovery beacon
        int recommended_hop_count,          // hop count to publish in the discovery beacon
        int maximum_rssi);                  // maximum_rssi to publish in the discovery beacon

/* Send NBD Publish request */
wifi_error wifi_nbd_publish(
        wifi_request_id id,
        wifi_interface_handle handle,
        u32 ttl,
        u32 period,
        u32 replied_event,
        u32 count,
        u32 publish_type,
        u32 tx_type,
        char *service_name,
        size_t service_name_len,
        char *rx_match_filter,
        size_t rx_match_filter_len,
        char *tx_match_filter,
        size_t tx_match_filter_len,
        char *service_specific_info,
        size_t service_specific_info_len,
        char *group_key,
        size_t group_key_len);

/* Cancel NBD Publish request */
wifi_error wifi_nbd_publish_cancel(
        wifi_request_id id);

/* Send NBD Publish Service Specific Info Request */
wifi_error wifi_nbd_publish_ssi(
        wifi_request_id id,
        wifi_interface_handle handle,
        u32 priority, // should we make this frequency?
        char *addr,
        char *service_name,
        size_t service_name_len,
        char *tx_match_filter,
        size_t tx_match_filter_len,
        char *service_specific_info,
        size_t service_specific_info_len);

/* Send NBD subscribe request */
wifi_error wifi_nbd_subscribe(
        wifi_request_id id,
        wifi_interface_handle handle,
        u32 subscribe_type,
        u32 period,
        u32 ttl,
        u32 count,
        u32 srf,
        u32 srfa,
        u32 srfi,
        u32 ssin,
        u32 match,
        char* service_name,
        size_t service_name_len,
        char* rx_match_filter,
        size_t rx_match_filter_len,
        char *tx_match_filter,
        size_t tx_match_filter_len,
        char *service_specific_info,
        size_t service_specific_info_len,
        char *group_key,
        size_t group_key_len);

/* Cancel NBD subscribe request*/
wifi_error wifi_nbd_subscribe_cancel(
        wifi_request_id id);

/* Send NBD subscribe followup request*/
wifi_error wifi_nbd_subscribe_followup(
        wifi_request_id id,
        u16 match_handle);

/* Get NBD Stat */
wifi_error wifi_nbd_stats(
        wifi_request_id id,
        u8 stats_id);

#endif