#include <android/log.h>
#include <android/multinetwork.h>
#include <jni.h>
#include <librist/librist.h>
#include <librist/peer.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdio.h>

#define TAG "BIG_IRL_RIST"
#define MAX_RIST_PATHS 2

static _Thread_local char last_create_error[256];

typedef struct {
  struct rist_ctx *context;
  struct rist_logging_settings *logging;
  JavaVM *jvm;
  jobject callback;
  jmethodID callback_method;
  struct rist_peer *peers[MAX_RIST_PATHS];
  bool peer_connected[MAX_RIST_PATHS];
  int peer_count;
  int reported_link_count;
  pthread_mutex_t status_mutex;
} RistSender;

static int log_message(void *opaque, enum rist_log_level level, const char *message) {
  (void)opaque;
  int priority = level >= RIST_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
  __android_log_print(priority, TAG, "%s", message);
  return 0;
}

static void set_create_error(const char *message) {
  snprintf(last_create_error, sizeof(last_create_error), "%s", message);
}

static void connection_status(
    void *opaque,
    struct rist_peer *peer,
    enum rist_connection_status status) {
  RistSender *sender = opaque;
  JNIEnv *env = NULL;
  bool attached = false;
  jint result = (*sender->jvm)->GetEnv(sender->jvm, (void **)&env, JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    if ((*sender->jvm)->AttachCurrentThread(
            sender->jvm, (void **)&env, NULL) != JNI_OK) return;
    attached = true;
  } else if (result != JNI_OK) {
    return;
  }

  int peer_index = -1;
  for (int i = 0; i < sender->peer_count; ++i) {
    if (sender->peers[i] == peer) {
      peer_index = i;
      break;
    }
  }
  if (peer_index < 0) {
    if (attached) (*sender->jvm)->DetachCurrentThread(sender->jvm);
    return;
  }

  pthread_mutex_lock(&sender->status_mutex);
  if (status == RIST_CONNECTION_ESTABLISHED || status == RIST_CLIENT_CONNECTED) {
    sender->peer_connected[peer_index] = true;
  } else if (status == RIST_CONNECTION_TIMED_OUT || status == RIST_CLIENT_TIMED_OUT) {
    sender->peer_connected[peer_index] = false;
  }

  int connected_count = 0;
  for (int i = 0; i < sender->peer_count; ++i) {
    if (sender->peer_connected[i]) connected_count++;
  }
  bool notify = connected_count != sender->reported_link_count;
  if (notify) {
    sender->reported_link_count = connected_count;
  }
  pthread_mutex_unlock(&sender->status_mutex);
  if (notify) {
    (*env)->CallVoidMethod(
        env, sender->callback, sender->callback_method, (jint)connected_count);
  }
  if ((*env)->ExceptionCheck(env)) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "RIST connection callback failed");
    (*env)->ExceptionClear(env);
  }
  if (attached) (*sender->jvm)->DetachCurrentThread(sender->jvm);
}

