
#include <stdint.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netlink/genl/genl.h>
#include <netlink/genl/family.h>
#include <netlink/genl/ctrl.h>
#include <linux/rtnetlink.h>
#include <netpacket/packet.h>
#include <linux/filter.h>
#include <linux/errqueue.h>

#include <linux/pkt_sched.h>
#include <netlink/object-api.h>
#include <netlink/netlink.h>
#include <netlink/socket.h>
#include <netlink-types.h>

#include <linux/nl80211.h>

#include "sync.h"

#define LOG_TAG  "WifiHAL"

#include <utils/Log.h>

#include "wifi_hal.h"
#include "common.h"
#include "cpp_bindings.h"

/* TODO: define vendor subcommands */
typedef enum {

    GSCAN_SUBCMD_START_GSCAN = ANDROID_NL80211_SUBCMD_GSCAN_RANGE_START,
    GSCAN_SUBCMD_STOP_GSCAN,
    GSCAN_SUBCMD_GSCAN_RESULTS,

    GSCAN_SUBCMD_SET_HOTLIST,
    GSCAN_SUBCMD_HOTLIST_RESULTS,

    GSCAN_SUBCMD_SET_SIGNIFICANT_CHANGE_MONITOR,
    GSCAN_SUBCMD_SIGNIFICANT_CHANGE_RESULTS,

    /* Add more sub commands here */

    GSCAN_SUBCMD_MAX

} GSCAN_SUB_COMMAND;

typedef enum {
    GSCAN_ATTRIBUTE_SCAN_CHANNELS = 10,
    GSCAN_ATTRIBUTE_SCAN_SINGLE_SHOT,
    GSCAN_ATTRIBUTE_SCAN_FREQUENCY,

    /* remaining reserved for additional attributes */

    GSCAN_ATTRIBUTE_HOTLIST_BSSIDS = 20,

    /* remaining reserved for additional attributes */

    GSCAN_ATTRIBUTE_SIGNIFICANT_CHANGE_ENABLE = 30,


    GSCAN_ATTRIBUTE_MAX

} GSCAN_ATTRIBUTE;

/////////////////////////////////////////////////////////////////////////////


class ScanCommand : public WifiCommand
{
private:
    static const unsigned int MAX_CHANNELS = 64;
    int mChannels[MAX_CHANNELS];
    int mNumChannels;
    IScanResultsHandler mHandler;
    static const unsigned int MAX_RESULTS = 64;
    wifi_scan_result mResults[MAX_RESULTS];
public:
    ScanCommand(wifi_handle handle, int id, int *channels, unsigned n,
                IScanResultsHandler handler)
        : WifiCommand(handle, id), mHandler(handler)
    {
        mNumChannels = n > MAX_CHANNELS ? MAX_CHANNELS : n;
        for (int i = 0; i < mNumChannels; i++) {
            mChannels[i] = channels[i];
        }
    }

    virtual int create() {
        int ret = mMsg.create(NL80211_CMD_START_SCHED_SCAN, 0, 0);
        if (ret < 0) {
            return ret;
        }
        struct nlattr * attr = mMsg.attr_start(NL80211_ATTR_SCAN_FREQUENCIES);
        for (int i = 0; i < mNumChannels; i++) {
            ret = mMsg.put_u32(i + 1, mChannels[i]);
            if (ret < 0) {
                return ret;
            }
        }
        mMsg.attr_end(attr);
        mMsg.put_u32(NL80211_ATTR_SCAN_FLAGS, NL80211_SCAN_FLAG_FLUSH);
        return ret;
    }

    int start() {
        return requestEvent(NL80211_CMD_SCHED_SCAN_RESULTS);
    }

    virtual int cancel() {
        /* TODO: send another command to the driver to cancel the scan */
        wifi_unregister_handler(mInfo, NL80211_CMD_SCHED_SCAN_RESULTS);
        return WIFI_SUCCESS;
    }

    virtual int handleResponse(WifiEvent reply) {
        /* Nothing to do on response! */
        return NL_SKIP;
    }

    virtual int handleEvent(WifiEvent event) {
        ALOGI("Got a scan results event");

        int rem = 0, i = 0;

        nl_iterator it(event.get_attribute(NL80211_ATTR_SCAN_SSIDS));
        for ( ; it.has_next(); it.next()) {
            struct nlattr *attr = it.get();
            wifi_scan_result *result = &mResults[i];
            char *ssid = (char *)nla_data(attr);
            int len = nla_len(attr);
            memcpy(result->ssid, ssid, len);
            ssid[len] = 0;
        }

        (*mHandler.on_scan_results)(id(), i, mResults);
        return NL_SKIP;
    }
};

