/*
 * Pont JNI vers Gutenprint (pilote libre, GPL v2+) pour la Canon G3000 series.
 * Produit le flux d'impression Canon ; l'appli l'envoie ensuite en BJNP.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <gutenprint/gutenprint.h>

#define TAG "gutenprint"

typedef struct { unsigned char *buf; size_t len, cap; } outbuf_t;

static int g_inited = 0;
static const stp_printer_t *g_printer = NULL;
static stp_vars_t *g_job = NULL;
static outbuf_t g_out;
static char g_err[2048];
static size_t g_errlen = 0;

typedef struct { const unsigned char *rgb; int w, h; } img_t;
static img_t g_img;

static void out_write(void *obj, const char *data, size_t n) {
    outbuf_t *o = (outbuf_t *) obj;
    if (o->len + n > o->cap) {
        size_t cap = o->cap ? o->cap : 1 << 20;
        while (cap < o->len + n) cap *= 2;
        unsigned char *nb = realloc(o->buf, cap);
        if (!nb) return;
        o->buf = nb; o->cap = cap;
    }
    memcpy(o->buf + o->len, data, n);
    o->len += n;
}

static void err_write(void *obj, const char *data, size_t n) {
    (void) obj;
    __android_log_print(ANDROID_LOG_WARN, TAG, "%.*s", (int) n, data);
    size_t room = sizeof(g_err) - 1 - g_errlen;
    if (n > room) n = room;
    memcpy(g_err + g_errlen, data, n);
    g_errlen += n;
    g_err[g_errlen] = 0;
}

static void im_noop(stp_image_t *im) { (void) im; }
static int im_width(stp_image_t *im) { return ((img_t *) im->rep)->w; }
static int im_height(stp_image_t *im) { return ((img_t *) im->rep)->h; }
static const char *im_app(stp_image_t *im) { (void) im; return "CanonHub"; }
static stp_image_status_t im_row(stp_image_t *im, unsigned char *data, size_t limit, int row) {
    img_t *p = (img_t *) im->rep;
    size_t n = (size_t) p->w * 3;
    if (row < 0 || row >= p->h) return STP_IMAGE_STATUS_ABORT;
    if (n > limit) n = limit;
    memcpy(data, p->rgb + (size_t) row * p->w * 3, n);
    return STP_IMAGE_STATUS_OK;
}

static stp_image_t g_image = {
    .init = im_noop, .reset = im_noop, .width = im_width, .height = im_height,
    .get_row = im_row, .get_appname = im_app, .conclude = im_noop, .rep = &g_img,
};

static jbyteArray take_output(JNIEnv *env) {
    jbyteArray a = (*env)->NewByteArray(env, (jsize) g_out.len);
    if (a && g_out.len) (*env)->SetByteArrayRegion(env, a, 0, (jsize) g_out.len, (const jbyte *) g_out.buf);
    g_out.len = 0;
    return a;
}

/* Valeurs par défaut du pilote pour tout paramètre actif encore vide (2 passes : certains
 * choix, comme Resolution, dépendent d'autres, comme MediaType). */
