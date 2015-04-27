/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "wifi"

#include "jni.h"
#include <ScopedUtfChars.h>
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/String16.h>
#include <ctype.h>
#include <sys/socket.h>
#include <linux/if.h>

#include "wifi.h"
#include "wifi_hal.h"
#include "jni_helper.h"
#include "rtt.h"
#include "wifi_hal_stub.h"

#define REPLY_BUF_SIZE 4096 // wpa_supplicant's maximum size.
#define EVENT_BUF_SIZE 2048

namespace android {

static jint DBG = false;

//Please put all HAL function call here and call from the function table instead of directly call
static wifi_hal_fn hal_fn;
int init_wifi_hal_func_table(wifi_hal_fn *hal_fn) {
    if (hal_fn == NULL) {
        return -1;
    }

    hal_fn->wifi_initialize = wifi_initialize_stub;
    hal_fn->wifi_cleanup = wifi_cleanup_stub;
    hal_fn->wifi_event_loop = wifi_event_loop_stub;
    hal_fn->wifi_get_error_info = wifi_get_error_info_stub;
    hal_fn->wifi_get_supported_feature_set = wifi_get_supported_feature_set_stub;
    hal_fn->wifi_get_concurrency_matrix = wifi_get_concurrency_matrix_stub;
    hal_fn->wifi_set_scanning_mac_oui =  wifi_set_scanning_mac_oui_stub;
    hal_fn->wifi_get_supported_channels = wifi_get_supported_channels_stub;
    hal_fn->wifi_is_epr_supported = wifi_is_epr_supported_stub;
    hal_fn->wifi_get_ifaces = wifi_get_ifaces_stub;
    hal_fn->wifi_get_iface_name = wifi_get_iface_name_stub;
    hal_fn->wifi_reset_iface_event_handler = wifi_reset_iface_event_handler_stub;
    hal_fn->wifi_start_gscan = wifi_start_gscan_stub;
    hal_fn->wifi_stop_gscan = wifi_stop_gscan_stub;
    hal_fn->wifi_get_cached_gscan_results = wifi_get_cached_gscan_results_stub;
    hal_fn->wifi_set_bssid_hotlist = wifi_set_bssid_hotlist_stub;
    hal_fn->wifi_reset_bssid_hotlist = wifi_reset_bssid_hotlist_stub;
    hal_fn->wifi_set_significant_change_handler = wifi_set_significant_change_handler_stub;
    hal_fn->wifi_reset_significant_change_handler = wifi_reset_significant_change_handler_stub;
    hal_fn->wifi_get_gscan_capabilities = wifi_get_gscan_capabilities_stub;
    hal_fn->wifi_set_link_stats = wifi_set_link_stats_stub;
    hal_fn->wifi_get_link_stats = wifi_get_link_stats_stub;
    hal_fn->wifi_clear_link_stats = wifi_clear_link_stats_stub;
    hal_fn->wifi_get_valid_channels = wifi_get_valid_channels_stub;
    hal_fn->wifi_rtt_range_request = wifi_rtt_range_request_stub;
    hal_fn->wifi_rtt_range_cancel = wifi_rtt_range_cancel_stub;
    hal_fn->wifi_get_rtt_capabilities = wifi_get_rtt_capabilities_stub;
    hal_fn->wifi_start_logging = wifi_start_logging_stub;
    hal_fn->wifi_set_epno_list = wifi_set_epno_list_stub;
    hal_fn->wifi_set_country_code = wifi_set_country_code_stub;
    hal_fn->wifi_enable_tdls = wifi_enable_tdls_stub;
    hal_fn->wifi_disable_tdls = wifi_disable_tdls_stub;
    hal_fn->wifi_get_tdls_status = wifi_get_tdls_status_stub;
    hal_fn->wifi_get_tdls_capabilities = wifi_get_tdls_capabilities_stub;
    hal_fn->wifi_get_firmware_memory_dump = wifi_get_firmware_memory_dump_stub;
    hal_fn->wifi_set_log_handler = wifi_set_log_handler_stub;
    hal_fn->wifi_set_alert_handler = wifi_set_alert_handler_stub;
    hal_fn->wifi_get_firmware_version = wifi_get_firmware_version_stub;
    hal_fn->wifi_get_ring_buffers_status = wifi_get_ring_buffers_status_stub;
    hal_fn->wifi_get_logger_supported_feature_set = wifi_get_logger_supported_feature_set_stub;
    hal_fn->wifi_get_ring_data = wifi_get_ring_data_stub;
    hal_fn->wifi_get_driver_version = wifi_get_driver_version_stub;
    return 0;
}


static bool doCommand(JNIEnv* env, jstring javaCommand,
                      char* reply, size_t reply_len) {
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return false; // ScopedUtfChars already threw on error.
    }

    if (DBG) {
        ALOGD("doCommand: %s", command.c_str());
    }

    --reply_len; // Ensure we have room to add NUL termination.
    if (::wifi_command(command.c_str(), reply, &reply_len) != 0) {
        return false;
    }

    // Strip off trailing newline.
    if (reply_len > 0 && reply[reply_len-1] == '\n') {
        reply[reply_len-1] = '\0';
    } else {
        reply[reply_len] = '\0';
    }
    return true;
}

static jint doIntCommand(JNIEnv* env, jstring javaCommand) {
    char reply[REPLY_BUF_SIZE];
    if (!doCommand(env, javaCommand, reply, sizeof(reply))) {
        return -1;
    }
    return static_cast<jint>(atoi(reply));
}

static jboolean doBooleanCommand(JNIEnv* env, jstring javaCommand) {
    char reply[REPLY_BUF_SIZE];
    if (!doCommand(env, javaCommand, reply, sizeof(reply))) {
        return JNI_FALSE;
    }
    return (strcmp(reply, "OK") == 0);
}

// Send a command to the supplicant, and return the reply as a String.
static jstring doStringCommand(JNIEnv* env, jstring javaCommand) {
    char reply[REPLY_BUF_SIZE];
    if (!doCommand(env, javaCommand, reply, sizeof(reply))) {
        return NULL;
    }
    return env->NewStringUTF(reply);
}

static jboolean android_net_wifi_isDriverLoaded(JNIEnv* env, jobject)
{
    return (::is_wifi_driver_loaded() == 1);
}

static jboolean android_net_wifi_loadDriver(JNIEnv* env, jobject)
{
    return (::wifi_load_driver() == 0);
}

static jboolean android_net_wifi_unloadDriver(JNIEnv* env, jobject)
{
    return (::wifi_unload_driver() == 0);
}

static jboolean android_net_wifi_startSupplicant(JNIEnv* env, jobject, jboolean p2pSupported)
{
    return (::wifi_start_supplicant(p2pSupported) == 0);
}

static jboolean android_net_wifi_killSupplicant(JNIEnv* env, jobject, jboolean p2pSupported)
{
    return (::wifi_stop_supplicant(p2pSupported) == 0);
}

static jboolean android_net_wifi_connectToSupplicant(JNIEnv* env, jobject)
{
    return (::wifi_connect_to_supplicant() == 0);
}

static void android_net_wifi_closeSupplicantConnection(JNIEnv* env, jobject)
{
    ::wifi_close_supplicant_connection();
}

static jstring android_net_wifi_waitForEvent(JNIEnv* env, jobject)
{
    char buf[EVENT_BUF_SIZE];
    int nread = ::wifi_wait_for_event(buf, sizeof buf);
    if (nread > 0) {
        return env->NewStringUTF(buf);
    } else {
        return NULL;
    }
}

static jboolean android_net_wifi_doBooleanCommand(JNIEnv* env, jobject, jstring javaCommand) {
    return doBooleanCommand(env, javaCommand);
}

static jint android_net_wifi_doIntCommand(JNIEnv* env, jobject, jstring javaCommand) {
    return doIntCommand(env, javaCommand);
}

static jstring android_net_wifi_doStringCommand(JNIEnv* env, jobject, jstring javaCommand) {
    return doStringCommand(env,javaCommand);
}

/* wifi_hal <==> WifiNative bridge */

