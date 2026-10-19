// Most JNI do libarchive: odczyt archiwów wpis po wpisie.
#include <jni.h>
#include <locale.h>
#include <stdlib.h>
#include <string.h>
#include "archive.h"
#include "archive_entry.h"

typedef struct {
    struct archive *archive;
    struct archive_entry *entry;
} Handle;

static void throw_io(JNIEnv *env, const char *message) {
    jclass io = (*env)->FindClass(env, "java/io/IOException");
    if (io) (*env)->ThrowNew(env, io, message ? message : "libarchive error");
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    // Nazwy w Unicode (RAR, ISO Joliet) zamieniane są na wielobajtowe według
    // locale; bez tego zostałyby tylko znaki ASCII.
    setlocale(LC_CTYPE, "C.UTF-8");
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_pl_arma_libarchive_LibArchive_nativeOpen(JNIEnv *env, jclass clazz, jstring path, jstring passphrase) {
    (void)clazz;
    struct archive *a = archive_read_new();
    if (!a) { throw_io(env, "out of memory"); return 0; }
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    if (passphrase) {
        const char *pass = (*env)->GetStringUTFChars(env, passphrase, NULL);
        archive_read_add_passphrase(a, pass);
        (*env)->ReleaseStringUTFChars(env, passphrase, pass);
    }
    const char *file = (*env)->GetStringUTFChars(env, path, NULL);
    int result = archive_read_open_filename(a, file, 64 * 1024);
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (result != ARCHIVE_OK) {
        throw_io(env, archive_error_string(a));
        archive_read_free(a);
        return 0;
    }
    Handle *h = calloc(1, sizeof(Handle));
    h->archive = a;
    return (jlong)(intptr_t)h;
}

JNIEXPORT jboolean JNICALL
Java_pl_arma_libarchive_LibArchive_nativeNext(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    int result = archive_read_next_header(h->archive, &h->entry);
    if (result == ARCHIVE_EOF) { h->entry = NULL; return JNI_FALSE; }
    if (result < ARCHIVE_WARN) {
        h->entry = NULL;
        throw_io(env, archive_error_string(h->archive));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_pl_arma_libarchive_LibArchive_nativeName(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    if (!h->entry) return NULL;
    const char *name = archive_entry_pathname_utf8(h->entry);
    if (!name) name = archive_entry_pathname(h->entry);
    if (!name) return NULL;
    jsize length = (jsize)strlen(name);
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes) (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)name);
    return bytes;
}

JNIEXPORT jlong JNICALL
Java_pl_arma_libarchive_LibArchive_nativeSize(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    return h->entry && archive_entry_size_is_set(h->entry) ? (jlong)archive_entry_size(h->entry) : -1;
}

JNIEXPORT jlong JNICALL
Java_pl_arma_libarchive_LibArchive_nativeModified(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    if (!h->entry || !archive_entry_mtime_is_set(h->entry)) return 0;
    return (jlong)archive_entry_mtime(h->entry) * 1000 + archive_entry_mtime_nsec(h->entry) / 1000000;
}

JNIEXPORT jint JNICALL
Java_pl_arma_libarchive_LibArchive_nativeType(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    return h->entry ? (jint)archive_entry_filetype(h->entry) : 0;
}

JNIEXPORT jboolean JNICALL
Java_pl_arma_libarchive_LibArchive_nativeEncrypted(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    return h->entry && archive_entry_is_encrypted(h->entry) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_pl_arma_libarchive_LibArchive_nativeRead(JNIEnv *env, jclass clazz, jlong handle, jbyteArray buffer, jint offset, jint length) {
    (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    if (length <= 0) return 0;
    jbyte *data = (*env)->GetPrimitiveArrayCritical(env, buffer, NULL);
    if (!data) { throw_io(env, "out of memory"); return -1; }
    la_ssize_t count = archive_read_data(h->archive, data + offset, (size_t)length);
    (*env)->ReleasePrimitiveArrayCritical(env, buffer, data, 0);
    if (count < 0) {
        throw_io(env, archive_error_string(h->archive));
        return -1;
    }
    return count == 0 ? -1 : (jint)count;
}

JNIEXPORT jstring JNICALL
Java_pl_arma_libarchive_LibArchive_nativeFormat(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    const char *name = archive_format_name(h->archive);
    return name ? (*env)->NewStringUTF(env, name) : NULL;
}

JNIEXPORT void JNICALL
Java_pl_arma_libarchive_LibArchive_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Handle *h = (Handle *)(intptr_t)handle;
    if (!h) return;
    archive_read_free(h->archive);
    free(h);
}