static void fill_defaults(stp_vars_t *v) {
    for (int pass = 0; pass < 2; pass++) {
        stp_parameter_list_t list = stp_get_parameter_list(v);
        size_t n = stp_parameter_list_count(list);
        for (size_t i = 0; i < n; i++) {
            const stp_parameter_t *p = stp_parameter_list_param(list, i);
            stp_parameter_t d;
            stp_describe_parameter(v, p->name, &d);
            if (d.is_active) {
                switch (d.p_type) {
                case STP_PARAMETER_TYPE_STRING_LIST:
                    if (!stp_check_string_parameter(v, p->name, STP_PARAMETER_DEFAULTED)) {
                        if (d.deflt.str) stp_set_string_parameter(v, p->name, d.deflt.str);
                        else if (d.bounds.str && stp_string_list_count(d.bounds.str) > 0)
                            stp_set_string_parameter(v, p->name, stp_string_list_param(d.bounds.str, 0)->name);
                    }
                    break;
                case STP_PARAMETER_TYPE_INT:
                    if (!stp_check_int_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                        stp_set_int_parameter(v, p->name, d.deflt.integer);
                    break;
                case STP_PARAMETER_TYPE_BOOLEAN:
                    if (!stp_check_boolean_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                        stp_set_boolean_parameter(v, p->name, d.deflt.boolean);
                    break;
                case STP_PARAMETER_TYPE_DOUBLE:
                    if (!stp_check_float_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                        stp_set_float_parameter(v, p->name, d.deflt.dbl);
                    break;
                case STP_PARAMETER_TYPE_DIMENSION:
                    if (!stp_check_dimension_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                        stp_set_dimension_parameter(v, p->name, d.deflt.dimension);
                    break;
                default:
                    break;
                }
            }
            stp_parameter_description_destroy(&d);
        }
        stp_parameter_list_destroy(list);
    }
}

/* Réglages "clé=valeur" ; préfixes : "i:" entier, "b:" booléen, "f:" réel, sinon chaîne. */
static stp_vars_t *make_vars(JNIEnv *env, jobjectArray kv) {
    stp_vars_t *v = stp_vars_create_copy(stp_printer_get_defaults(g_printer));
    stp_set_driver(v, stp_printer_get_driver(g_printer));
    stp_set_outfunc(v, out_write);
    stp_set_outdata(v, &g_out);
    stp_set_errfunc(v, err_write);
    stp_set_errdata(v, NULL);
    stp_set_string_parameter(v, "InputImageType", "RGB");
    stp_set_int_parameter(v, "ChannelBitDepth", 8);
    jsize n = kv ? (*env)->GetArrayLength(env, kv) : 0;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, kv, i);
        const char *s = (*env)->GetStringUTFChars(env, js, NULL);
        char *dup = strdup(s);
        char *eq = strchr(dup, '=');
        if (eq) {
            *eq = 0;
            const char *key = dup, *val = eq + 1;
            if (!strncmp(key, "i:", 2)) stp_set_int_parameter(v, key + 2, atoi(val));
            else if (!strncmp(key, "b:", 2)) stp_set_boolean_parameter(v, key + 2, atoi(val));
            else if (!strncmp(key, "f:", 2)) stp_set_float_parameter(v, key + 2, atof(val));
            else stp_set_string_parameter(v, key, val);
        }
        free(dup);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
    }
    fill_defaults(v);
    return v;
}

/* Zone imprimable de la page choisie (points) appliquée aux réglages. */
static void apply_area(stp_vars_t *v) {
    stp_dimension_t l, r, b, t;
    stp_get_imageable_area(v, &l, &r, &b, &t);
    stp_set_left(v, l);
    stp_set_top(v, t);
    stp_set_width(v, r - l);
    stp_set_height(v, b - t);
}

JNIEXPORT jstring JNICALL
Java_be_canonhub_Gutenprint_nInit(JNIEnv *env, jclass cls, jstring dataPath, jstring driver) {
    (void) cls;
    if (!g_inited) {
        const char *p = (*env)->GetStringUTFChars(env, dataPath, NULL);
        setenv("STP_DATA_PATH", p, 1);
        (*env)->ReleaseStringUTFChars(env, dataPath, p);
        stp_init();
        g_inited = 1;
    }
    const char *d = (*env)->GetStringUTFChars(env, driver, NULL);
    g_printer = stp_get_printer_by_driver(d);
    (*env)->ReleaseStringUTFChars(env, driver, d);
    if (!g_printer) {
        char msg[128];
        snprintf(msg, sizeof msg, "pilote introuvable (%d imprimantes chargées)", stp_printer_model_count());
        return (*env)->NewStringUTF(env, msg);
    }
    return (*env)->NewStringUTF(env, "");
}

/* Choix d'un paramètre-liste : "*nom\ttexte" pour la valeur par défaut, "nom\ttexte" sinon. */
JNIEXPORT jobjectArray JNICALL
Java_be_canonhub_Gutenprint_nChoices(JNIEnv *env, jclass cls, jstring param, jobjectArray kv) {
    (void) cls;
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    if (!g_printer) return (*env)->NewObjectArray(env, 0, strCls, NULL);
    stp_vars_t *v = make_vars(env, kv);
    const char *name = (*env)->GetStringUTFChars(env, param, NULL);
    stp_parameter_t d;
    stp_describe_parameter(v, name, &d);
    (*env)->ReleaseStringUTFChars(env, param, name);
    jobjectArray res;
    if (d.p_type == STP_PARAMETER_TYPE_STRING_LIST && d.bounds.str) {
        int n = (int) stp_string_list_count(d.bounds.str);
        res = (*env)->NewObjectArray(env, n, strCls, NULL);
        for (int i = 0; i < n; i++) {
            const stp_param_string_t *ps = stp_string_list_param(d.bounds.str, i);
            char line[512];
            int def = d.deflt.str && !strcmp(d.deflt.str, ps->name);
            snprintf(line, sizeof line, "%s%s\t%s", def ? "*" : "", ps->name, ps->text ? ps->text : ps->name);
            jstring js = (*env)->NewStringUTF(env, line);
            (*env)->SetObjectArrayElement(env, res, i, js);
            (*env)->DeleteLocalRef(env, js);
        }
    } else {
        res = (*env)->NewObjectArray(env, 0, strCls, NULL);
    }
    stp_parameter_description_destroy(&d);
    stp_vars_destroy(v);
    return res;
}

/* [largeur page, hauteur page, gauche, droite, bas, haut] en points. */
JNIEXPORT jdoubleArray JNICALL
Java_be_canonhub_Gutenprint_nArea(JNIEnv *env, jclass cls, jobjectArray kv) {
    (void) cls;
    jdoubleArray a = (*env)->NewDoubleArray(env, 6);
    if (!g_printer) return a;
    stp_vars_t *v = make_vars(env, kv);
    stp_dimension_t w, h, l, r, b, t;
    stp_get_media_size(v, &w, &h);
    stp_get_imageable_area(v, &l, &r, &b, &t);
    jdouble vals[6] = { w, h, l, r, b, t };
    (*env)->SetDoubleArrayRegion(env, a, 0, 6, vals);
    stp_vars_destroy(v);
    return a;
}

/* Début de travail : "" si OK, sinon le message d'erreur. */
JNIEXPORT jstring JNICALL
Java_be_canonhub_Gutenprint_nBegin(JNIEnv *env, jclass cls, jobjectArray kv, jint w, jint h) {
    (void) cls;
    if (!g_printer) return (*env)->NewStringUTF(env, "pilote non initialisé");
    if (g_job) { stp_vars_destroy(g_job); g_job = NULL; }
    g_out.len = 0; g_errlen = 0; g_err[0] = 0;
    g_job = make_vars(env, kv);
    apply_area(g_job);
    g_img.rgb = NULL; g_img.w = w; g_img.h = h;
    if (!stp_verify(g_job)) {
        stp_vars_destroy(g_job); g_job = NULL;
        return (*env)->NewStringUTF(env, g_errlen ? g_err : "réglages refusés par le pilote");
    }
    stp_start_job(g_job, &g_image);
    return (*env)->NewStringUTF(env, "");
}

/* Une page RGB 8 bits (w*h*3 octets) ; renvoie les données produites depuis le dernier appel. */
JNIEXPORT jbyteArray JNICALL
Java_be_canonhub_Gutenprint_nPage(JNIEnv *env, jclass cls, jbyteArray rgb, jint w, jint h) {
    (void) cls;
    if (!g_job) return NULL;
    jbyte *px = (*env)->GetByteArrayElements(env, rgb, NULL);
    g_img.rgb = (const unsigned char *) px; g_img.w = w; g_img.h = h;
    int ok = stp_print(g_job, &g_image);
    (*env)->ReleaseByteArrayElements(env, rgb, px, JNI_ABORT);
    g_img.rgb = NULL;
    if (!ok) return NULL;
    return take_output(env);
}

/* Fin de travail : renvoie les dernières données (commandes de fin). */
JNIEXPORT jbyteArray JNICALL
Java_be_canonhub_Gutenprint_nEnd(JNIEnv *env, jclass cls) {
    (void) cls;
    if (!g_job) return (*env)->NewByteArray(env, 0);
    stp_end_job(g_job, &g_image);
    stp_vars_destroy(g_job); g_job = NULL;
    jbyteArray a = take_output(env);
    free(g_out.buf); g_out.buf = NULL; g_out.cap = 0;
    return a;
}

JNIEXPORT jstring JNICALL
Java_be_canonhub_Gutenprint_nLastError(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, g_err);
}
