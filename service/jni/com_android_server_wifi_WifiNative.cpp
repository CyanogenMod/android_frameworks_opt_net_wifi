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

#include "wifi.h"
#include "wifi_hal.h"
#include "jni_helper.h"

#define REPLY_BUF_SIZE 4096 // wpa_supplicant's maximum size.
#define EVENT_BUF_SIZE 2048

namespace android {

static jint DBG = false;

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

static jobject mObj;                            /* saved WifiNative object */
static JavaVM *mVM;                             /* saved JVM pointer */

static const char *WifiHandleVarName = "mWifiHalHandle";
static const char *WifiIfaceHandleVarName = "mWifiIfaceHandles";
static jmethodID OnScanResultsMethodID;

static JNIEnv *getEnv() {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);
    return env;
}

static wifi_handle getWifiHandle(JNIEnv *env, jobject obj) {
    return (wifi_handle) getLongField(env, obj, WifiHandleVarName);
}

static wifi_interface_handle getIfaceHandle(JNIEnv *env, jobject obj, jint index) {
    return (wifi_interface_handle) getLongArrayField(env, obj, WifiIfaceHandleVarName, index);
}

static jboolean android_net_wifi_startHal(JNIEnv* env, jobject obj) {
    ALOGD("In wifi start Hal");
    wifi_handle halHandle = getWifiHandle(env, obj);
    if (halHandle == NULL) {
        wifi_error res = wifi_initialize(&halHandle);
        if (res == WIFI_SUCCESS) {
            setLongField(env, obj, WifiHandleVarName, (jlong)halHandle);
        }
        env->GetJavaVM(&mVM);
        mObj = env->NewGlobalRef(obj);
        ALOGD("halHandle = %p, mVM = %p, mObj = %p", halHandle, mVM, mObj);
        return res == WIFI_SUCCESS;
    } else {
        return true;
    }
}

void android_net_wifi_hal_cleaned_up_handler(wifi_handle handle) {
    ALOGD("In wifi cleaned up handler");

    JNIEnv * env = getEnv();
    setLongField(env, mObj, WifiHandleVarName, 0);
    env->DeleteGlobalRef(mObj);
    mObj = NULL;
    mVM  = NULL;
}

static void android_net_wifi_stopHal(JNIEnv* env, jobject obj) {
    ALOGD("In wifi stop Hal");
    wifi_handle halHandle = getWifiHandle(env, obj);
    wifi_cleanup(halHandle, android_net_wifi_hal_cleaned_up_handler);
}

static void android_net_wifi_waitForHalEvents(JNIEnv* env, jobject obj) {

    ALOGD("waitForHalEvents called, vm = %p, obj = %p, env = %p", mVM, mObj, env);

    wifi_handle halHandle = getWifiHandle(env, obj);
    ALOGD("halHandle = %p", halHandle);
    wifi_event_loop(halHandle);
}

static int android_net_wifi_getInterfaces(JNIEnv *env, jobject obj) {
    int n = 0;
    wifi_handle halHandle = getWifiHandle(env, obj);
    wifi_interface_handle *ifaceHandles = NULL;
    int result = wifi_get_ifaces(halHandle, &n, &ifaceHandles);
    if (result < 0) {
        return result;
    }

    jlongArray array = (env)->NewLongArray(n);
    if (array == NULL) {
        THROW(env, "Error in accessing array");
        return 0;
    }

    jlong elems[8];
    if (n > 8) {
        THROW(env, "Too many interfaces");
        return 0;
    }

    for (int i = 0; i < n; i++) {
        elems[i] = reinterpret_cast<jlong>(ifaceHandles[i]);
    }

    env->SetLongArrayRegion(array, 0, n, elems);

    setLongArrayField(env, obj, WifiIfaceHandleVarName, array);
    return (result < 0) ? result : n;
}

static jstring android_net_wifi_getInterfaceName(JNIEnv *env, jobject obj, jint i) {
    char buf[EVENT_BUF_SIZE];

    jlong value = getLongArrayField(env, obj, WifiIfaceHandleVarName, i);
    wifi_interface_handle handle = (wifi_interface_handle) value;
    int result = ::wifi_get_iface_name(handle, buf, sizeof(buf));
    if (result < 0) {
        return NULL;
    } else {
        return env->NewStringUTF(buf);
    }
}

