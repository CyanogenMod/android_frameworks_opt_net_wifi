# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)

ifneq ($(TARGET_BUILD_PDK), true)

include $(CLEAR_VARS)

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/java
LOCAL_SRC_FILES := $(call all-java-files-under, java) \
	$(call all-Iaidl-files-under, java) \
	$(call all-logtags-files-under, java)

LOCAL_JNI_SHARED_LIBRARIES := libandroid_runtime
LOCAL_JAVA_LIBRARIES := services
LOCAL_REQUIRED_MODULES := services
LOCAL_MODULE_TAGS :=
LOCAL_MODULE := wifi-service

include $(BUILD_JAVA_LIBRARY)

# Make the HAL library
# ============================================================
include $(CLEAR_VARS)

LOCAL_REQUIRED_MODULES :=

LOCAL_CFLAGS += -Wno-unused-parameter -Wno-int-to-pointer-cast
LOCAL_CFLAGS += -Wno-maybe-uninitialized -Wno-parentheses
LOCAL_CPPFLAGS += -Wno-conversion-null

LOCAL_C_INCLUDES += \
	external/libnl/include

LOCAL_SRC_FILES := \
	lib/wifi_hal.cpp \
	lib/common.cpp \
	lib/cpp_bindings.cpp \
	lib/gscan.cpp 

LOCAL_MODULE := libwifi-hal

include $(BUILD_STATIC_LIBRARY)

# Make the JNI part
# ============================================================
include $(CLEAR_VARS)

LOCAL_REQUIRED_MODULES := libandroid_runtime libhardware_legacy

LOCAL_CFLAGS += -Wno-unused-parameter -Wno-int-to-pointer-cast
LOCAL_CFLAGS += -Wno-maybe-uninitialized -Wno-parentheses
LOCAL_CPPFLAGS += -Wno-conversion-null

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(call include-path-for, libhardware)/hardware \
	$(call include-path-for, libhardware_legacy)/hardware_legacy \
	libcore/include \
	$(LOCAL_PATH)/lib

LOCAL_SHARED_LIBRARIES += \
	libnativehelper \
	libcutils \
	libutils \
	libhardware \
	libhardware_legacy \
	libandroid_runtime  \
	libnl

LOCAL_STATIC_LIBRARIES += libwifi-hal

LOCAL_SRC_FILES := \
	jni/com_android_server_wifi_WifiNative.cpp \
	jni/jni_helper.cpp

LOCAL_MODULE := libwifi-service

include $(BUILD_SHARED_LIBRARY)

# Build the halutil
# ============================================================

include $(CLEAR_VARS)

LOCAL_REQUIRED_MODULES := libandroid_runtime libhardware_legacy

LOCAL_CFLAGS += -Wno-unused-parameter -Wno-int-to-pointer-cast
LOCAL_CFLAGS += -Wno-maybe-uninitialized -Wno-parentheses
LOCAL_CPPFLAGS += -Wno-conversion-null

LOCAL_C_INCLUDES += \
	libcore/include \
	$(LOCAL_PATH)/lib

LOCAL_SHARES_LIBRARIES += \
	libcutils \
	libutils \
	libandroid_runtime

LOCAL_STATIC_LIBRARIES := libwifi-hal libnl_2

LOCAL_SHARED_LIBRARIES += \
	libnativehelper \
	libcutils \
	libutils \
	libhardware \
	libhardware_legacy

LOCAL_SRC_FILES := \
	tools/halutil/halutil.cpp

LOCAL_MODULE := halutil

include $(BUILD_EXECUTABLE)

