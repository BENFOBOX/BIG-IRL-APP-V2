#include <jni.h>
#include <srt.h>
#include <android/log.h>
#include <errno.h>
#include <netdb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "BIG_IRL_SRT_BOND"
#define REQUIRED_LINKS 2
#define CONNECTION_TIMEOUT_MS 15000

typedef struct {
  SRTSOCKET group;
  jobject callback;
  jmethodID callback_method;
  int connected;
  int connection_timeout_reported;
  struct timespec started_at;
} BondedSender;

static void report_link_count(JNIEnv *env, BondedSender *sender, int connected) {
  if (sender->connected == connected) return;
  sender->connected = connected;
  (*env)->CallVoidMethod(env, sender->callback, sender->callback_method, (jint)connected);
  if ((*env)->ExceptionCheck(env)) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "SRT connection callback failed");
    (*env)->ExceptionClear(env);
  }
}

static int connected_member_count(SRTSOCKET group) {
  SRT_SOCKGROUPDATA members[REQUIRED_LINKS] = {0};
  size_t count = REQUIRED_LINKS;
  if (srt_group_data(group, members, &count) == SRT_ERROR) return 0;
  int connected = 0;
  for (size_t i = 0; i < count && i < REQUIRED_LINKS; ++i) {
    if (members[i].sockstate == SRTS_CONNECTED) ++connected;
  }
  return connected;
}

static int elapsed_ms(const struct timespec *start) {
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
  return (int)((now.tv_sec - start->tv_sec) * 1000 +
      (now.tv_nsec - start->tv_nsec) / 1000000);
}

static int resolve_endpoint(
    const char *host,
    int port,
    struct sockaddr_storage *address,
    int *address_length) {
  char port_text[6];
  snprintf(port_text, sizeof(port_text), "%d", port);
  struct addrinfo hints = {0};
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_DGRAM;
  struct addrinfo *result = NULL;
  int status = getaddrinfo(host, port_text, &hints, &result);
  if (status != 0 || result == NULL) return -1;
  if (result->ai_addrlen > sizeof(*address)) {
    freeaddrinfo(result);
    return -1;
  }
  memset(address, 0, sizeof(*address));
  memcpy(address, result->ai_addr, result->ai_addrlen);
  *address_length = (int)result->ai_addrlen;
  freeaddrinfo(result);
  return 0;
}

static int set_group_flag(SRTSOCKET group, SRT_SOCKOPT option, const void *value, int length) {
  return srt_setsockflag(group, option, value, length);
}

static void destroy_sender(JNIEnv *env, BondedSender *sender) {
  if (sender == NULL) return;
  if (sender->group != SRT_INVALID_SOCK) srt_close(sender->group);
  if (sender->callback != NULL) (*env)->DeleteGlobalRef(env, sender->callback);
  srt_cleanup();
  free(sender);
}