static jclass mCls;                             /* saved WifiNative object */
static JavaVM *mVM;                             /* saved JVM pointer */

static const char *WifiHandleVarName = "sWifiHalHandle";
static const char *WifiIfaceHandleVarName = "sWifiIfaceHandles";
static jmethodID OnScanResultsMethodID;

static JNIEnv *getEnv() {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);
    return env;
}

static wifi_handle getWifiHandle(JNIEnv *env, jclass cls) {
    return (wifi_handle) getStaticLongField(env, cls, WifiHandleVarName);
}

static wifi_interface_handle getIfaceHandle(JNIEnv *env, jclass cls, jint index) {
    return (wifi_interface_handle) getStaticLongArrayField(env, cls, WifiIfaceHandleVarName, index);
}

static jobject createScanResult(JNIEnv *env, wifi_scan_result *result) {

    // ALOGD("creating scan result");

    jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
    if (scanResult == NULL) {
        ALOGE("Error in creating scan result");
        return NULL;
    }

    ALOGE("setting SSID to %s", result->ssid);
    //jstring jssid = env->NewStringUTF(result->ssid);
    setStringField(env, scanResult, "SSID", result->ssid);

    char bssid[32];
    sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", result->bssid[0], result->bssid[1],
        result->bssid[2], result->bssid[3], result->bssid[4], result->bssid[5]);
    //jstring jbssid = env->NewStringUTF(bssid);

    setStringField(env, scanResult, "BSSID", bssid);

    setIntField(env, scanResult, "level", result->rssi);
    setIntField(env, scanResult, "frequency", result->channel);
    setLongField(env, scanResult, "timestamp", result->ts);

    return scanResult;
}

int set_iface_flags(const char *ifname, int dev_up) {
    struct ifreq ifr;
    int ret;
    int sock = socket(PF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        ALOGD("Bad socket: %d\n", sock);
        return -errno;
    }

    //ALOGD("setting interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");

    memset(&ifr, 0, sizeof(ifr));
    strlcpy(ifr.ifr_name, ifname, IFNAMSIZ);

    //ALOGD("reading old value\n");

    if (ioctl(sock, SIOCGIFFLAGS, &ifr) != 0) {
      ret = errno ? -errno : -999;
      ALOGE("Could not read interface %s flags: %d\n", ifname, errno);
      close(sock);
      return ret;
    } else {
      //ALOGD("writing new value\n");
    }

    if (dev_up) {
      if (ifr.ifr_flags & IFF_UP) {
        // ALOGD("interface %s is already up\n", ifname);
        close(sock);
        return 0;
      }
      ifr.ifr_flags |= IFF_UP;
    } else {
      if (!(ifr.ifr_flags & IFF_UP)) {
        // ALOGD("interface %s is already down\n", ifname);
        close(sock);
        return 0;
      }
      ifr.ifr_flags &= ~IFF_UP;
    }

    if (ioctl(sock, SIOCSIFFLAGS, &ifr) != 0) {
      ALOGE("Could not set interface %s flags \n", ifname);
      close(sock);
      return ret;
    } else {
      ALOGD("set interface %s flags (%s)\n", ifname, dev_up ? "UP" : "DOWN");
    }
    close(sock);
    return 0;
}

static jboolean android_net_wifi_toggle_interface(JNIEnv* env, jclass cls, int toggle) {
    return(set_iface_flags("wlan0", toggle) == 0);
}

static jboolean android_net_wifi_startHal(JNIEnv* env, jclass cls) {
    wifi_handle halHandle = getWifiHandle(env, cls);
    if (halHandle == NULL) {

        if(init_wifi_hal_func_table(&hal_fn) != 0 ) {
            ALOGD("Can not initialize the basic function pointer table");
            return false;
        }

        wifi_error res = init_wifi_vendor_hal_func_table(&hal_fn);
        if (res != WIFI_SUCCESS) {
            ALOGD("Can not initialize the vendor function pointer table");
	    return false;
        }

        int ret = set_iface_flags("wlan0", 1);
        if(ret != 0) {
            return false;
        }

        res = hal_fn.wifi_initialize(&halHandle);
        if (res == WIFI_SUCCESS) {
            setStaticLongField(env, cls, WifiHandleVarName, (jlong)halHandle);
            ALOGD("Did set static halHandle = %p", halHandle);
        }
        env->GetJavaVM(&mVM);
        mCls = (jclass) env->NewGlobalRef(cls);
        ALOGD("halHandle = %p, mVM = %p, mCls = %p", halHandle, mVM, mCls);
        return res == WIFI_SUCCESS;
    } else {
        return (set_iface_flags("wlan0", 1) == 0);
    }
}

void android_net_wifi_hal_cleaned_up_handler(wifi_handle handle) {
    ALOGD("In wifi cleaned up handler");

    JNIEnv * env = getEnv();
    setStaticLongField(env, mCls, WifiHandleVarName, 0);
    env->DeleteGlobalRef(mCls);
    mCls = NULL;
    mVM  = NULL;
}

static void android_net_wifi_stopHal(JNIEnv* env, jclass cls) {
    ALOGD("In wifi stop Hal");

    wifi_handle halHandle = getWifiHandle(env, cls);
    hal_fn.wifi_cleanup(halHandle, android_net_wifi_hal_cleaned_up_handler);
    set_iface_flags("wlan0", 0);
}

static void android_net_wifi_waitForHalEvents(JNIEnv* env, jclass cls) {

    ALOGD("waitForHalEvents called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    wifi_handle halHandle = getWifiHandle(env, cls);
    hal_fn.wifi_event_loop(halHandle);
}

static int android_net_wifi_getInterfaces(JNIEnv *env, jclass cls) {
    int n = 0;
    wifi_handle halHandle = getWifiHandle(env, cls);
    wifi_interface_handle *ifaceHandles = NULL;
    int result = hal_fn.wifi_get_ifaces(halHandle, &n, &ifaceHandles);
    if (result < 0) {
        return result;
    }

    if (n < 0) {
        THROW(env, "android_net_wifi_getInterfaces no interfaces");
        return 0;
    }

    if (ifaceHandles == NULL) {
       THROW(env, "android_net_wifi_getInterfaces null interface array");
       return 0;
    }

    if (n > 8) {
        THROW(env, "Too many interfaces");
        return 0;
    }

    jlongArray array = (env)->NewLongArray(n);
    if (array == NULL) {
        THROW(env, "Error in accessing array");
        return 0;
    }

    jlong elems[8];
    for (int i = 0; i < n; i++) {
        elems[i] = reinterpret_cast<jlong>(ifaceHandles[i]);
    }
    env->SetLongArrayRegion(array, 0, n, elems);
    setStaticLongArrayField(env, cls, WifiIfaceHandleVarName, array);

    return (result < 0) ? result : n;
}

static jstring android_net_wifi_getInterfaceName(JNIEnv *env, jclass cls, jint i) {
    char buf[EVENT_BUF_SIZE];

    jlong value = getStaticLongArrayField(env, cls, WifiIfaceHandleVarName, i);
    wifi_interface_handle handle = (wifi_interface_handle) value;
    int result = hal_fn.wifi_get_iface_name(handle, buf, sizeof(buf));
    if (result < 0) {
        return NULL;
    } else {
        return env->NewStringUTF(buf);
    }
}


static void onScanResultsAvailable(wifi_request_id id, unsigned num_results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onScanResultsAvailable called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    reportEvent(env, mCls, "onScanResultsAvailable", "(I)V", id);
}

static void onScanEvent(wifi_scan_event event, unsigned status) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onScanStatus called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    reportEvent(env, mCls, "onScanStatus", "(I)V", event);
}

static void onFullScanResult(wifi_request_id id, wifi_scan_result *result) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onFullScanResult called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    jobject scanResult = createScanResult(env, result);

    ALOGD("Creating a byte array of length %d", result->ie_length);

    jbyteArray elements = env->NewByteArray(result->ie_length);
    if (elements == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    ALOGE("Setting byte array");

    jbyte *bytes = (jbyte *)&(result->ie_data[0]);
    env->SetByteArrayRegion(elements, 0, result->ie_length, bytes);

    ALOGE("Returning result");

    reportEvent(env, mCls, "onFullScanResult", "(ILandroid/net/wifi/ScanResult;[B)V", id,
            scanResult, elements);

    env->DeleteLocalRef(scanResult);
    env->DeleteLocalRef(elements);
}

