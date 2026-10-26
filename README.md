# 弈枰 — 中国象棋实战悬浮助手

悬浮窗形态的个人自用象棋助手：JJ 象棋对局中自动截屏识别棋局，
本地 Pikafish 引擎给出带评分的推荐走法；封存对局后自动复盘打分，
跨对局局面知识库让同局面秒回。不上架。

> 前身叫「棋室」，2026-10 更名「弈枰」（枰 = 棋盘）。

## 功能一览（全部实机验证通过）

- **实战悬浮助手**：MediaProjection 截屏 → 校准式定位 → **CNN 主路逐格
  识别（MobileNetV3-Small@128 + ONNX Runtime）** → 方向归一化 → 仲裁/走子
  校验 → 引擎分析 → 悬浮窗口显示「X方回合 + 三条候选」，打吃/将军用
  「吃」「将」徽章醒目标出
- **防卡死架构**：识别循环整层兜底、引擎搜索互斥、非法局面拦截、
  光圈脉动/幽灵步等误识别在循环层容忍修复（识别层永远追不上动画）
- **中途加入**：切到进行中的对局，点悬浮窗「开局」即以当前盘面接手；
  重装后旧棋谱自动清盘（安装事件）
- **局前零识别**：授权后只亮悬浮窗（只有「开局」），不抓帧、不跑 CNN、
  不弹识别提示；点「开局」才开始记账
- **结 → 复盘 → 回开局闭环**：对局中点「结」封存入库 → 逐局面深搜复盘
  打分（按钮实时「复盘X/Y」）→ 完成后回到局前态，点「开局」开新局
- **多步确认**：一次落定里出现 3-4 手且有多种合法解释时，弹列表式候选
  （如 `① 红 车四进一 → 黑 卒7进1`）让你点选，确认结果同时作为重训样本
- **知识库**：Zobrist 局面指纹 + Room 持久化，复盘过的局面再次出现直接秒回
- **内置日志落盘**：`AppLog` 全量业务日志写 `files/logs/`，崩溃堆栈同步落盘
  （进程启动即开启，授权页等早期崩溃也留证）；主界面「导出日志」一键复制到
  `下载/象棋助手日志/`，无需 adb
- **Android 14+ 适配**：按系统要求先启动 `mediaProjection` 前台服务再创建
  投屏，并注册 `MediaProjection.Callback`（否则点「立即开始」直接闪退）
- **墨纸编辑部版式**：纸底墨字朱红点睛的极简主界面 +
  藏蓝渐变标题栏的悬浮窗，可拖动、可收起为圆点

## 构建

要求：JDK 17 + Gradle 8.7 + NDK + CMake 3.22（Windows 下环境变量见
[CLAUDE.md](CLAUDE.md)，模拟器调试流程同样在内）。

```
gradle assembleDebug        # NNUE 权重 ~50MB 已打包在 app/src/main/assets/
```

## 30 秒上手

1. 首次使用先点「校准」：3 秒内切到目标棋盘 App，依次点棋盘四角
   （左上→右上→右下→左下）。**校准按设备/分辨率保存**，换设备或改显示
   大小后需重校
2. 回主界面点右下朱红圆钮「开始实战辅助」→ 按提示授予「使用情况访问」
   与截屏权限（Android 14+ 每次开启都要确认一次）→ 悬浮窗自动出现
3. 切到 JJ 对局，点悬浮窗「开局」→ 出推荐；走完一局点「结」复盘
4. 圆钮是开关：运行中显示「停止实战辅助」，停止会释放投屏，再次开启
   需重新授权

详细步骤与故障排查（授权失效 / 识别降级 / 日志导出）见
[docs/usage.md](docs/usage.md)。

## 文档地图

| 文档 | 内容 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | AI 协作须知：构建环境、adb 约定、拉日志命令、引擎/识别关键约定 |
| [docs/usage.md](docs/usage.md) | 使用文档：完整操作流程 + 排障 + 日志/样本导出 |
| [docs/features-and-roadmap.md](docs/features-and-roadmap.md) | 功能清单与路线图（需求口径） |
| [docs/development-lessons.md](docs/development-lessons.md) | 开发对话中沉淀的工程经验 |
| [docs/recurring-issues.md](docs/recurring-issues.md) | 复发问题登记册（症状→病因速查，排障先看） |
| [docs/charcnn-upgrade.md](docs/charcnn-upgrade.md) | CNN 迁移设计与数据标注口径 |
| [docs/overlay-auto-recognize-status.md](docs/overlay-auto-recognize-status.md) | 自动识别排查史（归档） |
| [CONTEXT.md](CONTEXT.md) | 领域语言与概念定义 |
| [docs/adr/](docs/adr/) | 架构决策记录（引擎选型/模板匹配/知识库） |

## 技术选型

Kotlin + 协程；Pikafish(UCI) 经 JNI 直驱，NNUE 64MiB 来自
[官方 Networks](https://github.com/official-pikafish/Networks/releases)；
MediaProjection + ImageReader 抓帧；**MobileNetV3-Small@128 + ONNX Runtime
Mobile** 逐格分类（ImageNet 预训练微调，约 2500 张标注样本）；Room + Zobrist
知识库。
