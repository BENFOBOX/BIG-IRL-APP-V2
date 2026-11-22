#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct SrtlaSession SrtlaSession;

extern SrtlaSession *bigirl_srtla_start(
    const char *receiver_host,
    uint16_t receiver_port,
    uint16_t local_srt_port,
    const char *wifi_ip,
    uint64_t wifi_network_handle,
    const char *cellular_ip,
    uint64_t cellular_network_handle,
    const char *cache_directory);
extern int32_t bigirl_srtla_active_links(const SrtlaSession *session);
extern size_t bigirl_srtla_error(
    const SrtlaSession *session,
    char *output,
    size_t capacity);
extern void bigirl_srtla_stop(SrtlaSession *session);

JNIEXPORT jlong JNICALL
Java_uk_co_bigirl_sender_SrtlaNative_createNative(
    JNIEnv *env,
    jobject object,
    jstring receiver_host,
    jint receiver_port,
    jint local_srt_port,
    jstring wifi_ip,
    jlong wifi_network_handle,
    jstring cellular_ip,
    jlong cellular_network_handle,
    jstring cache_directory) {
  (void)object;
  if (receiver_host == NULL || wifi_ip == NULL || cellular_ip == NULL ||
      cache_directory == NULL || receiver_port < 1 || receiver_port > 65535 ||
      local_srt_port < 1 || local_srt_port > 65535) {
    return 0;
  }

  const char *host = (*env)->GetStringUTFChars(env, receiver_host, NULL);
  const char *wifi = (*env)->GetStringUTFChars(env, wifi_ip, NULL);
  const char *cellular = (*env)->GetStringUTFChars(env, cellular_ip, NULL);
  const char *cache = (*env)->GetStringUTFChars(env, cache_directory, NULL);
  if (host == NULL || wifi == NULL || cellular == NULL || cache == NULL) {
    if (host != NULL) (*env)->ReleaseStringUTFChars(env, receiver_host, host);
    if (wifi != NULL) (*env)->ReleaseStringUTFChars(env, wifi_ip, wifi);
    if (cellular != NULL) (*env)->ReleaseStringUTFChars(env, cellular_ip, cellular);
    if (cache != NULL) (*env)->ReleaseStringUTFChars(env, cache_directory, cache);
    return 0;
  }

  SrtlaSession *session = bigirl_srtla_start(
      host,
      (uint16_t)receiver_port,
      (uint16_t)local_srt_port,
      wifi,
      (uint64_t)wifi_network_handle,
      cellular,
      (uint64_t)cellular_network_handle,
      cache);

  (*env)->ReleaseStringUTFChars(env, receiver_host, host);
  (*env)->ReleaseStringUTFChars(env, wifi_ip, wifi);
  (*env)->ReleaseStringUTFChars(env, cellular_ip, cellular);
  (*env)->ReleaseStringUTFChars(env, cache_directory, cache);
  return (jlong)(intptr_t)session;
}

JNIEXPORT jint JNICALL
Java_uk_co_bigirl_sender_SrtlaNative_activeLinksNative(
    JNIEnv *env,
    jobject object,
    jlong handle) {
  (void)env;
  (void)object;
  return bigirl_srtla_active_links((SrtlaSession *)(intptr_t)handle);
}

JNIEXPORT jstring JNICALL
Java_uk_co_bigirl_sender_SrtlaNative_errorNative(
    JNIEnv *env,
    jobject object,
    jlong handle) {
  (void)object;
  char error[1024];
  if (bigirl_srtla_error((SrtlaSession *)(intptr_t)handle, error, sizeof(error)) == 0) {
    return NULL;
  }
  return (*env)->NewStringUTF(env, error);
}

JNIEXPORT void JNICALL
Java_uk_co_bigirl_sender_SrtlaNative_destroyNative(
    JNIEnv *env,
    jobject object,
    jlong handle) {
  (void)env;
  (void)object;
  bigirl_srtla_stop((SrtlaSession *)(intptr_t)handle);
}