static jboolean android_net_wifi_startScan(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject settings) {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("starting scan on interface[%d] = %p", iface, handle);

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    params.base_period = getIntField(env, settings, "base_period_ms");
    params.max_ap_per_scan = getIntField(env, settings, "max_ap_per_scan");
    params.report_threshold_percent = getIntField(env, settings, "report_threshold_percent");
    params.report_threshold_num_scans = getIntField(env, settings, "report_threshold_num_scans");

    ALOGD("Initialized common fields %d, %d, %d, %d", params.base_period, params.max_ap_per_scan,
            params.report_threshold_percent, params.report_threshold_num_scans);

    const char *bucket_array_type = "[Lcom/android/server/wifi/WifiNative$BucketSettings;";
    const char *channel_array_type = "[Lcom/android/server/wifi/WifiNative$ChannelSettings;";

    jobjectArray buckets = (jobjectArray)getObjectField(env, settings, "buckets", bucket_array_type);
    params.num_buckets = getIntField(env, settings, "num_buckets");

    ALOGD("Initialized num_buckets to %d", params.num_buckets);

    for (int i = 0; i < params.num_buckets; i++) {
        jobject bucket = getObjectArrayField(env, settings, "buckets", bucket_array_type, i);

        params.buckets[i].bucket = getIntField(env, bucket, "bucket");
        params.buckets[i].band = (wifi_band) getIntField(env, bucket, "band");
        params.buckets[i].period = getIntField(env, bucket, "period_ms");

        ALOGD("Initialized common bucket fields %d:%d:%d", params.buckets[i].bucket,
                params.buckets[i].band, params.buckets[i].period);

        int report_events = getIntField(env, bucket, "report_events");
        params.buckets[i].report_events = report_events;

        ALOGD("Initialized report events to %d", params.buckets[i].report_events);

        jobjectArray channels = (jobjectArray)getObjectField(
                env, bucket, "channels", channel_array_type);

        params.buckets[i].num_channels = getIntField(env, bucket, "num_channels");
        ALOGD("Initialized num_channels to %d", params.buckets[i].num_channels);

        for (int j = 0; j < params.buckets[i].num_channels; j++) {
            jobject channel = getObjectArrayField(env, bucket, "channels", channel_array_type, j);

            params.buckets[i].channels[j].channel = getIntField(env, channel, "frequency");
            params.buckets[i].channels[j].dwellTimeMs = getIntField(env, channel, "dwell_time_ms");

            bool passive = getBoolField(env, channel, "passive");
            params.buckets[i].channels[j].passive = (passive ? 1 : 0);

            // ALOGD("Initialized channel %d", params.buckets[i].channels[j].channel);
        }
    }

    ALOGD("Initialized all fields");

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = &onScanResultsAvailable;
    handler.on_full_scan_result = &onFullScanResult;
    handler.on_scan_event = &onScanEvent;

    return hal_fn.wifi_start_gscan(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_stopScan(JNIEnv *env, jclass cls, jint iface, jint id) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("stopping scan on interface[%d] = %p", iface, handle);

    return hal_fn.wifi_stop_gscan(id, handle)  == WIFI_SUCCESS;
}

static int compare_scan_result_timestamp(const void *v1, const void *v2) {
    const wifi_scan_result *result1 = static_cast<const wifi_scan_result *>(v1);
    const wifi_scan_result *result2 = static_cast<const wifi_scan_result *>(v2);
    return result1->ts - result2->ts;
}

static jobject android_net_wifi_getScanResults(
        JNIEnv *env, jclass cls, jint iface, jboolean flush)  {

    wifi_cached_scan_results scan_data[64];
    int num_scan_data = 64;

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("getting scan results on interface[%d] = %p", iface, handle);

    byte b = flush ? 0xFF : 0;
    int result = hal_fn.wifi_get_cached_gscan_results(handle, b, num_scan_data, scan_data, &num_scan_data);
    if (result == WIFI_SUCCESS) {
        jobjectArray scanData = createObjectArray(env,
                "android/net/wifi/WifiScanner$ScanData", num_scan_data);
        if (scanData == NULL) {
            ALOGE("Error in allocating array of scanData");
            return NULL;
        }

        for (int i = 0; i < num_scan_data; i++) {

            jobject data = createObject(env, "android/net/wifi/WifiScanner$ScanData");
            if (data == NULL) {
                ALOGE("Error in allocating scanData");
                return NULL;
            }

            setIntField(env, data, "mId", scan_data[i].scan_id);
            setIntField(env, data, "mFlags", scan_data[i].flags);

            /* sort all scan results by timestamp */
            qsort(scan_data[i].results, scan_data[i].num_results,
                    sizeof(wifi_scan_result), compare_scan_result_timestamp);

            jobjectArray scanResults = createObjectArray(env,
                    "android/net/wifi/ScanResult", scan_data[i].num_results);
            if (scanResults == NULL) {
                ALOGE("Error in allocating scanResult array");
                return NULL;
            }

            wifi_scan_result *results = scan_data[i].results;
            for (int j = 0; j < scan_data[i].num_results; j++) {

                jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
                if (scanResult == NULL) {
                    ALOGE("Error in creating scan result");
                    return NULL;
                }

                setStringField(env, scanResult, "SSID", results[j].ssid);

                char bssid[32];
                sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", results[j].bssid[0],
                        results[j].bssid[1], results[j].bssid[2], results[j].bssid[3],
                        results[j].bssid[4], results[j].bssid[5]);

                setStringField(env, scanResult, "BSSID", bssid);

                setIntField(env, scanResult, "level", results[j].rssi);
                setIntField(env, scanResult, "frequency", results[j].channel);
                setLongField(env, scanResult, "timestamp", results[j].ts);

                env->SetObjectArrayElement(scanResults, j, scanResult);
                env->DeleteLocalRef(scanResult);
            }

            setObjectField(env, data, "mResults", "[Landroid/net/wifi/ScanResult;", scanResults);
            env->SetObjectArrayElement(scanData, i, data);
        }

        return scanData;
    } else {
        return NULL;
    }
}


static jboolean android_net_wifi_getScanCapabilities(
        JNIEnv *env, jclass cls, jint iface, jobject capabilities) {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("getting scan capabilities on interface[%d] = %p", iface, handle);

    wifi_gscan_capabilities c;
    memset(&c, 0, sizeof(c));
    int result = hal_fn.wifi_get_gscan_capabilities(handle, &c);
    if (result != WIFI_SUCCESS) {
        ALOGD("failed to get capabilities : %d", result);
        return JNI_FALSE;
    }

    setIntField(env, capabilities, "max_scan_cache_size", c.max_scan_cache_size);
    setIntField(env, capabilities, "max_scan_buckets", c.max_scan_buckets);
    setIntField(env, capabilities, "max_ap_cache_per_scan", c.max_ap_cache_per_scan);
    setIntField(env, capabilities, "max_rssi_sample_size", c.max_rssi_sample_size);
    setIntField(env, capabilities, "max_scan_reporting_threshold", c.max_scan_reporting_threshold);
    setIntField(env, capabilities, "max_hotlist_bssids", c.max_hotlist_bssids);
    setIntField(env, capabilities, "max_significant_wifi_change_aps",
                c.max_significant_wifi_change_aps);

    return JNI_TRUE;
}


static byte parseHexChar(char ch) {
    if (isdigit(ch))
        return ch - '0';
    else if ('A' <= ch && ch <= 'F')
        return ch - 'A' + 10;
    else if ('a' <= ch && ch <= 'f')
        return ch - 'a' + 10;
    else {
        ALOGE("invalid character in bssid %c", ch);
        return 0;
    }
}

