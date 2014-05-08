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

static void saveEventMethodIds(JNIEnv *env, jobject obj) {

    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        ALOGE("Error in accessing class");
        return;
    } else {
        ALOGD("cls = %p", cls);
    }

    jmethodID method = env->GetMethodID(cls, "onScanResults",
            "(I[Lcom/android/server/wifi/WifiNative$ScanResult;)V");

    if (method == NULL) {
        ALOGE("Error in getting method ID");
        return;
    } else {
        ALOGD("method = %p", method);
    }
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

static void onScanResults(wifi_request_id id, unsigned num_results, wifi_scan_result *results) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onScanResults called, vm = %p, obj = %p, env = %p", mVM, mObj, env);

    jclass clsScanResult = (env)->FindClass("com/android/server/wifi/WifiNative$ScanResult");
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

        jobject scanResult = createObject(env, "com/android/server/wifi/WifiNative$ScanResult");
        if (scanResult == NULL) {
            ALOGE("Error in creating scan result");
            return;
        }

        setStringField(env, scanResult, "SSID", results[i].ssid);

        char bssid[32];
        sprintf(bssid, "%0x:%0x:%0x:%0x:%0x:%0x", results[i].bssid[0], results[i].bssid[1],
            results[i].bssid[2], results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);

        setStringField(env, scanResult, "BSSID", bssid);

        setIntField(env, scanResult, "level", results[i].rssi);
        setLongField(env, scanResult, "timestamp", results[i].ts);
        setIntField(env, scanResult, "frequency", results[i].channel);

        env->SetObjectArrayElement(scanResults, i, scanResult);
    }

    reportEvent(env, mObj, "onScanResults",
            "(I[Lcom/android/server/wifi/WifiNative$ScanResult;)V", id, scanResults);
}

static jboolean android_net_wifi_startScan(JNIEnv *env, jobject obj, jint iface, jint id) {

    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("starting scan on interface[%d] = %p", iface, handle);

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results = &onScanResults;

    return wifi_start_gscan(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_stopScan(JNIEnv *env, jobject obj, jint iface, jint id) {
    wifi_interface_handle handle = getIfaceHandle(env, obj, iface);
    ALOGD("stopping scan on interface[%d] = %p", iface, handle);

    return wifi_stop_gscan(id, handle);
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
    { "startScanNative", "(II)Z", (void*) android_net_wifi_startScan},
    { "stopScanNative", "(II)Z", (void*) android_net_wifi_stopScan}
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