static void onScanResultsAvailable(wifi_request_id id, unsigned num_results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onScanResultsAvailable called, vm = %p, obj = %p, env = %p", mVM, mObj, env);

    reportEvent(env, mObj, "onScanResultsAvailable", "(I)V", id);
}

static jboolean android_net_wifi_startScan(
        JNIEnv *env, jobject obj, jint iface, jint id, jobject settings) {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("starting scan on interface[%d] = %p", iface, handle);

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));
    
    params.base_period = getIntField(env, settings, "base_period_ms");
    params.max_ap_per_scan = getIntField(env, settings, "max_ap_per_scan");
    params.report_threshold = getIntField(env, settings, "report_threshold");
    
    ALOGD("Initialized common fields %d, %d, %d", params.base_period,
            params.max_ap_per_scan, params.report_threshold);

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

            ALOGD("Initialized channel %d", params.buckets[i].channels[j].channel);
        }
    }

    ALOGD("Initialized all fields");

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = &onScanResultsAvailable;

    return wifi_start_gscan(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_stopScan(JNIEnv *env, jobject obj, jint iface, jint id) {
    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("stopping scan on interface[%d] = %p", iface, handle);

    return wifi_stop_gscan(id, handle)  == WIFI_SUCCESS;
}

static jobject android_net_wifi_getScanResults(
        JNIEnv *env, jobject obj, jint iface, jboolean flush)  {
    
    wifi_scan_result results[256];
    int num_results = 256;
    
    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("getting scan results on interface[%d] = %p", iface, handle);
    
    int result = wifi_get_cached_gscan_results(handle, 1, results, &num_results);
    if (result == WIFI_SUCCESS) {
        jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
        if (clsScanResult == NULL) {
            ALOGE("Error in accessing class");
            return NULL;
        }

        jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
        if (scanResults == NULL) {
            ALOGE("Error in allocating array");
            return NULL;
        }

        for (int i = 0; i < num_results; i++) {

            jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
            if (scanResult == NULL) {
                ALOGE("Error in creating scan result");
                return NULL;
            }

            setStringField(env, scanResult, "SSID", results[i].ssid);

            char bssid[32];
            sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", results[i].bssid[0],
                    results[i].bssid[1], results[i].bssid[2], results[i].bssid[3],
                    results[i].bssid[4], results[i].bssid[5]);

            setStringField(env, scanResult, "BSSID", bssid);

            setIntField(env, scanResult, "level", results[i].rssi);
            setIntField(env, scanResult, "frequency", results[i].channel);
            setLongField(env, scanResult, "timestamp", results[i].ts);

            env->SetObjectArrayElement(scanResults, i, scanResult);
            env->DeleteLocalRef(scanResult);
        }

        return scanResults;
    } else {
        return NULL;
    }
}


static jboolean android_net_wifi_getScanCapabilities(
        JNIEnv *env, jobject obj, jint iface, jobject capabilities) {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("getting scan capabilities on interface[%d] = %p", iface, handle);

    wifi_gscan_capabilities c;
    memset(&c, 0, sizeof(c));
    int result = wifi_get_gscan_capabilities(handle, &c);
    if (result != WIFI_SUCCESS) {
        ALOGD("failed to get capabilities : %d", result);
        return JNI_FALSE;
    }

    setIntField(env, capabilities, "max_scan_cache_size", c.max_scan_cache_size);
    setIntField(env, capabilities, "max_scan_buckets", c.max_scan_buckets);
    setIntField(env, capabilities, "max_ap_cache_per_scan", c.max_ap_cache_per_scan);
    setIntField(env, capabilities, "max_rssi_sample_size", c.max_rssi_sample_size);
    setIntField(env, capabilities, "max_scan_reporting_threshold", c.max_scan_reporting_threshold);
    setIntField(env, capabilities, "max_hotlist_aps", c.max_hotlist_aps);
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

static void onHotlistApFound(wifi_request_id id,
        unsigned num_results, wifi_scan_result *results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onHotlistApFound called, vm = %p, obj = %p, env = %p, num_results = %d",
            mVM, mObj, env, num_results);

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

    reportEvent(env, mObj, "onHotlistApFound", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);
}