wifi_error wifi_start_gscan(
        wifi_request_id id,
        wifi_interface_handle iface,
        wifi_scan_cmd_params params,
        IScanResultsHandler handler)
{
    interface_info *iinfo = (interface_info *)iface;
    wifi_handle handle = iinfo->handle;
    hal_info *info = (hal_info *)handle;

    ScanCommand *cmd = new ScanCommand(handle, id, params.channels, params.num_channels, handler);
    wifi_register_cmd(handle, id, cmd);
    return (wifi_error)cmd->start();
}

wifi_error wifi_stop_gscan(wifi_request_id id, wifi_interface_handle iface)
{
    interface_info *iinfo = (interface_info *)iface;
    wifi_handle handle = iinfo->handle;
    hal_info *info = (hal_info *)handle;

    WifiCommand *cmd = wifi_unregister_cmd(handle, id);
    if (cmd) {
        cmd->cancel();
        delete cmd;
        return WIFI_SUCCESS;
    }

    return WIFI_ERROR_INVALID_ARGS;
}


/////////////////////////////////////////////////////////////////////////////

class BssidHotlistCommand : public WifiCommand
{
private:
    static const uint32_t VENDOR_OUI = GOOGLE_OUI;
    int mNum;
    mac_addr *mBssids;
    wifi_hotlist_ap_found_handler mHandler;
    static const unsigned int MAX_RESULTS = 64;
    wifi_scan_result mResults[MAX_RESULTS];
public:
    BssidHotlistCommand(wifi_handle handle, int id,
            mac_addr bssid[], int num, wifi_hotlist_ap_found_handler handler)
        : WifiCommand(handle, id), mNum(num), mBssids(bssid), mHandler(handler)
    { }

    virtual int create() {
        int ret = mMsg.create(VENDOR_OUI, GSCAN_SUBCMD_SET_HOTLIST);
        if (ret < 0) {
            return ret;
        }
        struct nlattr * attr = mMsg.attr_start(GSCAN_ATTRIBUTE_HOTLIST_BSSIDS);
        for (int i = 0; i < mNum; i++) {
            ret = mMsg.put_addr(i + 1, mBssids[i]);
            if (ret < 0) {
                return ret;
            }
        }
        mMsg.attr_end(attr);
        return ret;
    }

    int start() {
        registerVendorHandler(VENDOR_OUI, GSCAN_SUBCMD_HOTLIST_RESULTS);
        int res = requestResponse();
        mMsg.destroy();
        return res;
    }

    virtual int cancel() {
        /* unregister event handler */
        unregisterVendorHandler(VENDOR_OUI, GSCAN_SUBCMD_HOTLIST_RESULTS);

        /* create set hotlist message with empty hotlist */
        int ret = mMsg.create(VENDOR_OUI, GSCAN_SUBCMD_SET_HOTLIST);
        if (ret < 0) {
            return WIFI_ERROR_OUT_OF_MEMORY;
        }

        struct nlattr * attr = mMsg.attr_start(GSCAN_ATTRIBUTE_HOTLIST_BSSIDS);
        if (attr == NULL) {
            return WIFI_ERROR_OUT_OF_MEMORY;
        }

        mMsg.attr_end(attr);
        return requestResponse();
    }

    virtual int handleResponse(WifiEvent reply) {
        /* Nothing to do on response! */
        return NL_SKIP;
    }

    virtual int handleEvent(WifiEvent event) {
        ALOGI("Got a scan results event");

        int rem = 0, i = 0;

        nl_iterator it(event.get_attribute(NL80211_ATTR_SCAN_SSIDS));
        for ( ; it.has_next(); it.next()) {
            struct nlattr *attr = it.get();
            wifi_scan_result *result = &mResults[i];
            char *ssid = (char *)nla_data(attr);
            int len = nla_len(attr);
            memcpy(result->ssid, ssid, len);
            ssid[len] = 0;
        }

        (*mHandler.on_hotlist_ap_found)(id(), i, mResults);
        return NL_SKIP;
    }
};

