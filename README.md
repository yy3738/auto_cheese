# 弈枰 — 中国象棋实战悬浮助手

悬浮窗形态的个人自用象棋助手：JJ 象棋对局中自动截屏识别棋局，
本地 Pikafish 引擎给出带评分的推荐走法；封存对局后自动复盘打分，
跨对局局面知识库让同局面秒回。不上架。

> 前身叫「棋室」，2026-10 更名「弈枰」（枰 = 棋盘）。

## 功能一览（全部实机验证通过）

- **实战悬浮助手**：MediaProjection 截屏 → 校准式模板识别（NCC）→
  方向归一化 → 走子校验 → 引擎分析 → 悬浮窗口显示
  「X方回合 + 三条候选」，打吃/将军用「吃」「将」徽章醒目标出
- **防卡死架构**：识别循环整层兜底、引擎搜索互斥、非法局面拦截、
  光圈脉动/幽灵步等误识别在循环层容忍修复（识别层永远追不上动画）
- **结 → 复盘 → 下一局闭环**：对局中点「结」封存入库 → 逐局面深搜复盘
  打分（失误/大漏着）→ 朱红「下一局」一键开新局
- **知识库**：Zobrist 局面指纹 + Room 持久化，复盘过的局面再次出现直接秒回
- **内置日志落盘**：`AppLog` 全量业务日志写 `files/logs/`，崩溃堆栈同步落盘，
  卡死现场可事后回放（见 [CLAUDE.md](CLAUDE.md) 拉取命令）
- **墨纸编辑部版式**：纸底墨字朱红点睛的极简主界面 +
  藏蓝渐变标题栏的悬浮窗，可拖动、可收起为圆点

## 构建

要求：JDK 17 + Gradle 8.7 + NDK + CMake 3.22（Windows 下环境变量见
[CLAUDE.md](CLAUDE.md)，模拟器调试流程同样在内）。

```
gradle assembleDebug        # NNUE 权重 ~50MB 已打包在 app/src/main/assets/
```

## 30 秒上手

1. 主界面点右下朱红圆钮「开始实战辅助」→ 授予截屏权限 → **回主界面再点一次**
   （授权后会失效一次，再点才真正启动）
2. 首次使用先点「校准」：3 秒内切到目标棋盘 App，标定棋盘四角
3. 切到 JJ 象棋对局，悬浮窗自动出推荐；走完一局点「结」复盘

详细步骤与故障排查（授权失效 / 识别降级 / 日志导出）见
[docs/usage.md](docs/usage.md)。

## 文档地图

| 文档 | 内容 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | AI 协作须知：构建环境、adb 约定、拉日志命令、引擎/识别关键约定 |
| [docs/usage.md](docs/usage.md) | 使用文档：完整操作流程 + 排障 |
| [docs/features-and-roadmap.md](docs/features-and-roadmap.md) | 功能清单与路线图（需求口径） |
| [docs/development-lessons.md](docs/development-lessons.md) | 开发对话中沉淀的工程经验 |
| [docs/overlay-auto-recognize-status.md](docs/overlay-auto-recognize-status.md) | 自动识别排查史（归档） |
| [CONTEXT.md](CONTEXT.md) | 领域语言与概念定义 |
| [docs/adr/](docs/adr/) | 架构决策记录（引擎选型/模板匹配/知识库） |

## 技术选型

Kotlin + 协程；Pikafish(UCI) 经 JNI 直驱，NNUE 64MiB 来自
[官方 Networks](https://github.com/official-pikafish/Networks/releases)；
MediaProjection + ImageReader 抓帧；NCC 模板匹配识别（OpenCV 原型验证后
纯 Kotlin 移植）；Room + Zobrist 知识库。
