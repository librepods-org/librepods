#include <jni.h>
#include <pthread.h>
#include <array>
#include <string>

static JavaVM* gVm = nullptr;

template<size_t N>
constexpr auto encryptString(const char (&str)[N], char key) {
    std::array<char, N> encrypted{};
    for (size_t i = 0; i < N; i++) {
        encrypted[i] = str[i] ^ key;
    }
    return encrypted;
}

template<size_t N>
static std::string decryptString(const std::array<char, N>& encrypted, char key) {
    std::string result(N - 1, '\0');
    for (size_t i = 0; i < N - 1; i++) {
        result[i] = encrypted[i] ^ key;
    }
    return result;
}

#define ENC(str) encryptString(str, 0x47)
#define DEC(arr) decryptString(arr, 0x47).c_str()

__attribute__((visibility("hidden")))
static JavaVM* getVm() { return gVm; }

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gVm = vm;

    auto fn = [](void*) -> void* {
        constexpr auto c1 = ENC("dalvik/system/VMRuntime");
        constexpr auto c2 = ENC("getRuntime");
        constexpr auto c3 = ENC("()Ldalvik/system/VMRuntime;");
        constexpr auto c4 = ENC("setHiddenApiExemptions");
        constexpr auto c5 = ENC("([Ljava/lang/String;)V");
        constexpr auto c6 = ENC("java/lang/String");
        constexpr auto c7 = ENC("Landroid/bluetooth/BluetoothSocket;");
        constexpr auto c8 = ENC("Landroid/bluetooth/BluetoothDevice;");

        JNIEnv* env;
        getVm()->AttachCurrentThread(&env, nullptr);

        jclass vmRuntime = env->FindClass(DEC(c1));
        jmethodID getRuntime = env->GetStaticMethodID(vmRuntime, DEC(c2), DEC(c3));
        jmethodID setExemptions = env->GetMethodID(vmRuntime, DEC(c4), DEC(c5));

        jobject runtime = env->CallStaticObjectMethod(vmRuntime, getRuntime);
        jobjectArray prefixes = env->NewObjectArray(
                2, env->FindClass(DEC(c6)), nullptr);
        env->SetObjectArrayElement(prefixes, 0, env->NewStringUTF(DEC(c7)));
        env->SetObjectArrayElement(prefixes, 1, env->NewStringUTF(DEC(c8)));

        env->CallVoidMethod(runtime, setExemptions, prefixes);
        getVm()->DetachCurrentThread();
        return nullptr;
    };

    pthread_t t;
    pthread_create(&t, nullptr, fn, nullptr);
    pthread_join(t, nullptr);
    return JNI_VERSION_1_6;
}