static byte parseHexByte(const char * &str) {
    byte b = parseHexChar(str[0]);
    if (str[1] == ':' || str[1] == '\0') {
        str += 2;
        return b;
    } else {
        b = b << 4 | parseHexChar(str[1]);
        str += 3;
        return b;
    }
}

static void parseMacAddress(const char *str, mac_addr addr) {
    addr[0] = parseHexByte(str);
    addr[1] = parseHexByte(str);
    addr[2] = parseHexByte(str);
    addr[3] = parseHexByte(str);
    addr[4] = parseHexByte(str);
    addr[5] = parseHexByte(str);
}

static bool parseMacAddress(JNIEnv *env, jobject obj, mac_addr addr) {
    jstring macAddrString = (jstring) getObjectField(
            env, obj, "bssid", "Ljava/lang/String;");

    if (macAddrString == NULL) {
        ALOGE("Error getting bssid field");
        return false;
    }

    const char *bssid = env->GetStringUTFChars(macAddrString, NULL);
    if (bssid == NULL) {
        ALOGE("Error getting bssid");
        return false;
    }

    parseMacAddress(bssid, addr);
    return true;
}

static void onHotlistApFound(wifi_request_id id,
        unsigned num_results, wifi_scan_result *results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onHotlistApFound called, vm = %p, obj = %p, env = %p, num_results = %d",
            mVM, mCls, env, num_results);

    jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
    if (clsScanResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
    if (scanResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
        if (scanResult == NULL) {
            ALOGE("Error in creating scan result");
            return;
        }

        setStringField(env, scanResult, "SSID", results[i].ssid);

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", results[i].bssid[0], results[i].bssid[1],
            results[i].bssid[2], results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);

        setStringField(env, scanResult, "BSSID", bssid);

        setIntField(env, scanResult, "level", results[i].rssi);
        setIntField(env, scanResult, "frequency", results[i].channel);
        setLongField(env, scanResult, "timestamp", results[i].ts);

        env->SetObjectArrayElement(scanResults, i, scanResult);

        ALOGD("Found AP %32s %s", results[i].ssid, bssid);
    }

    reportEvent(env, mCls, "onHotlistApFound", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);
}

static void onHotlistApLost(wifi_request_id id,
        unsigned num_results, wifi_scan_result *results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onHotlistApLost called, vm = %p, obj = %p, env = %p, num_results = %d",
            mVM, mCls, env, num_results);

    jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
    if (clsScanResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
    if (scanResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
        if (scanResult == NULL) {
            ALOGE("Error in creating scan result");
            return;
        }

        setStringField(env, scanResult, "SSID", results[i].ssid);

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", results[i].bssid[0], results[i].bssid[1],
            results[i].bssid[2], results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);

        setStringField(env, scanResult, "BSSID", bssid);

        setIntField(env, scanResult, "level", results[i].rssi);
        setIntField(env, scanResult, "frequency", results[i].channel);
        setLongField(env, scanResult, "timestamp", results[i].ts);

        env->SetObjectArrayElement(scanResults, i, scanResult);

        ALOGD("Lost AP %32s %s", results[i].ssid, bssid);
    }

    reportEvent(env, mCls, "onHotlistApLost", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);
}


static jboolean android_net_wifi_setHotlist(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject ap)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("setting hotlist on interface[%d] = %p", iface, handle);

    wifi_bssid_hotlist_params params;
    memset(&params, 0, sizeof(params));

    params.lost_ap_sample_size = getIntField(env, ap, "apLostThreshold");

    jobjectArray array = (jobjectArray) getObjectField(env, ap,
            "bssidInfos", "[Landroid/net/wifi/WifiScanner$BssidInfo;");
    params.num_bssid = env->GetArrayLength(array);

    if (params.num_bssid == 0) {
        ALOGE("Error in accesing array");
        return false;
    }

    for (int i = 0; i < params.num_bssid; i++) {
        jobject objAp = env->GetObjectArrayElement(array, i);

        jstring macAddrString = (jstring) getObjectField(
                env, objAp, "bssid", "Ljava/lang/String;");
        if (macAddrString == NULL) {
            ALOGE("Error getting bssid field");
            return false;
        }

        const char *bssid = env->GetStringUTFChars(macAddrString, NULL);
        if (bssid == NULL) {
            ALOGE("Error getting bssid");
            return false;
        }
        parseMacAddress(bssid, params.ap[i].bssid);

        mac_addr addr;
        memcpy(addr, params.ap[i].bssid, sizeof(mac_addr));

        char bssidOut[32];
        sprintf(bssidOut, "%0x:%0x:%0x:%0x:%0x:%0x", addr[0], addr[1],
            addr[2], addr[3], addr[4], addr[5]);

        ALOGD("Added bssid %s", bssidOut);

        params.ap[i].low = getIntField(env, objAp, "low");
        params.ap[i].high = getIntField(env, objAp, "high");
    }

    wifi_hotlist_ap_found_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_hotlist_ap_found = &onHotlistApFound;
    handler.on_hotlist_ap_lost  = &onHotlistApLost;
    return hal_fn.wifi_set_bssid_hotlist(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_resetHotlist(
        JNIEnv *env, jclass cls, jint iface, jint id)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("resetting hotlist on interface[%d] = %p", iface, handle);

    return hal_fn.wifi_reset_bssid_hotlist(id, handle) == WIFI_SUCCESS;
}

void onSignificantWifiChange(wifi_request_id id,
        unsigned num_results, wifi_significant_change_result **results) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onSignificantWifiChange called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
    if (clsScanResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
    if (scanResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        wifi_significant_change_result result = *(results[i]);

        jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
        if (scanResult == NULL) {
            ALOGE("Error in creating scan result");
            return;
        }

        // setStringField(env, scanResult, "SSID", results[i].ssid);

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", result.bssid[0], result.bssid[1],
            result.bssid[2], result.bssid[3], result.bssid[4], result.bssid[5]);

        setStringField(env, scanResult, "BSSID", bssid);

        setIntField(env, scanResult, "level", result.rssi[0]);
        setIntField(env, scanResult, "frequency", result.channel);
        // setLongField(env, scanResult, "timestamp", result.ts);

        env->SetObjectArrayElement(scanResults, i, scanResult);
    }

    reportEvent(env, mCls, "onSignificantWifiChange", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);

}

static jboolean android_net_wifi_trackSignificantWifiChange(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject settings)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("tracking significant wifi change on interface[%d] = %p", iface, handle);

    wifi_significant_change_params params;
    memset(&params, 0, sizeof(params));

    params.rssi_sample_size = getIntField(env, settings, "rssiSampleSize");
    params.lost_ap_sample_size = getIntField(env, settings, "lostApSampleSize");
    params.min_breaching = getIntField(env, settings, "minApsBreachingThreshold");

    const char *bssid_info_array_type = "[Landroid/net/wifi/WifiScanner$BssidInfo;";
    jobjectArray bssids = (jobjectArray)getObjectField(
                env, settings, "bssidInfos", bssid_info_array_type);
    params.num_bssid = env->GetArrayLength(bssids);

    if (params.num_bssid == 0) {
        ALOGE("Error in accessing array");
        return false;
    }

    ALOGD("Initialized common fields %d, %d, %d, %d", params.rssi_sample_size,
            params.lost_ap_sample_size, params.min_breaching, params.num_bssid);

    for (int i = 0; i < params.num_bssid; i++) {
        jobject objAp = env->GetObjectArrayElement(bssids, i);

        jstring macAddrString = (jstring) getObjectField(
                env, objAp, "bssid", "Ljava/lang/String;");
        if (macAddrString == NULL) {
            ALOGE("Error getting bssid field");
            return false;
        }

        const char *bssid = env->GetStringUTFChars(macAddrString, NULL);
        if (bssid == NULL) {
            ALOGE("Error getting bssid");
            return false;
        }

        mac_addr addr;
        parseMacAddress(bssid, addr);
        memcpy(params.ap[i].bssid, addr, sizeof(mac_addr));

        char bssidOut[32];
        sprintf(bssidOut, "%02x:%02x:%02x:%02x:%02x:%02x", addr[0], addr[1],
            addr[2], addr[3], addr[4], addr[5]);

        params.ap[i].low = getIntField(env, objAp, "low");
        params.ap[i].high = getIntField(env, objAp, "high");

        ALOGD("Added bssid %s, [%04d, %04d]", bssidOut, params.ap[i].low, params.ap[i].high);
    }

    ALOGD("Added %d bssids", params.num_bssid);

    wifi_significant_change_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_significant_change = &onSignificantWifiChange;
    return hal_fn.wifi_set_significant_change_handler(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_untrackSignificantWifiChange(
        JNIEnv *env, jclass cls, jint iface, jint id)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("resetting significant wifi change on interface[%d] = %p", iface, handle);

    return hal_fn.wifi_reset_significant_change_handler(id, handle) == WIFI_SUCCESS;
}

