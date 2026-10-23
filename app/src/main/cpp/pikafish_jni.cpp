// Pikafish 引擎桥：直接驱动 Stockfish::Engine，绕过 stdio/UCI 文本协议。
// 输出仍以 UCI 行文本形式投递给 Java 端，复用其解析器。
#include <jni.h>
#include <string>
#include <deque>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <sstream>
#include <atomic>
#include <thread>
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
// NNUE 权重校验完成标志：EvalFile 的加载/校验是异步的，setoption 返回时
// 网络可能还没就绪。首搜若抢在加载前会返回深度1/评分0的退化结果
// （实测 14:37:09.883 搜索、09.927 NNUE 才 ready → 首推兵九进一 +0.0）。
std::atomic<bool>       g_networkReady{false};

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
            g_networkReady.store(true);
        });
        g_initialized = true;

        auto& options = g_engine->get_options();
        // OptionsMap::setoption 期望流从 "name" 开始（"setoption" 已在 UCI 分发层消费）
        std::istringstream iss("name EvalFile value " + evalFile);
        g_networkReady.store(false);
        options.setoption(iss);
        // 等 NNUE 校验完成再返回：否则调用方立刻 go() 会在无网络状态搜索，
        // 得到深度1/评分0的退化结果。10s 超时兜底（模拟器加载约 3-6s）。
        for (int i = 0; i < 100 && !g_networkReady.load(); ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (!g_networkReady.load())
            ALOGE("engine: NNUE verify timeout after 10s, first search may be degenerate");
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
Java_com_cheese_assistant_engine_PikafishEngine_nativeGo(JNIEnv*, jobject, jint movetimeMs) {
    if (!g_initialized) return;
    Search::LimitsType limits;
    // movetime 由引擎自查自停:此前不改 limits 时引擎全功率搜索,
    // 靠 Java 层在 movetime+3000ms 后才 stop——每次推荐白搜 3 秒
    if (movetimeMs > 0) limits.movetime = movetimeMs;
    g_engine->go(limits);
}

JNIEXPORT void JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeStop(JNIEnv*, jobject) {
    if (g_initialized) g_engine->stop();
}

// 等上一轮搜索线程真正退出：只有 searchMutex 串行化还不够，native 搜索
// 线程可能在 stop() 后仍在收尾；下一轮 go 若此时发出会被忽略/串到上一轮
// 的残留输出（实测同一 root move 以两个 multipv 排名、不同评分出现）。
JNIEXPORT void JNICALL
Java_com_cheese_assistant_engine_PikafishEngine_nativeWaitForSearchFinished(JNIEnv*, jobject) {
    if (g_initialized) g_engine->wait_for_search_finished();
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
    g_networkReady.store(false);
}

}  // extern "C"