static void destroy_sender(RistSender *sender) {
  if (sender == NULL) return;
  if (sender->context != NULL) rist_destroy(sender->context);
  if (sender->callback != NULL && sender->jvm != NULL) {
    JNIEnv *env = NULL;
    if ((*sender->jvm)->GetEnv(sender->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
      (*env)->DeleteGlobalRef(env, sender->callback);
    }
  }
  if (sender->logging != NULL) rist_logging_settings_free2(&sender->logging);
  pthread_mutex_destroy(&sender->status_mutex);
  free(sender);
}

JNIEXPORT jlong JNICALL
Java_uk_co_bigirl_sender_RistNative_createNative(
    JNIEnv *env,
    jobject object,
    jstring endpoint,
    jlong wifi_network_handle,
    jlong cellular_network_handle,
    jobject callback) {
  (void)object;
  last_create_error[0] = '\0';
  if (endpoint == NULL) {
    set_create_error("RIST endpoint is missing");
    return 0;
  }
  if (cellular_network_handle == 0) {
    set_create_error("Cellular network is unavailable for RIST");
    return 0;
  }

  const char *endpoint_text = (*env)->GetStringUTFChars(env, endpoint, NULL);
  if (endpoint_text == NULL) {
    set_create_error("Could not read RIST endpoint");
    return 0;
  }

  RistSender *sender = calloc(1, sizeof(*sender));
  if (sender == NULL) {
    set_create_error("Could not allocate RIST sender");
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  if (pthread_mutex_init(&sender->status_mutex, NULL) != 0) {
    set_create_error("Could not initialize RIST sender lock");
    free(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  sender->reported_link_count = -1;
  if ((*env)->GetJavaVM(env, &sender->jvm) != JNI_OK || callback == NULL) {
    set_create_error("Could not initialize RIST callback");
    pthread_mutex_destroy(&sender->status_mutex);
    free(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  sender->callback = (*env)->NewGlobalRef(env, callback);
  jclass callback_class = (*env)->GetObjectClass(env, callback);
  if (sender->callback == NULL || callback_class == NULL) {
    set_create_error("Could not retain RIST callback");
    if (callback_class != NULL) (*env)->DeleteLocalRef(env, callback_class);
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  sender->callback_method = (*env)->GetMethodID(
      env, callback_class, "onLinkCountNative", "(I)V");
  (*env)->DeleteLocalRef(env, callback_class);
  if (sender->callback_method == NULL) {
    set_create_error("Could not find RIST callback method");
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }

  int result = rist_logging_set(
      &sender->logging, RIST_LOG_WARN, log_message, NULL, NULL, NULL);
  if (result != 0) {
    snprintf(last_create_error, sizeof(last_create_error),
             "Could not initialize RIST logging (error %d)", result);
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  result = rist_sender_create(&sender->context, RIST_PROFILE_MAIN, 0, sender->logging);
  if (result != 0) {
    snprintf(last_create_error, sizeof(last_create_error),
             "Could not create RIST sender (error %d)", result);
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }

  struct rist_peer_config *peer_config = NULL;
  result = rist_parse_address2(endpoint_text, &peer_config);
  (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
  if (result < 0 || peer_config == NULL) {
    snprintf(last_create_error, sizeof(last_create_error),
             "Invalid RIST destination (error %d)", result);
    if (peer_config != NULL) rist_peer_config_free2(&peer_config);
    destroy_sender(sender);
    return 0;
  }

  const net_handle_t networks[MAX_RIST_PATHS] = {
      (net_handle_t)wifi_network_handle,
      (net_handle_t)cellular_network_handle,
  };
  const int path_count = wifi_network_handle != 0 ? 2 : 1;
  const int first_network = path_count == 2 ? 0 : 1;
  snprintf(
      peer_config->cname,
      sizeof(peer_config->cname),
      "BIG_IRL_%08x",
      (unsigned int)arc4random());
  peer_config->weight = 5;

  for (int i = 0; i < path_count; ++i) {
    struct rist_peer_config path_config = *peer_config;
    // libRIST opens its UDP socket during peer creation, so bind the process
    // briefly to make the new socket inherit this path's Android network.
    if (android_setprocnetwork(networks[first_network + i]) != 0) {
      snprintf(last_create_error, sizeof(last_create_error),
               "Could not select Android network for RIST path %d (errno %d)",
               i + 1, errno);
      rist_peer_config_free2(&peer_config);
      destroy_sender(sender);
      return 0;
    }

    sender->peer_count = i + 1;
    int peer_result = rist_peer_create(
        sender->context, &sender->peers[i], &path_config);
    int peer_error = errno;
    int restore_result = android_setprocnetwork(0);
    int restore_error = errno;
    if (restore_result != 0) {
      snprintf(last_create_error, sizeof(last_create_error),
               "Could not restore Android process network (errno %d)",
               restore_error);
      rist_peer_config_free2(&peer_config);
      destroy_sender(sender);
      return 0;
    }
    if (peer_result != 0) {
      snprintf(last_create_error, sizeof(last_create_error),
               "Could not create RIST network path %d (error %d)",
               i + 1, peer_error);
      rist_peer_config_free2(&peer_config);
      destroy_sender(sender);
      return 0;
    }
  }
  rist_peer_config_free2(&peer_config);

  if (rist_connection_status_callback_set(
          sender->context, connection_status, sender) != 0) {
    set_create_error("Could not register RIST connection callback");
    destroy_sender(sender);
    return 0;
  }
  if (rist_start(sender->context) != 0) {
    set_create_error("Could not start RIST transport");
    destroy_sender(sender);
    return 0;
  }
  return (jlong)(intptr_t)sender;
}

JNIEXPORT jstring JNICALL
Java_uk_co_bigirl_sender_RistNative_lastCreateErrorNative(
    JNIEnv *env,
    jobject object) {
  (void)object;
  return last_create_error[0] == '\0'
      ? NULL
      : (*env)->NewStringUTF(env, last_create_error);
}

JNIEXPORT jint JNICALL
Java_uk_co_bigirl_sender_RistNative_sendNative(
    JNIEnv *env,
    jobject object,
    jlong handle,
    jbyteArray payload) {
  (void)object;
  RistSender *sender = (RistSender *)(intptr_t)handle;
  if (sender == NULL || sender->context == NULL || payload == NULL) return -1;

  jsize length = (*env)->GetArrayLength(env, payload);
  jbyte *bytes = (*env)->GetByteArrayElements(env, payload, NULL);
  if (bytes == NULL) return -1;

  struct rist_data_block block = {
      .payload = bytes,
      .payload_len = (size_t)length,
      .ts_ntp = 0,
  };
  int result = rist_sender_data_write(sender->context, &block);
  (*env)->ReleaseByteArrayElements(env, payload, bytes, JNI_ABORT);
  return result;
}

JNIEXPORT void JNICALL
Java_uk_co_bigirl_sender_RistNative_destroyNative(
    JNIEnv *env,
    jobject object,
    jlong handle) {
  (void)env;
  (void)object;
  destroy_sender((RistSender *)(intptr_t)handle);
}