wifi_error wifi_set_bssid_hotlist(wifi_request_id id, wifi_interface_handle iface,
        int num_bssid, mac_addr bssid[], wifi_hotlist_ap_found_handler handler)
{
    interface_info *iinfo = (interface_info *)iface;
    wifi_handle handle = iinfo->handle;
    hal_info *info = (hal_info *)handle;

    BssidHotlistCommand *cmd = new BssidHotlistCommand(handle, id, bssid, num_bssid, handler);
    wifi_register_cmd(handle, id, cmd);
    return (wifi_error)cmd->start();
}

wifi_error wifi_reset_bssid_hotlist(wifi_request_id id, wifi_interface_handle iface)
{
    interface_info *iinfo = (interface_info *)iface;
    wifi_handle handle = iinfo->handle;
    hal_info *info = (hal_info *)handle;

    WifiCommand *cmd = wifi_unregister_cmd(handle, id);
    if (cmd) {
        cmd->cancel();
        delete cmd;
        return WIFI_SUCCESS;
    }

    return WIFI_ERROR_INVALID_ARGS;
}


/////////////////////////////////////////////////////////////////////////////


class SignificantWifiChangeCommand : public WifiCommand
{
private:
    static const uint32_t VENDOR_OUI = GOOGLE_OUI;
    wifi_significant_change_handler mHandler;
    static const unsigned int MAX_RESULTS = 64;
    wifi_scan_result mResults[MAX_RESULTS];
public:
    SignificantWifiChangeCommand(wifi_handle handle, int id,
            wifi_significant_change_handler handler)
        : WifiCommand(handle, id), mHandler(handler)
    { }

    virtual int create() {
        int ret = mMsg.create(VENDOR_OUI, GSCAN_SUBCMD_SET_SIGNIFICANT_CHANGE_MONITOR);
        if (ret < 0) {
            return ret;
        }
        mMsg.put_u8(GSCAN_ATTRIBUTE_SIGNIFICANT_CHANGE_ENABLE, 1);
        return ret;
    }

    int start() {
        registerVendorHandler(VENDOR_OUI, GSCAN_SUBCMD_SIGNIFICANT_CHANGE_RESULTS);
        int res = requestResponse();
        mMsg.destroy();
        return res;
    }

    virtual int cancel() {
        /* unregister event handler */
        unregisterVendorHandler(VENDOR_OUI, GSCAN_SUBCMD_SIGNIFICANT_CHANGE_RESULTS);

        /* create set significant change monitor message with empty hotlist */
        int ret = mMsg.create(VENDOR_OUI, GSCAN_SUBCMD_SET_SIGNIFICANT_CHANGE_MONITOR);
        if (ret < 0) {
            return WIFI_ERROR_OUT_OF_MEMORY;
        }
        mMsg.put_u8(GSCAN_ATTRIBUTE_SIGNIFICANT_CHANGE_ENABLE, 0);
        return requestResponse();
    }

    virtual int handleResponse(WifiEvent reply) {
        /* Nothing to do on response! */
        return NL_SKIP;
    }

    virtual int handleEvent(WifiEvent event) {
        ALOGI("Got a scan results event");

        int rem = 0, i = 0;

        nl_iterator it(event.get_attribute(NL80211_ATTR_SCAN_SSIDS));
        for ( ; it.has_next(); it.next()) {
            struct nlattr *attr = it.get();
            wifi_scan_result *result = &mResults[i];
            char *ssid = (char *)nla_data(attr);
            int len = nla_len(attr);
            memcpy(result->ssid, ssid, len);
            ssid[len] = 0;
        }

        (*mHandler.on_significant_change)(id(), i, mResults);
        return NL_SKIP;
    }
};

wifi_error wifi_set_significant_change_handler(wifi_request_id id, wifi_interface_handle iface,
        wifi_significant_change_handler handler)
{
    interface_info *iinfo = (interface_info *)iface;
    wifi_handle handle = iinfo->handle;
    hal_info *info = (hal_info *)handle;

    SignificantWifiChangeCommand *cmd = new SignificantWifiChangeCommand(handle, id, handler);
    wifi_register_cmd(handle, id, cmd);
    return (wifi_error)cmd->start();
}

wifi_error wifi_reset_significant_change_handler(wifi_request_id id, wifi_interface_handle iface)
{
    interface_info *iinfo = (interface_info *)iface;
    wifi_handle handle = iinfo->handle;
    hal_info *info = (hal_info *)handle;

    WifiCommand *cmd = wifi_unregister_cmd(handle, id);
    if (cmd) {
        cmd->cancel();
        delete cmd;
        return WIFI_SUCCESS;
    }

    return WIFI_ERROR_INVALID_ARGS;
}