wifi_iface_stat link_stat;
wifi_radio_stat radio_stat; // L release has support for only one radio

void onLinkStatsResults(wifi_request_id id, wifi_iface_stat *iface_stat,
         int num_radios, wifi_radio_stat *radio_stats)
{
    if (iface_stat != 0) {
        memcpy(&link_stat, iface_stat, sizeof(wifi_iface_stat));
    } else {
        memset(&link_stat, 0, sizeof(wifi_iface_stat));
    }

    if (num_radios > 0 && radio_stats != 0) {
        memcpy(&radio_stat, radio_stats, sizeof(wifi_radio_stat));
    } else {
        memset(&radio_stat, 0, sizeof(wifi_radio_stat));
    }
}

static jobject android_net_wifi_getLinkLayerStats (JNIEnv *env, jclass cls, jint iface)  {

    wifi_stats_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_link_stats_results = &onLinkStatsResults;
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    int result = hal_fn.wifi_get_link_stats(0, handle, handler);
    if (result < 0) {
        ALOGE("android_net_wifi_getLinkLayerStats: failed to get link statistics\n");
        return NULL;
    }

    jobject wifiLinkLayerStats = createObject(env, "android/net/wifi/WifiLinkLayerStats");
    if (wifiLinkLayerStats == NULL) {
       ALOGE("Error in allocating wifiLinkLayerStats");
       return NULL;
    }

    setIntField(env, wifiLinkLayerStats, "beacon_rx", link_stat.beacon_rx);
    setIntField(env, wifiLinkLayerStats, "rssi_mgmt", link_stat.rssi_mgmt);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_be", link_stat.ac[WIFI_AC_BE].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_bk", link_stat.ac[WIFI_AC_BK].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_vi", link_stat.ac[WIFI_AC_VI].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_vo", link_stat.ac[WIFI_AC_VO].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_be", link_stat.ac[WIFI_AC_BE].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_bk", link_stat.ac[WIFI_AC_BK].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_vi", link_stat.ac[WIFI_AC_VI].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_vo", link_stat.ac[WIFI_AC_VO].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_be", link_stat.ac[WIFI_AC_BE].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_bk", link_stat.ac[WIFI_AC_BK].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_vi",  link_stat.ac[WIFI_AC_VI].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_vo", link_stat.ac[WIFI_AC_VO].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "retries_be", link_stat.ac[WIFI_AC_BE].retries);
    setLongField(env, wifiLinkLayerStats, "retries_bk", link_stat.ac[WIFI_AC_BK].retries);
    setLongField(env, wifiLinkLayerStats, "retries_vi", link_stat.ac[WIFI_AC_VI].retries);
    setLongField(env, wifiLinkLayerStats, "retries_vo", link_stat.ac[WIFI_AC_VO].retries);


    setIntField(env, wifiLinkLayerStats, "on_time", radio_stat.on_time);
    setIntField(env, wifiLinkLayerStats, "tx_time", radio_stat.tx_time);
    setIntField(env, wifiLinkLayerStats, "rx_time", radio_stat.rx_time);
    setIntField(env, wifiLinkLayerStats, "on_time_scan", radio_stat.on_time_scan);

    return wifiLinkLayerStats;
}

static jint android_net_wifi_getSupportedFeatures(JNIEnv *env, jclass cls, jint iface) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    feature_set set = 0;

    wifi_error result = WIFI_SUCCESS;
    /*
    set = WIFI_FEATURE_INFRA
        | WIFI_FEATURE_INFRA_5G
        | WIFI_FEATURE_HOTSPOT
        | WIFI_FEATURE_P2P
        | WIFI_FEATURE_SOFT_AP
        | WIFI_FEATURE_GSCAN
        | WIFI_FEATURE_PNO
        | WIFI_FEATURE_TDLS
        | WIFI_FEATURE_EPR;
    */

    result = hal_fn.wifi_get_supported_feature_set(handle, &set);
    if (result == WIFI_SUCCESS) {
        ALOGD("wifi_get_supported_feature_set returned set = 0x%x", set);
        return set;
    } else {
        ALOGD("wifi_get_supported_feature_set returned error = 0x%x", result);
        return 0;
    }
}

static void onRttResults(wifi_request_id id, unsigned num_results, wifi_rtt_result* results[]) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onRttResults called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    jclass clsRttResult = (env)->FindClass("android/net/wifi/RttManager$RttResult");
    if (clsRttResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray rttResults = env->NewObjectArray(num_results, clsRttResult, NULL);
    if (rttResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        wifi_rtt_result *result = results[i];

        jobject rttResult = createObject(env, "android/net/wifi/RttManager$RttResult");
        if (rttResult == NULL) {
            ALOGE("Error in creating rtt result");
            return;
        }

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", result->addr[0], result->addr[1],
            result->addr[2], result->addr[3], result->addr[4], result->addr[5]);

        setStringField(env, rttResult, "bssid", bssid);
        setIntField(env,  rttResult, "burstNumber",              result->burst_num);
        setIntField(env,  rttResult, "measurementFrameNumber",   result->measurement_number);
        setIntField(env,  rttResult, "successMeasurementFrameNumber",   result->success_number);
        setIntField(env, rttResult, "frameNumberPerBurstPeer",   result->number_per_burst_peer);
        setIntField(env,  rttResult, "status",                   result->status);
        setIntField(env,  rttResult, "measurementType",          result->type);
        setIntField(env, rttResult, "retryAfterDuration",       result->retry_after_duration);
        setLongField(env, rttResult, "ts",                       result->ts);
        setIntField(env,  rttResult, "rssi",                     result->rssi);
        setIntField(env,  rttResult, "rssiSpread",               result->rssi_spread);
        setIntField(env,  rttResult, "txRate",                   result->tx_rate.bitrate);
        setIntField(env,  rttResult, "rxRate",                   result->rx_rate.bitrate);
        setLongField(env, rttResult, "rtt",                      result->rtt);
        setLongField(env, rttResult, "rttStandardDeviation",     result->rtt_sd);
        setIntField(env,  rttResult, "distance",                 result->distance);
        setIntField(env,  rttResult, "distanceStandardDeviation", result->distance_sd);
        setIntField(env,  rttResult, "distanceSpread",           result->distance_spread);
        setIntField(env,  rttResult, "burstDuration",             result->burst_duration);
        setIntField(env,  rttResult, "negotiatedBurstNum",      result->negotiated_burst_num);
       jobject LCI = createObject(env, "android/net/wifi/RttManager$WifiInformationElement");
       if (result->LCI != NULL && result->LCI->len > 0) {
           ALOGD("Add LCI in result");
           setByteField(env, LCI, "id",           result->LCI->id);
           jbyteArray elements = env->NewByteArray(result->LCI->len);
           jbyte *bytes = (jbyte *)&(result->LCI->data[0]);
           env->SetByteArrayRegion(elements, 0, result->LCI->len, bytes);
           setObjectField(env, LCI, "data", "[B", elements);
           env->DeleteLocalRef(elements);
       } else {
           ALOGD("No LCI in result");
           setByteField(env, LCI, "id",           (byte)(0xff));
         }
       setObjectField(env, rttResult, "LCI",
           "Landroid/net/wifi/RttManager$WifiInformationElement;", LCI);

       jobject LCR = createObject(env, "android/net/wifi/RttManager$WifiInformationElement");
       if (result->LCR != NULL && result->LCR->len > 0) {
           ALOGD("Add LCR in result");
           setByteField(env, LCR, "id",           result->LCR->id);
           jbyteArray elements = env->NewByteArray(result->LCI->len);
           jbyte *bytes = (jbyte *)&(result->LCR->data[0]);
           env->SetByteArrayRegion(elements, 0, result->LCI->len, bytes);
           setObjectField(env, LCR, "data", "[B", elements);
           env->DeleteLocalRef(elements);
       } else {
           ALOGD("No LCR in result");
           setByteField(env, LCR, "id",           (byte)(0xff));
       }
       setObjectField(env, rttResult, "LCR",
           "Landroid/net/wifi/RttManager$WifiInformationElement;", LCR);

        env->SetObjectArrayElement(rttResults, i, rttResult);
        env->DeleteLocalRef(LCI);
        env->DeleteLocalRef(LCR);
        env->DeleteLocalRef(rttResult);
    }

    reportEvent(env, mCls, "onRttResults", "(I[Landroid/net/wifi/RttManager$RttResult;)V",
        id, rttResults);

    //clean the local reference
    env->DeleteLocalRef(rttResults);
    env->DeleteLocalRef(clsRttResult);

}

