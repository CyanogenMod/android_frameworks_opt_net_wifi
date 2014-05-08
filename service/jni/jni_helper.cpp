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

namespace android {

/* JNI Helpers for wifi_hal implementation */

void throwException( JNIEnv *env, const char *message, int line )
{
    ALOGE("error at line %d: %s", line, message);

    const char *className = "java/lang/Exception";

    jclass exClass = (env)->FindClass(className );
    if ( exClass == NULL ) {
        ALOGE("Could not find exception class to throw error");
        ALOGE("error at line %d: %s", line, message);
        return;
    }

    (env)->ThrowNew(exClass, message);
}

jlong getLongField(JNIEnv *env, jobject obj, const char *name)
{
    jclass cls = (env)->GetObjectClass(obj);
    jfieldID field = (env)->GetFieldID(cls, name, "J");
    if (field == 0) {
        THROW(env, "Error in accessing field");
        return 0;
    }

    jlong value = (env)->GetLongField(obj, field);
    return value;
}

jlong getLongArrayField(JNIEnv *env, jobject obj, const char *name, int index)
{
    jclass cls = (env)->GetObjectClass(obj);
    jfieldID field = (env)->GetFieldID(cls, name, "[J");
    if (field == 0) {
        THROW(env, "Error in accessing field definition");
        return 0;
    }

    jlongArray array = (jlongArray)(env)->GetObjectField(obj, field);
    jlong *elem = (env)->GetLongArrayElements(array, 0);
    if (elem == NULL) {
        THROW(env, "Error in accessing index element");
        return 0;
    }

    jlong value = elem[index];
    (env)->ReleaseLongArrayElements(array, elem, 0);
    return value;
}

void setIntField(JNIEnv *env, jobject obj, const char *name, jint value)
{
    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        THROW(env, "Error in accessing class");
        return;
    }

    jfieldID field = (env)->GetFieldID(cls, name, "I");
    if (field == NULL) {
        THROW(env, "Error in accessing field");
        return;
    }

    (env)->SetIntField(obj, field, value);
}

void setLongField(JNIEnv *env, jobject obj, const char *name, jlong value)
{
    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        THROW(env, "Error in accessing class");
        return;
    }

    jfieldID field = (env)->GetFieldID(cls, name, "J");
    if (field == NULL) {
        THROW(env, "Error in accessing field");
        return;
    }

    (env)->SetLongField(obj, field, value);
}

void setLongArrayField(JNIEnv *env, jobject obj, const char *name, jlongArray value)
{
    ALOGD("setting long array field");

    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        THROW(env, "Error in accessing field");
        return;
    } else {
        ALOGD("cls = %p", cls);
    }

    jfieldID field = (env)->GetFieldID(cls, name, "[J");
    if (field == NULL) {
        THROW(env, "Error in accessing field");
        return;
    }

    (env)->SetObjectField(obj, field, value);
    ALOGD("array field set");
}

void setLongArrayElement(JNIEnv *env, jobject obj, const char *name, int index, jlong value)
{
    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        THROW(env, "Error in accessing field");
        return;
    } else {
        ALOGD("cls = %p", cls);
    }

    jfieldID field = (env)->GetFieldID(cls, name, "[J");
    if (field == NULL) {
        THROW(env, "Error in accessing field");
        return;
    } else {
        ALOGD("field = %p", field);
    }

    jlongArray array = (jlongArray)(env)->GetObjectField(obj, field);
    if (array == NULL) {
        THROW(env, "Error in accessing array");
        return;
    } else {
        ALOGD("array = %p", array);
    }

    jlong *elem = (env)->GetLongArrayElements(array, NULL);
    if (elem == NULL) {
        THROW(env, "Error in accessing index element");
        return;
    }

    elem[index] = value;
    (env)->ReleaseLongArrayElements(array, elem, 0);
}

void setObjectField(JNIEnv *env, jobject obj, const char *name, const char *type, jobject value)
{
    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        THROW(env, "Error in accessing class");
        return;
    }

    jfieldID field = (env)->GetFieldID(cls, name, type);
    if (field == NULL) {
        THROW(env, "Error in accessing field");
        return;
    }

    (env)->SetObjectField(obj, field, value);
}

void setStringField(JNIEnv *env, jobject obj, const char *name, const char *value)
{

    jstring str = env->NewStringUTF(value);

    if (str == NULL) {
        THROW(env, "Error in accessing class");
        return;
    }

    setObjectField(env, obj, name, "Ljava/lang/String;", str);
}

void reportEvent(JNIEnv *env, jobject obj, const char *method, const char *signature, ...)
{
    va_list params;
    va_start(params, signature);

    jclass cls = (env)->GetObjectClass(obj);
    if (cls == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jmethodID methodID = env->GetMethodID(cls, method, signature);
    if (method == NULL) {
        ALOGE("Error in getting method ID");
        return;
    }

    env->CallVoidMethodV(obj, methodID, params);
    va_end(params);
}

jobject createObject(JNIEnv *env, const char *className)
{
    jclass cls = env->FindClass(className);
    if (cls == NULL) {
        ALOGE("Error in finding class");
        return NULL;
    }

    jmethodID constructor = env->GetMethodID(cls, "<init>", "()V");
    if (constructor == NULL) {
        ALOGE("Error in constructor ID");
        return NULL;
    }
    jobject obj = env->NewObject(cls, constructor);
    if (constructor == NULL) {
        ALOGE("Could not create new object of %s", className);
        return NULL;
    }

    return obj;
}

}; // namespace android


