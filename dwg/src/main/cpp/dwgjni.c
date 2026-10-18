// Most JNI do LibreDWG: odczyt DWG i zapis binarnego DXF.
// Kod biblioteki jest na licencji GPL-3.0, tak samo ten moduł.
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>
#include "dwg.h"
#include "bits.h"
#include "out_dxf.h"

#define TAG "dwgjni"

JNIEXPORT jstring JNICALL
Java_pl_arma_dwg_DwgNative_toDxf(JNIEnv *env, jclass clazz, jstring input, jstring output, jboolean binary)
{
    (void)clazz;
    const char *in = (*env)->GetStringUTFChars(env, input, NULL);
    const char *out = (*env)->GetStringUTFChars(env, output, NULL);
    char message[256] = { 0 };

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    dwg.opts = 0;
    int error = dwg_read_file(in, &dwg);
    if (error >= DWG_ERR_CRITICAL) {
        snprintf(message, sizeof(message), "Nie udało się odczytać DWG (kod 0x%x).", error);
    } else {
        Bit_Chain dat;
        memset(&dat, 0, sizeof(Bit_Chain));
        dat.version = dwg.header.version;
        dat.from_version = dwg.header.from_version;
        dat.fh = fopen(out, "wb");
        if (!dat.fh) {
            snprintf(message, sizeof(message), "Nie udało się utworzyć pliku DXF.");
        } else {
            error = binary ? dwg_write_dxfb(&dat, &dwg) : dwg_write_dxf(&dat, &dwg);
            fclose(dat.fh);
            if (error >= DWG_ERR_CRITICAL)
                snprintf(message, sizeof(message), "Nie udało się zapisać DXF (kod 0x%x).", error);
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s -> %s: 0x%x", in, out, error);
    // Pamięci nie zwalniamy: proces konwersji kończy się zaraz po niej, a
    // dwg_free dla dużych rysunków trwa dłużej niż sama konwersja.
    (*env)->ReleaseStringUTFChars(env, input, in);
    (*env)->ReleaseStringUTFChars(env, output, out);
    return message[0] ? (*env)->NewStringUTF(env, message) : NULL;
}
