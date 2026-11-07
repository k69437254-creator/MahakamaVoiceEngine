#include <jni.h>
#include <sentencepiece_processor.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

static void throwJava(JNIEnv* env, const char* type, const std::string& message) {
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message.c_str());
}

static std::string toUtf8(JNIEnv* env, jstring input) {
    if (!input) return {};

    const jsize length = env->GetStringLength(input);
    const jchar* chars = env->GetStringChars(input, nullptr);
    if (!chars) return {};

    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);

    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];

        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < length &&
                chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10)
                     + (chars[++i] - 0xDC00);
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }

        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(input, chars);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aram_voiceengine_tokenizer_SentencePieceTokenizer_nativeCreate(
    JNIEnv* env, jobject, jstring modelPath) {

    const std::string path = toUtf8(env, modelPath);
    auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();

    const auto status = processor->Load(path);
    if (!status.ok()) {
        throwJava(env, "java/lang/IllegalStateException", status.ToString());
        return 0;
    }

    return reinterpret_cast<jlong>(processor.release());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_aram_voiceengine_tokenizer_SentencePieceTokenizer_nativeEncode(
    JNIEnv* env, jobject, jlong handle, jstring input) {

    auto* processor =
        reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);

    if (!processor) {
        throwJava(env, "java/lang/IllegalStateException", "Tokenizer is closed.");
        return nullptr;
    }

    const std::string text = toUtf8(env, input);
    std::vector<int> ids;

    const auto status = processor->Encode(text, &ids);
    if (!status.ok()) {
        throwJava(env, "java/lang/IllegalArgumentException", status.ToString());
        return nullptr;
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (!result) return nullptr;

    std::vector<jint> values(ids.begin(), ids.end());
    env->SetIntArrayRegion(
        result, 0, static_cast<jsize>(values.size()), values.data()
    );

    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aram_voiceengine_tokenizer_SentencePieceTokenizer_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {

    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