static jboolean android_net_wifi_setHotlist(
        JNIEnv *env, jobject obj, jint iface, jint id, jobject ap)  {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("setting hotlist on interface[%d] = %p", iface, handle);

    wifi_bssid_hotlist_params params;
    memset(&params, 0, sizeof(params));

    jobjectArray array = (jobjectArray) getObjectField(env, ap,
            "hotspotInfos", "[Landroid/net/wifi/WifiScanner$HotspotInfo;");
    params.num = env->GetArrayLength(array);

    if (params.num == 0) {
        ALOGE("Error in accesing array");
        return false;
    }

    for (int i = 0; i < params.num; i++) {
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
        parseMacAddress(bssid, params.bssids[i].bssid);

        mac_addr addr;
        memcpy(addr, params.bssids[i].bssid, sizeof(mac_addr));

        char bssidOut[32];
        sprintf(bssidOut, "%0x:%0x:%0x:%0x:%0x:%0x", addr[0], addr[1],
            addr[2], addr[3], addr[4], addr[5]);

        ALOGD("Added bssid %s", bssidOut);

        params.bssids[i].low = getIntField(env, objAp, "low");
        params.bssids[i].high = getIntField(env, objAp, "high");
    }

    wifi_hotlist_ap_found_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_hotlist_ap_found = &onHotlistApFound;
    return wifi_set_bssid_hotlist(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_resetHotlist(
        JNIEnv *env, jobject obj, jint iface, jint id)  {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("resetting hotlist on interface[%d] = %p", iface, handle);

    return wifi_reset_bssid_hotlist(id, handle) == WIFI_SUCCESS;
}

void onSignificantWifiChange(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onSignificantWifiChange called, vm = %p, obj = %p, env = %p", mVM, mObj, env);

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
    }

    reportEvent(env, mObj, "onSignificantWifiChange", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);

}

static jboolean android_net_wifi_trackSignificantWifiChange(
        JNIEnv *env, jobject obj, jint iface, jint id, jobject settings)  {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("tracking significant wifi change on interface[%d] = %p", iface, handle);

    wifi_significant_change_params params;
    memset(&params, 0, sizeof(params));

    params.rssi_sample_size = getIntField(env, settings, "rssiSampleSize");
    params.lost_ap_sample_size = getIntField(env, settings, "lostApSampleSize");
    params.min_breaching = getIntField(env, settings, "minApsBreachingThreshold");

    const char *hotspot_info_array_type = "[Landroid/net/wifi/WifiScanner$HotspotInfo;";
    jobjectArray hotspots = (jobjectArray)getObjectField(
                env, settings, "hotspotInfos", hotspot_info_array_type);
    params.num = env->GetArrayLength(hotspots);

    if (params.num == 0) {
        ALOGE("Error in accesing array");
        return false;
    }

    ALOGD("Initialized common fields %d, %d, %d, %d", params.rssi_sample_size,
            params.lost_ap_sample_size, params.min_breaching, params.num);

    for (int i = 0; i < params.num; i++) {
        jobject objAp = env->GetObjectArrayElement(hotspots, i);

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
        memcpy(params.bssids[i].bssid, addr, sizeof(mac_addr));

        char bssidOut[32];
        sprintf(bssidOut, "%0x:%0x:%0x:%0x:%0x:%0x", addr[0], addr[1],
            addr[2], addr[3], addr[4], addr[5]);

        params.bssids[i].low = getIntField(env, objAp, "low");
        params.bssids[i].high = getIntField(env, objAp, "high");

        ALOGD("Added bssid %s, [%04d, %04d]", bssidOut, params.bssids[i].low, params.bssids[i].high);
    }

    ALOGD("Added %d bssids", params.num);

    wifi_significant_change_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_significant_change = &onSignificantWifiChange;
    return wifi_set_significant_change_handler(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_untrackSignificantWifiChange(
        JNIEnv *env, jobject obj, jint iface, jint id)  {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("resetting significant wifi change on interface[%d] = %p", iface, handle);

    return wifi_reset_significant_change_handler(id, handle) == WIFI_SUCCESS;
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
    { "getScanResultsNative", "(IZ)[Landroid/net/wifi/ScanResult;",
            (void *) android_net_wifi_getScanResults},

    { "setHotlistNative", "(IILandroid/net/wifi/WifiScanner$HotlistSettings;)Z",
            (void*) android_net_wifi_setHotlist},
    { "resetHotlistNative", "(II)Z", (void*) android_net_wifi_resetHotlist},


    { "trackSignificantWifiChangeNative", "(IILandroid/net/wifi/WifiScanner$WifiChangeSettings;)Z",
            (void*) android_net_wifi_trackSignificantWifiChange},
    { "untrackSignificantWifiChangeNative", "(II)Z",
            (void*) android_net_wifi_untrackSignificantWifiChange}
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
