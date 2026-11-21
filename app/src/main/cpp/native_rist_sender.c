#include <android/log.h>
#include <android/multinetwork.h>
#include <jni.h>
#include <librist/librist.h>
#include <librist/peer.h>
#include <librist/transport.h>
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/socket.h>

#define TAG "BIG_IRL_RIST"

typedef struct {
  struct rist_ctx *context;
  struct rist_logging_settings *logging;
  struct rist_transport_ops transport;
  net_handle_t network;
  JavaVM *jvm;
  jobject callback;
  jmethodID callback_method;
} RistSender;

static void log_message(void *opaque, enum rist_log_level level, const char *message) {
  (void)opaque;
  int priority = level >= RIST_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
  __android_log_print(priority, TAG, "%s", message);
}

static int bind_socket(RistSender *sender, int fd) {
  if (sender->network == 0) return 0;
  if (android_setsocknetwork(sender->network, fd) == 0) return 0;
  __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not bind RIST socket to Android network: errno=%d", errno);
  return -1;
}

static ssize_t network_sendto(
    void *opaque,
    int fd,
    const void *buffer,
    size_t length,
    int flags,
    const struct sockaddr *address,
    socklen_t address_length) {
  RistSender *sender = opaque;
  if (bind_socket(sender, fd) != 0) return -1;
  return sendto(fd, buffer, length, flags, address, address_length);
}

static ssize_t network_recvfrom(
    void *opaque,
    int fd,
    void *buffer,
    size_t length,
    int flags,
    struct sockaddr *address,
    socklen_t *address_length) {
  RistSender *sender = opaque;
  if (bind_socket(sender, fd) != 0) return -1;
  return recvfrom(fd, buffer, length, flags, address, address_length);
}

static int network_poll(void *opaque, struct pollfd *fds, int count, int timeout_ms) {
  (void)opaque;
  return poll(fds, (nfds_t)count, timeout_ms);
}

static void connection_status(
    void *opaque,
    struct rist_peer *peer,
    enum rist_connection_status status) {
  (void)peer;
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

  if (status == RIST_CONNECTION_ESTABLISHED || status == RIST_CLIENT_CONNECTED) {
    (*env)->CallVoidMethod(env, sender->callback, sender->callback_method, JNI_TRUE);
  } else if (status == RIST_CONNECTION_TIMED_OUT || status == RIST_CLIENT_TIMED_OUT) {
    (*env)->CallVoidMethod(env, sender->callback, sender->callback_method, JNI_FALSE);
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
  free(sender);
}

JNIEXPORT jlong JNICALL
Java_uk_co_bigirl_sender_RistNative_createNative(
    JNIEnv *env,
    jobject object,
    jstring endpoint,
    jlong network_handle,
    jobject callback) {
  (void)object;
  if (endpoint == NULL) return 0;

  const char *endpoint_text = (*env)->GetStringUTFChars(env, endpoint, NULL);
  if (endpoint_text == NULL) return 0;

  RistSender *sender = calloc(1, sizeof(*sender));
  if (sender == NULL) {
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  sender->network = (net_handle_t)network_handle;
  if ((*env)->GetJavaVM(env, &sender->jvm) != JNI_OK || callback == NULL) {
    free(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  sender->callback = (*env)->NewGlobalRef(env, callback);
  jclass callback_class = (*env)->GetObjectClass(env, callback);
  if (sender->callback == NULL || callback_class == NULL) {
    if (callback_class != NULL) (*env)->DeleteLocalRef(env, callback_class);
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }
  sender->callback_method = (*env)->GetMethodID(
      env, callback_class, "onConnectionStatusNative", "(Z)V");
  (*env)->DeleteLocalRef(env, callback_class);
  if (sender->callback_method == NULL) {
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }

  int result = rist_logging_set(
      &sender->logging, RIST_LOG_WARN, log_message, NULL, NULL, NULL);
  if (result != 0 ||
      rist_sender_create(&sender->context, RIST_PROFILE_MAIN, 0, sender->logging) != 0) {
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }

  sender->transport.opaque = sender;
  sender->transport.sendto = network_sendto;
  sender->transport.recvfrom = network_recvfrom;
  sender->transport.poll = network_poll;
  if (rist_transport_set(sender->context, &sender->transport) != 0) {
    destroy_sender(sender);
    (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
    return 0;
  }

  struct rist_peer_config *peer_config = NULL;
  struct rist_peer *peer = NULL;
  result = rist_parse_address2(endpoint_text, &peer_config);
  (*env)->ReleaseStringUTFChars(env, endpoint, endpoint_text);
  if (result != 0 || peer_config == NULL ||
      rist_peer_create(sender->context, &peer, peer_config) != 0) {
    if (peer_config != NULL) rist_peer_config_free2(&peer_config);
    destroy_sender(sender);
    return 0;
  }
  rist_peer_config_free2(&peer_config);

  if (rist_connection_status_callback_set(
          sender->context, connection_status, sender) != 0) {
    destroy_sender(sender);
    return 0;
  }
  if (rist_start(sender->context) != 0) {
    destroy_sender(sender);
    return 0;
  }
  return (jlong)(intptr_t)sender;
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
