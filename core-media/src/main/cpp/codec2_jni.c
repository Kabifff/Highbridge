// JNI glue for org.highbridge.core.media.NativeCodec2. Encodes whole buffers of 8 kHz 16-bit PCM.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "codec2/codec2.h"

JNIEXPORT jlong JNICALL
Java_org_highbridge_core_media_NativeCodec2_nativeCreate(JNIEnv *env, jobject thiz, jint mode) {
    struct CODEC2 *c2 = codec2_create(mode);
    return (jlong)(intptr_t)c2;
}

JNIEXPORT void JNICALL
Java_org_highbridge_core_media_NativeCodec2_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (c2) codec2_destroy(c2);
}

JNIEXPORT jbyteArray JNICALL
Java_org_highbridge_core_media_NativeCodec2_nativeEncode(JNIEnv *env, jobject thiz, jlong handle, jshortArray pcm) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2 || !pcm) return (*env)->NewByteArray(env, 0);
    int spf = codec2_samples_per_frame(c2);
    int bpf = codec2_bytes_per_frame(c2);
    jsize n = (*env)->GetArrayLength(env, pcm);
    int frames = n / spf;
    jbyteArray out = (*env)->NewByteArray(env, frames * bpf);
    if (frames == 0) return out;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    unsigned char *bits = (unsigned char *)malloc((size_t)frames * bpf);
    for (int f = 0; f < frames; f++) codec2_encode(c2, bits + f * bpf, in + f * spf);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->SetByteArrayRegion(env, out, 0, frames * bpf, (jbyte *)bits);
    free(bits);
    return out;
}

JNIEXPORT jshortArray JNICALL
Java_org_highbridge_core_media_NativeCodec2_nativeDecode(JNIEnv *env, jobject thiz, jlong handle, jbyteArray enc) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2 || !enc) return (*env)->NewShortArray(env, 0);
    int spf = codec2_samples_per_frame(c2);
    int bpf = codec2_bytes_per_frame(c2);
    jsize n = (*env)->GetArrayLength(env, enc);
    int frames = n / bpf;
    jshortArray out = (*env)->NewShortArray(env, frames * spf);
    if (frames == 0) return out;
    jbyte *in = (*env)->GetByteArrayElements(env, enc, NULL);
    short *pcm = (short *)malloc((size_t)frames * spf * sizeof(short));
    for (int f = 0; f < frames; f++) codec2_decode(c2, pcm + f * spf, (const unsigned char *)in + f * bpf);
    (*env)->ReleaseByteArrayElements(env, enc, in, JNI_ABORT);
    (*env)->SetShortArrayRegion(env, out, 0, frames * spf, pcm);
    free(pcm);
    return out;
}