JNIEXPORT jlong JNICALL
Java_uk_co_bigirl_sender_SrtBondedNative_createNative(
    JNIEnv *env,
    jobject object,
    jstring host,
    jint wifi_port,
    jint cellular_port,
    jstring stream_id,
    jlong wifi_network_handle,
    jlong cellular_network_handle,
    jobject callback) {
  (void)object;
  if (host == NULL || stream_id == NULL || callback == NULL ||
      wifi_network_handle == 0 || cellular_network_handle == 0) {
    return 0;
  }

  const char *host_text = (*env)->GetStringUTFChars(env, host, NULL);
  const char *stream_id_text = (*env)->GetStringUTFChars(env, stream_id, NULL);
  if (host_text == NULL || stream_id_text == NULL) {
    if (host_text != NULL) (*env)->ReleaseStringUTFChars(env, host, host_text);
    if (stream_id_text != NULL) (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }

  if (srt_startup() < 0) {
    (*env)->ReleaseStringUTFChars(env, host, host_text);
    (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }

  BondedSender *sender = calloc(1, sizeof(*sender));
  if (sender == NULL) {
    srt_cleanup();
    (*env)->ReleaseStringUTFChars(env, host, host_text);
    (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }
  sender->group = SRT_INVALID_SOCK;
  sender->callback = (*env)->NewGlobalRef(env, callback);
  jclass callback_class = (*env)->GetObjectClass(env, callback);
  if (sender->callback == NULL || callback_class == NULL) {
    if (callback_class != NULL) (*env)->DeleteLocalRef(env, callback_class);
    destroy_sender(env, sender);
    (*env)->ReleaseStringUTFChars(env, host, host_text);
    (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }
  sender->callback_method = (*env)->GetMethodID(
      env, callback_class, "onLinkCountNative", "(I)V");
  (*env)->DeleteLocalRef(env, callback_class);
  if (sender->callback_method == NULL) {
    destroy_sender(env, sender);
    (*env)->ReleaseStringUTFChars(env, host, host_text);
    (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }

  sender->group = srt_create_group(SRT_GTYPE_BROADCAST);
  int send_sync = 0;
  int receive_sync = 0;
  int transmission_type = SRTT_LIVE;
  int latency_ms = 120;
  if (sender->group == SRT_INVALID_SOCK ||
      set_group_flag(sender->group, SRTO_SNDSYN, &send_sync, sizeof(send_sync)) == SRT_ERROR ||
      set_group_flag(sender->group, SRTO_RCVSYN, &receive_sync, sizeof(receive_sync)) == SRT_ERROR ||
      set_group_flag(sender->group, SRTO_TRANSTYPE, &transmission_type, sizeof(transmission_type)) == SRT_ERROR ||
      set_group_flag(sender->group, SRTO_LATENCY, &latency_ms, sizeof(latency_ms)) == SRT_ERROR ||
      set_group_flag(sender->group, SRTO_STREAMID, stream_id_text, (int)strlen(stream_id_text)) == SRT_ERROR) {
    destroy_sender(env, sender);
    (*env)->ReleaseStringUTFChars(env, host, host_text);
    (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }

  struct sockaddr_storage wifi_address;
  struct sockaddr_storage cellular_address;
  int wifi_address_length = 0;
  int cellular_address_length = 0;
  if (resolve_endpoint(host_text, wifi_port, &wifi_address, &wifi_address_length) != 0 ||
      resolve_endpoint(host_text, cellular_port, &cellular_address, &cellular_address_length) != 0) {
    destroy_sender(env, sender);
    (*env)->ReleaseStringUTFChars(env, host, host_text);
    (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
    return 0;
  }

  SRT_SOCKGROUPCONFIG members[REQUIRED_LINKS];
  members[0] = srt_prepare_endpoint(
      NULL, (const struct sockaddr *)&wifi_address, wifi_address_length);
  members[1] = srt_prepare_endpoint(
      NULL, (const struct sockaddr *)&cellular_address, cellular_address_length);
  members[0].android_network_handle = (uint64_t)wifi_network_handle;
  members[1].android_network_handle = (uint64_t)cellular_network_handle;

  int connect_result = srt_connect_group(sender->group, members, REQUIRED_LINKS);
  (*env)->ReleaseStringUTFChars(env, host, host_text);
  (*env)->ReleaseStringUTFChars(env, stream_id, stream_id_text);
  if (connect_result == SRT_ERROR ||
      (members[0].errorcode != SRT_SUCCESS && members[1].errorcode != SRT_SUCCESS)) {
    destroy_sender(env, sender);
    return 0;
  }

  if (clock_gettime(CLOCK_MONOTONIC, &sender->started_at) != 0) {
    destroy_sender(env, sender);
    return 0;
  }
  return (jlong)(intptr_t)sender;
}

JNIEXPORT jint JNICALL
Java_uk_co_bigirl_sender_SrtBondedNative_sendNative(
    JNIEnv *env,
    jobject object,
    jlong handle,
    jbyteArray payload) {
  (void)object;
  BondedSender *sender = (BondedSender *)(intptr_t)handle;
  if (sender == NULL || sender->group == SRT_INVALID_SOCK || payload == NULL) return SRT_ERROR;

  jsize length = (*env)->GetArrayLength(env, payload);
  int connected = connected_member_count(sender->group);
  if (connected == 0) {
    report_link_count(env, sender, 0);
    if (!sender->connection_timeout_reported &&
        elapsed_ms(&sender->started_at) >= CONNECTION_TIMEOUT_MS) {
      sender->connection_timeout_reported = 1;
      return SRT_ERROR;
    }
    return length;
  }

  jbyte *bytes = (*env)->GetByteArrayElements(env, payload, NULL);
  if (bytes == NULL) return SRT_ERROR;

  SRT_MSGCTRL control = srt_msgctrl_default;
  SRT_SOCKGROUPDATA members[REQUIRED_LINKS] = {0};
  control.grpdata = members;
  control.grpdata_size = REQUIRED_LINKS;
  int result = srt_sendmsg2(sender->group, (const char *)bytes, length, &control);
  (*env)->ReleaseByteArrayElements(env, payload, bytes, JNI_ABORT);
  if (result != length) return SRT_ERROR;

  connected = connected_member_count(sender->group);
  report_link_count(env, sender, connected);
  return result;
}

JNIEXPORT void JNICALL
Java_uk_co_bigirl_sender_SrtBondedNative_destroyNative(
    JNIEnv *env,
    jobject object,
    jlong handle) {
  (void)object;
  destroy_sender(env, (BondedSender *)(intptr_t)handle);
}