const int MaxRttConfigs = 16;

static jboolean android_net_wifi_requestRange(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject params)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("sending rtt request [%d] = %p", id, handle);

    wifi_rtt_config configs[MaxRttConfigs];
    memset(&configs, 0, sizeof(configs));

    int len = env->GetArrayLength((jobjectArray)params);
    if (len > MaxRttConfigs) {
        return false;
    }

    for (int i = 0; i < len; i++) {

        jobject param = env->GetObjectArrayElement((jobjectArray)params, i);
        if (param == NULL) {
            ALOGD("could not get element %d", i);
            continue;
        }

        wifi_rtt_config &config = configs[i];

        parseMacAddress(env, param, config.addr);
        config.type = (wifi_rtt_type)getIntField(env, param, "requestType");
        config.peer = (rtt_peer_type)getIntField(env, param, "deviceType");
        config.channel.center_freq = getIntField(env, param, "frequency");
        config.channel.width = (wifi_channel_width) getIntField(env, param, "channelWidth");
        config.channel.center_freq0 = getIntField(env, param, "centerFreq0");
        config.channel.center_freq1 = getIntField(env, param, "centerFreq1");

        config.num_burst = getIntField(env, param, "numberBurst");
        config.burst_period = (unsigned) getIntField(env, param, "interval");
        config.num_frames_per_burst = (unsigned) getIntField(env, param, "numSamplesPerBurst");
        config.num_retries_per_rtt_frame = (unsigned) getIntField(env, param,
                "numRetriesPerMeasurementFrame");
        config.num_retries_per_ftmr = (unsigned) getIntField(env, param, "numRetriesPerFTMR");
        config.LCI_request = getBoolField(env, param, "LCIRequest") ? 1 : 0;
        config.LCR_request = getBoolField(env, param, "LCRRequest") ? 1 : 0;
        config.burst_duration = (unsigned) getIntField(env, param, "burstTimeout");
        config.preamble = (wifi_rtt_preamble) getIntField(env, param, "preamble");
        config.bw = (wifi_rtt_bw) getIntField(env, param, "bandwidth");

        ALOGD("RTT request destination %d: type is %d, peer is %d, bw is %d, center_freq is %d ", i,
                config.type,config.peer, config.channel.width,  config.channel.center_freq0);
        ALOGD("center_freq0 is %d, center_freq1 is %d, num_burst is %d,interval is %d",
                config.channel.center_freq0, config.channel.center_freq1, config.num_burst,
                config.burst_period);
        ALOGD("frames_per_burst is %d, retries of measurement frame is %d, retries_per_ftmr is %d",
                config.num_frames_per_burst, config.num_retries_per_rtt_frame,
                config.num_retries_per_ftmr);
        ALOGD("LCI_requestis %d, LCR_request is %d,  burst_timeout is %d, preamble is %d, bw is %d",
                config.LCI_request, config.LCR_request, config.burst_duration, config.preamble,
                config.bw);
    }

    wifi_rtt_event_handler handler;
    handler.on_rtt_results = &onRttResults;

    return hal_fn.wifi_rtt_range_request(id, handle, len, configs, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_cancelRange(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject params)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("cancelling rtt request [%d] = %p", id, handle);

    mac_addr addrs[MaxRttConfigs];
    memset(&addrs, 0, sizeof(addrs));

    int len = env->GetArrayLength((jobjectArray)params);
    if (len > MaxRttConfigs) {
        return false;
    }

    for (int i = 0; i < len; i++) {

        jobject param = env->GetObjectArrayElement((jobjectArray)params, i);
        if (param == NULL) {
            ALOGD("could not get element %d", i);
            continue;
        }

        parseMacAddress(env, param, addrs[i]);
    }

    return hal_fn.wifi_rtt_range_cancel(id, handle, len, addrs) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_setScanningMacOui(JNIEnv *env, jclass cls,
        jint iface, jbyteArray param)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("setting scan oui %p", handle);

    static const unsigned oui_len = 3;          /* OUI is upper 3 bytes of mac_address */
    int len = env->GetArrayLength(param);
    if (len != oui_len) {
        ALOGE("invalid oui length %d", len);
        return false;
    }

    jbyte* bytes = env->GetByteArrayElements(param, NULL);
    if (bytes == NULL) {
        ALOGE("failed to get array");
        return false;
    }

    return hal_fn.wifi_set_scanning_mac_oui(handle, (byte *)bytes) == WIFI_SUCCESS;
}

static jintArray android_net_wifi_getValidChannels(JNIEnv *env, jclass cls,
        jint iface, jint band)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("getting valid channels %p", handle);

    static const int MaxChannels = 64;
    wifi_channel channels[64];
    int num_channels = 0;
    wifi_error result = hal_fn.wifi_get_valid_channels(handle, band, MaxChannels,
            channels, &num_channels);

    if (result == WIFI_SUCCESS) {
        jintArray channelArray = env->NewIntArray(num_channels);
        if (channelArray == NULL) {
            ALOGE("failed to allocate channel list");
            return NULL;
        }

        env->SetIntArrayRegion(channelArray, 0, num_channels, channels);
        return channelArray;
    } else {
        ALOGE("failed to get channel list : %d", result);
        return NULL;
    }
}

static jboolean android_net_wifi_setDfsFlag(JNIEnv *env, jclass cls, jint iface, jboolean dfs) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("setting dfs flag to %s, %p", dfs ? "true" : "false", handle);

    u32 nodfs = dfs ? 0 : 1;
    wifi_error result = hal_fn.wifi_set_nodfs_flag(handle, nodfs);
    return result == WIFI_SUCCESS;
}

static jobject android_net_wifi_get_rtt_capabilities(JNIEnv *env, jclass cls, jint iface) {
    wifi_rtt_capabilities rtt_capabilities;
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    wifi_error ret = hal_fn.wifi_get_rtt_capabilities(handle, &rtt_capabilities);

    if(WIFI_SUCCESS == ret) {
         jobject capabilities = createObject(env, "android/net/wifi/RttManager$RttCapabilities");
         setBooleanField(env, capabilities, "oneSidedRttSupported",
                 rtt_capabilities.rtt_one_sided_supported == 1);
         setBooleanField(env, capabilities, "twoSided11McRttSupported",
                 rtt_capabilities.rtt_ftm_supported == 1);
         setBooleanField(env, capabilities, "lciSupported",
                 rtt_capabilities.lci_support);
         setBooleanField(env,capabilities, "lcrSupported",
                 rtt_capabilities.lcr_support);
         setIntField(env, capabilities, "preambleSupported",
                 rtt_capabilities.preamble_support);
         setIntField(env, capabilities, "bwSupported",
                 rtt_capabilities.bw_support);
         ALOGD("One side RTT is: %s", rtt_capabilities.rtt_one_sided_supported ==1 ? "support" :
                 "not support");
         ALOGD("Two side RTT is: %s", rtt_capabilities.rtt_ftm_supported == 1 ? "support" :
                 "not support");
         ALOGD("LCR is: %s", rtt_capabilities.lcr_support == 1 ? "support" : "not support");

         ALOGD("LCI is: %s", rtt_capabilities.lci_support == 1 ? "support" : "not support");

         ALOGD("Support Preamble is : %d support BW is %d", rtt_capabilities.preamble_support,
                 rtt_capabilities.bw_support);
         return capabilities;
    } else {
        return NULL;
    }
}

