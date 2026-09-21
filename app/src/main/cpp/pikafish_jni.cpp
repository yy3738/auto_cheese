// Pikafish 引擎桥：直接驱动 Stockfish::Engine，绕过 stdio/UCI 文本协议。
// 输出仍以 UCI 行文本形式投递给 Java 端，复用其解析器。
#include <jni.h>
#include <string>
#include <deque>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <sstream>
#include <android/log.h>

#include "attacks.h"
#include "engine.h"
#include "position.h"
#include "score.h"
#include "search.h"
#include "types.h"
#include "uci.h"

#define LOG_TAG "PikafishShim"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace Stockfish;

namespace {

std::unique_ptr<Engine> g_engine;
bool                    g_initialized = false;

std::mutex              g_outMutex;
std::deque<std::string> g_outQueue;
std::condition_variable g_outCv;

void pushLine(const std::string& line) {
    {
        std::lock_guard<std::mutex> lock(g_outMutex);
        g_outQueue.push_back(line);
    }
    g_outCv.notify_one();
}

template<typename... Ts>
struct Overload: Ts... {
    using Ts::operator()...;
};
template<typename... Ts>
Overload(Ts...) -> Overload<Ts...>;

std::string formatScore(const Score& s) {
    return s.visit(Overload{[](Score::Mate mate) -> std::string {
                                auto m = (mate.plies > 0 ? (mate.plies + 1) : mate.plies) / 2;
                                return std::string("mate ") + std::to_string(m);
                            },
                            [](Score::InternalUnits units) -> std::string {
                                return std::string("cp ") + std::to_string(units.value);
                            }});
}

// 与 UCIEngine::on_update_full 相同的 info 行格式，Kotlin 端解析器无需改动
void onFullUpdate(const Engine::InfoFull& info) {
    std::stringstream ss;
    ss << "info";
    ss << " depth " << info.depth
       << " seldepth " << info.selDepth
       << " multipv " << info.multiPV
       << " score " << formatScore(info.score);
    if (!info.bound.empty()) ss << " " << info.bound;
    ss << " nodes " << info.nodes << " nps " << info.nps
       << " hashfull " << info.hashfull
       << " time " << info.timeMs
       << " pv " << info.pv;
    pushLine(ss.str());
}

void onBestMove(std::string_view bestmove, std::string_view ponder) {
    std::string line = std::string("bestmove ") + std::string(bestmove);
    if (!ponder.empty()) line += " ponder " + std::string(ponder);
    pushLine(line);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeInit(JNIEnv* env, jobject, jstring nnuePath) {
    if (g_initialized) return JNI_TRUE;
    try {
        Attacks::init();
        Position::init();
        // nnuePath 为权重文件绝对路径，经 EvalFile 选项触发 load_network
        const char* p = env->GetStringUTFChars(nnuePath, nullptr);
        std::string evalFile = p ? p : "";
        env->ReleaseStringUTFChars(nnuePath, p);

        g_engine = std::make_unique<Engine>(std::nullopt);
        g_engine->set_on_update_full([](const Engine::InfoFull& info) { onFullUpdate(info); });
        g_engine->set_on_bestmove(
          [](std::string_view bm, std::string_view p) { onBestMove(bm, p); });
        // 搜索时引擎会调用所有已注册的监听器，缺任何一个都是 bad_function_call
        g_engine->set_on_update_no_moves([](const Engine::InfoShort& info) {
            std::stringstream ss;
            ss << "info depth " << info.depth << " score " << formatScore(info.score);
            pushLine(ss.str());
        });
        g_engine->set_on_iter([](const Engine::InfoIter&) {});
        g_engine->set_on_start([] {});
        g_engine->set_on_bestmove(
          [](std::string_view bm, std::string_view p) { onBestMove(bm, p); });
        // 引擎的校验/诊断信息（NNUE 加载结果等）输出到 logcat
        g_engine->set_on_verify_network([](std::string_view s) {
            ALOGE("engine: %.*s", static_cast<int>(s.size()), s.data());
        });
        g_initialized = true;

        auto& options = g_engine->get_options();
        // OptionsMap::setoption 期望流从 "name" 开始（"setoption" 已在 UCI 分发层消费）
        std::istringstream iss("name EvalFile value " + evalFile);
        options.setoption(iss);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        ALOGE("engine init failed: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeSetOption(JNIEnv* env, jobject,
                                                                jstring name, jstring value) {
    if (!g_initialized) return JNI_FALSE;
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    auto& options = g_engine->get_options();
    if (!options.count(n)) {
        env->ReleaseStringUTFChars(name, n);
        env->ReleaseStringUTFChars(value, v);
        return JNI_FALSE;
    }
    // 通过 Option 的字符串写入触发其 on_change（如 Threads/EvalFile 的副作用）
    std::istringstream iss("name " + std::string(n) + " value " + std::string(v));
    options.setoption(iss);
    env->ReleaseStringUTFChars(name, n);
    env->ReleaseStringUTFChars(value, v);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeSetPosition(JNIEnv* env, jobject,
                                                                  jstring fen,
                                                                  jobjectArray moves) {
    if (!g_initialized) return JNI_FALSE;
    const char* f = env->GetStringUTFChars(fen, nullptr);
    std::string fenStr = f;
    env->ReleaseStringUTFChars(fen, f);

    std::vector<std::string> moveList;
    if (moves != nullptr) {
        const jsize n = env->GetArrayLength(moves);
        for (jsize i = 0; i < n; ++i) {
            jstring s = static_cast<jstring>(env->GetObjectArrayElement(moves, i));
            const char* m = env->GetStringUTFChars(s, nullptr);
            moveList.emplace_back(m);
            env->ReleaseStringUTFChars(s, m);
        }
    }

    if (auto err = g_engine->set_position(fenStr, moveList)) {
        ALOGE("set_position failed: %s", err->what());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeGo(JNIEnv*, jobject) {
    if (!g_initialized) return;
    Search::LimitsType limits;
    g_engine->go(limits);
}

JNIEXPORT void JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeStop(JNIEnv*, jobject) {
    if (g_initialized) g_engine->stop();
}

JNIEXPORT jstring JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativePollLine(JNIEnv* env, jobject,
                                                               jint timeoutMs) {
    std::unique_lock<std::mutex> lock(g_outMutex);
    if (!g_outCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                          [] { return !g_outQueue.empty(); }))
        return nullptr;
    std::string line = std::move(g_outQueue.front());
    g_outQueue.pop_front();
    return env->NewStringUTF(line.c_str());
}

JNIEXPORT void JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeQuit(JNIEnv*, jobject) {
    if (!g_initialized) return;
    g_engine->wait_for_search_finished();
    g_engine.reset();
    g_initialized = false;
}

}  // extern "C"