static jboolean android_net_wifi_set_Country_Code_Hal(JNIEnv *env,jclass cls, jint iface,
        jstring country_code) {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    const char *country = env->GetStringUTFChars(country_code, NULL);

    ALOGD("set country code: %s", country);
    wifi_error res = hal_fn.wifi_set_country_code(handle, country);
    env->ReleaseStringUTFChars(country_code, country);

    return res == WIFI_SUCCESS;
}

static jboolean android_net_wifi_enable_disable_tdls(JNIEnv *env,jclass cls, jint iface,
        jboolean enable, jstring addr) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);

    mac_addr address;
    parseMacAddress(env, addr, address);
    wifi_tdls_handler tdls_handler;
    //tdls_handler.on_tdls_state_changed = &on_tdls_state_changed;

    if(enable) {
        return (hal_fn.wifi_enable_tdls(handle, address, NULL, tdls_handler) == WIFI_SUCCESS);
    } else {
        return (hal_fn.wifi_disable_tdls(handle, address) == WIFI_SUCCESS);
    }
}

static void on_tdls_state_changed(mac_addr addr, wifi_tdls_status status) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("on_tdls_state_changed is called: vm = %p, obj = %p, env = %p", mVM, mCls, env);

    char mac[32];
    sprintf(mac, "%02x:%02x:%02x:%02x:%02x:%02x", addr[0], addr[1], addr[2], addr[3], addr[4],
            addr[5]);

    jstring mac_address = env->NewStringUTF(mac);
    reportEvent(env, mCls, "onTdlsStatus", "(Ljava/lang/StringII;)V",
        mac_address, status.state, status.reason);

}

static jobject android_net_wifi_get_tdls_status(JNIEnv *env,jclass cls, jint iface,jstring addr) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);

    mac_addr address;
    parseMacAddress(env, addr, address);

    wifi_tdls_status status;

    wifi_error ret;
    ret = hal_fn.wifi_get_tdls_status(handle, address, &status );

    if (ret != WIFI_SUCCESS) {
        return NULL;
    } else {
        jobject tdls_status = createObject(env, "com/android/server/wifi/WifiNative$TdlsStatus");
        setIntField(env, tdls_status, "channel", status.channel);
        setIntField(env, tdls_status, "global_operating_class", status.global_operating_class);
        setIntField(env, tdls_status, "state", status.state);
        setIntField(env, tdls_status, "reason", status.reason);
        return tdls_status;
    }
}

static jobject android_net_wifi_get_tdls_capabilities(JNIEnv *env, jclass cls, jint iface) {
    wifi_tdls_capabilities tdls_capabilities;
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    wifi_error ret = hal_fn.wifi_get_tdls_capabilities(handle, &tdls_capabilities);

    if(WIFI_SUCCESS == ret) {
         jobject capabilities = createObject(env,
                 "com/android/server/wifi/WifiNative$TdlsCapabilities");
         setIntField(env, capabilities, "maxConcurrentTdlsSessionNumber",
                 tdls_capabilities.max_concurrent_tdls_session_num);
         setBooleanField(env, capabilities, "isGlobalTdlsSupported",
                 tdls_capabilities.is_global_tdls_supported == 1);
         setBooleanField(env, capabilities, "isPerMacTdlsSupported",
                 tdls_capabilities.is_per_mac_tdls_supported == 1);
         setBooleanField(env,capabilities, "isOffChannelTdlsSupported",
                 tdls_capabilities.is_off_channel_tdls_supported);

         ALOGD("TDLS Max Concurrent Tdls Session Number is: %d",
                 tdls_capabilities.max_concurrent_tdls_session_num);
         ALOGD("Global Tdls is: %s", tdls_capabilities.is_global_tdls_supported == 1 ? "support" :
                 "not support");
         ALOGD("Per Mac Tdls is: %s", tdls_capabilities.is_per_mac_tdls_supported == 1 ? "support" :
                 "not support");
         ALOGD("Off Channel Tdls is: %s", tdls_capabilities.is_off_channel_tdls_supported == 1 ?
                 "support" : "not support");

         return capabilities;
    } else {
        return NULL;
    }
}

// ----------------------------------------------------------------------------
// Debug framework
// ----------------------------------------------------------------------------

static void onRingBufferData(char * ring_name, char * buffer,
int buffer_size, wifi_ring_buffer_status *status) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onRingBufferData called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    reportEvent(env, mCls, "onDataAvailable", "(I[Landroid/net/wifi/WiFiLogger$LogData;)V",
        0, 0);
}

static jboolean android_net_wifi_start_logging(JNIEnv *env, jclass cls, jint iface) {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("android_net_wifi_start_logging = %p", handle);

    if (handle == 0) {
        return WIFI_ERROR_UNINITIALIZED;
    }
    wifi_ring_buffer_data_handler handler;
    handler.on_ring_buffer_data = &onRingBufferData;

    wifi_error result = WIFI_SUCCESS; //ifi_start_logging(handle, 1, 0, 5, 4*1024,(u8*)"wifi_connectivity_events", handler);

    return result;
}

// ----------------------------------------------------------------------------
// ePno framework
// ----------------------------------------------------------------------------


static void onPnoNetworkFound(wifi_request_id id,
                                          unsigned num_results, wifi_scan_result *results) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onPnoNetworkFound called, vm = %p, obj = %p, env = %p, num_results %u",
            mVM, mCls, env, num_results);

    if (results == 0 || num_results == 0) {
       ALOGE("onPnoNetworkFound: Error no results");
       return;
    }

    jobject scanResult;
    jbyte *bytes;
    jobjectArray scanResults;
    //jbyteArray elements;

    for (unsigned i=0; i<num_results; i++) {

        scanResult = createScanResult(env, &results[i]);
        if (i == 0) {
            scanResults = env->NewObjectArray(num_results,
                    env->FindClass("android/net/wifi/ScanResult"), scanResult);
            if (scanResults == 0) {
                ALOGD("cant allocate array");
            } else {
                ALOGD("allocated array %u", env->GetArrayLength(scanResults));
            }
        } else {
            env->SetObjectArrayElement(scanResults, i, scanResult);
        }

        ALOGD("Scan result with ie length %d, i %u, <%s> rssi=%d %02x:%02x:%02x:%02x:%02x:%02x", results->ie_length, i,
            results[i].ssid, results[i].rssi, results[i].bssid[0], results[i].bssid[1],
            results[i].bssid[2], results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);

        /*elements = env->NewByteArray(results->ie_length);
        if (elements == NULL) {
            ALOGE("Error in allocating array");
            return;
        }*/

        //ALOGD("onPnoNetworkFound: Setting byte array");

        //bytes = (jbyte *)&(results->ie_data[0]);
        //env->SetByteArrayRegion(elements, 0, results->ie_length, bytes);

        //ALOGD("onPnoNetworkFound: Returning result");
    }


    ALOGD("calling report");

    reportEvent(env, mCls, "onPnoNetworkFound", "(I[Landroid/net/wifi/ScanResult;)V", id,
               scanResults);
        ALOGD("free ref");

    env->DeleteLocalRef(scanResults);
    //env->DeleteLocalRef(elements);
}

static jboolean android_net_wifi_setPnoListNative(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject list)  {

    wifi_epno_handler handler;
    handler.on_network_found = &onPnoNetworkFound;

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("configure ePno list request [%d] = %p", id, handle);

    if (list == NULL) {
        // stop pno
        int result = hal_fn.wifi_set_epno_list(id, handle, 0, NULL, handler);
        ALOGE(" setPnoListNative: STOP result = %d", result);
        return result;
    }

    wifi_epno_network net_list[MAX_PNO_SSID];
    memset(&net_list, 0, sizeof(net_list));

    size_t len = env->GetArrayLength((jobjectArray)list);
    if (len > (size_t)MAX_PNO_SSID) {
        return false;
    }

    for (unsigned int i = 0; i < len; i++) {

        jobject pno_net = env->GetObjectArrayElement((jobjectArray)list, i);
        if (pno_net == NULL) {
            ALOGD("setPnoListNative: could not get element %d", i);
            continue;
        }

        jstring sssid = (jstring) getObjectField(
                   env, pno_net, "SSID", "Ljava/lang/String;");
        if (sssid == NULL) {
              ALOGE("Error setPnoListNative: getting ssid field");
              return false;
        }

        const char *ssid = env->GetStringUTFChars(sssid, NULL);
        if (ssid == NULL) {
             ALOGE("Error setPnoListNative: getting ssid");
             return false;
        }
        int ssid_len = strnlen((const char*)ssid, 33);
        if (ssid_len > 32) {
           ALOGE("Error setPnoListNative: long ssid %u", strnlen((const char*)ssid, 256));
           return false;
        }
        if (ssid_len > 1 && ssid[0] == '"' && ssid[ssid_len-1])
        {
            // strip leading and trailing '"'
            ssid++;
            ssid_len-=2;
        }
        if (ssid_len == 0) {
            ALOGE("Error setPnoListNative: zero length ssid, skip it");
            continue;
        }
        memcpy(net_list[i].ssid, ssid, ssid_len);

        int rssit = getIntField(env, pno_net, "rssi_threshold");
        net_list[i].rssi_threshold = (byte)rssit;
        int a = getIntField(env, pno_net, "auth");
        net_list[i].auth_bit_field = a;
        int f = getIntField(env, pno_net, "flags");
        net_list[i].flags = f;
        ALOGE(" setPnoListNative: idx %u rssi %d/%d auth %x/%x flags %x/%x [%s]", i, (signed byte)net_list[i].rssi_threshold, net_list[i].rssi_threshold, net_list[i].auth_bit_field, a, net_list[i].flags, f, net_list[i].ssid);
    }

    int result = hal_fn.wifi_set_epno_list(id, handle, len, net_list, handler);
    ALOGE(" setPnoListNative: result %d", result);

    return result >= 0;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gWifiMethods[] = {
    /* name, signature, funcPtr */

    { "loadDriver", "()Z",  (void *)android_net_wifi_loadDriver },
    { "isDriverLoaded", "()Z",  (void *)android_net_wifi_isDriverLoaded },
    { "unloadDriver", "()Z",  (void *)android_net_wifi_unloadDriver },
    { "startSupplicant", "(Z)Z",  (void *)android_net_wifi_startSupplicant },
    { "killSupplicant", "(Z)Z",  (void *)android_net_wifi_killSupplicant },
    { "connectToSupplicantNative", "()Z", (void *)android_net_wifi_connectToSupplicant },
    { "closeSupplicantConnectionNative", "()V",
            (void *)android_net_wifi_closeSupplicantConnection },
    { "waitForEventNative", "()Ljava/lang/String;", (void*)android_net_wifi_waitForEvent },
    { "doBooleanCommandNative", "(Ljava/lang/String;)Z", (void*)android_net_wifi_doBooleanCommand },
    { "doIntCommandNative", "(Ljava/lang/String;)I", (void*)android_net_wifi_doIntCommand },
    { "doStringCommandNative", "(Ljava/lang/String;)Ljava/lang/String;",
            (void*) android_net_wifi_doStringCommand },
    { "startHalNative", "()Z", (void*) android_net_wifi_startHal },
    { "stopHalNative", "()V", (void*) android_net_wifi_stopHal },
    { "waitForHalEventNative", "()V", (void*) android_net_wifi_waitForHalEvents },
    { "getInterfacesNative", "()I", (void*) android_net_wifi_getInterfaces},
    { "getInterfaceNameNative", "(I)Ljava/lang/String;", (void*) android_net_wifi_getInterfaceName},
    { "getScanCapabilitiesNative", "(ILcom/android/server/wifi/WifiNative$ScanCapabilities;)Z",
            (void *) android_net_wifi_getScanCapabilities},
    { "startScanNative", "(IILcom/android/server/wifi/WifiNative$ScanSettings;)Z",
            (void*) android_net_wifi_startScan},
    { "stopScanNative", "(II)Z", (void*) android_net_wifi_stopScan},
    { "getScanResultsNative", "(IZ)[Landroid/net/wifi/WifiScanner$ScanData;",
            (void *) android_net_wifi_getScanResults},
    { "setHotlistNative", "(IILandroid/net/wifi/WifiScanner$HotlistSettings;)Z",
            (void*) android_net_wifi_setHotlist},
    { "resetHotlistNative", "(II)Z", (void*) android_net_wifi_resetHotlist},
    { "trackSignificantWifiChangeNative", "(IILandroid/net/wifi/WifiScanner$WifiChangeSettings;)Z",
            (void*) android_net_wifi_trackSignificantWifiChange},
    { "untrackSignificantWifiChangeNative", "(II)Z",
            (void*) android_net_wifi_untrackSignificantWifiChange},
    { "getWifiLinkLayerStatsNative", "(I)Landroid/net/wifi/WifiLinkLayerStats;",
            (void*) android_net_wifi_getLinkLayerStats},
    { "getSupportedFeatureSetNative", "(I)I",
            (void*) android_net_wifi_getSupportedFeatures},
    { "requestRangeNative", "(II[Landroid/net/wifi/RttManager$RttParams;)Z",
            (void*) android_net_wifi_requestRange},
    { "cancelRangeRequestNative", "(II[Landroid/net/wifi/RttManager$RttParams;)Z",
            (void*) android_net_wifi_cancelRange},
    { "setScanningMacOuiNative", "(I[B)Z",  (void*) android_net_wifi_setScanningMacOui},
    { "getChannelsForBandNative", "(II)[I", (void*) android_net_wifi_getValidChannels},
    { "setDfsFlagNative",         "(IZ)Z",  (void*) android_net_wifi_setDfsFlag},
    { "toggleInterfaceNative",    "(I)Z",  (void*) android_net_wifi_toggle_interface},
    { "getRttCapabilitiesNative", "(I)Landroid/net/wifi/RttManager$RttCapabilities;",
            (void*) android_net_wifi_get_rtt_capabilities},
    { "startLogging", "(I)Z", (void*) android_net_wifi_start_logging},
    {"setCountryCodeHalNative", "(ILjava/lang/String;)Z",
            (void*) android_net_wifi_set_Country_Code_Hal},
    { "setPnoListNative", "(II[Lcom/android/server/wifi/WifiNative$WifiPnoNetwork;)Z",
            (void*) android_net_wifi_setPnoListNative},
    {"enableDisableTdlsNative", "(IZLjava/lang/String;)Z",
            (void*) android_net_wifi_enable_disable_tdls},
    {"getTdlsStatusNative", "(ILjava/lang/String;)Lcom/android/server/wifi/WifiNative$TdlsStatus;",
            (void*) android_net_wifi_get_tdls_status},
    {"getTdlsCapabilitiesNative", "(I)Lcom/android/server/wifi/WifiNative$TdlsCapabilities;",
            (void*) android_net_wifi_get_tdls_capabilities}
};

int register_android_net_wifi_WifiNative(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env,
            "com/android/server/wifi/WifiNative", gWifiMethods, NELEM(gWifiMethods));
}


/* User to register native functions */
extern "C"
jint Java_com_android_server_wifi_WifiNative_registerNatives(JNIEnv* env, jclass clazz) {
    return AndroidRuntime::registerNativeMethods(env,
            "com/android/server/wifi/WifiNative", gWifiMethods, NELEM(gWifiMethods));
}

}; // namespace android
