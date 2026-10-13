# 悬浮窗自动识别 — 真机验证与排查状态

> **⛔ 已归档(2026-10-13)**:本文记录的是 2026-09 时段的排查史,
> 所列"遗留问题"均已解决或转为路线图条目(见
> [features-and-roadmap.md](features-and-roadmap.md))。排查方法论已提炼进
> [development-lessons.md](development-lessons.md),保留本文仅作案例存档,
> 新排查不要从这里续接。

> 更新时间:2026-09-23(下午)。本文档记录悬浮窗自动识别功能(方案 A)的实现状态、
> 已修复问题与当前卡点,供后续排查续接使用。

## 〇、本会话结论(2026-09-23):卡点已解决,闭环已打通

上一版记录的「悬浮窗始终显示等待稳定棋盘画面」卡点,根因找到并修复:

1. **白色行棋光圈把空位 std 抬过判空阈值**:JJ 的行棋方指示光圈(亮白
   低饱和)压在棋盘边缘空点上时,窗口 std 实测 34.7 > SD_MIN=32,空位被
   误判有子,所有模板 d≈1 失败 → unknown。修复:`Features.removeWhiteOverlay`
   亮白低饱和像素替换为窗口中值灰度。PC 端验证 34.7→26.5 判空,90 点对照
   其余 89 点结果不变;棋子被光圈罩住时同样恢复 NCC 匹配。
2. **悬浮窗信息条自身遮挡 row0**:信息条默认位置 (40,200) 正压在
   (0,0)/(0,1)/(0,2) 上,d=1.0 持续 unknown;移到顶部 y=40 仍不够
   (3 行文字高约 350px 伸进识别区)。最终移到底部中央 (270,1560),
   JJ 棋盘识别区底约 y=1530,完全避开。识别 unknown 彻底消失。
3. **引擎「无结果」**:search 循环 800ms 到时即退,bestmove 还没来
   (polled=36 candidates=3 depth=12 但 bestMove 空)。修:stop 后补等
   bestmove 2s;nativeGo 前清空残留输出队列防上局行污染。
   验证:主界面与自动循环均稳定出 3 候选(如 +4.7 马二退一,深度 12)。

验证方法:BoardWatcher 识别失败时把 MediaProjection 原始帧落盘
`files/dump/`(限 5 张),`adb exec-out run-as com.cheese.assistant cat
files/dump/xxx.png` 拉出给 PC 端分析——本会话借此定位了上述两个遮挡源。

剩余小抖动:走子吃子瞬间 28/29 子帧交替触发 implausible rejects,
由现有 rejects=3 重置机制吸收,功能不受影响。

## 一、已实现(代码在 develop 分支,未提交)

### 自动识别闭环
- `ScreenCapture.captureStable(QUIET_MS=350, timeout=8000, isSame)`:
  双帧差分稳定检测。静止画面下 ImageReader 不推新帧,captureFresh(250) 超时
  即视为稳定直接返回已持有帧。
- `BoardWatcher.waitForPosition()`:等稳定 → 识别 → 可信校验
  (无未定点 + 双方各恰好一个王 + 棋子数 ≥ MIN_PIECES=10)。
- `BoardWatcher.normalizeOrientation()`:方向归一化。识别矩阵 row0 = 屏幕
  最上排,而 Position/FEN 约定 row0 = 黑方底线。按双王行位判断屏幕方向,
  红在上时上下翻转(列不变)。
- `MoveCheck.isPlausibleStep()`:新旧局面必须恰好"一子从 A 到 B"(可吃子),
  过滤动画中间帧;连续 3 次异常视为新对局重置。
- `AssistantService.startAutoLoop()`:自动循环,识别成功 → 引擎分析 →
  悬浮窗显示候选;识别失败显示「等待稳定棋盘画面...」并重试。

### 崩溃防御(已修复并验证有效)
- **缺王 SIGABRT 崩溃**(本会话核心修复):Pikafish 对非法局面
  `set_position` 返回 false 后引擎内部是空局面,Kotlin 端忽略返回值继续
  `nativeGo()` → 引擎缺王断言(position.cpp)abort 杀进程。
  修复:`SearchController.search()` 检查 `nativeSetPosition` 返回值,
  失败直接放弃本次搜索返回空结果(Ad. 修复后再无崩溃)。
- 双王校验、缺王 FEN 提前拦截均已生效:日志中「credibility fail」
  拦截了大厅 3D 棋盘、动画中间帧等误识别,防御按设计工作。

## 二、历史卡点(已解决,存档)

**现象:悬浮窗始终显示「等待稳定棋盘画面...」,识别层持续失败。**(根因见第〇节)

新 APK(带调试日志)实测循环活跃,约 700ms 一轮,失败原因两类交替出现:

1. **unknown 1~2 个未定点**:识别矩阵整体正确(所有棋子位置都对),
   但 row0(屏幕最上排)总有 1~2 个点 NCC 距离 d≈0.75~0.79,
   超过 UNKNOWN_DIST=0.75 阈值被判"不确定"。典型:`(0,0) d=0.754`、
   `(0,1) d=0.752~0.77`。
2. **credibility fail**:偶尔识别出多个"将"(redK=1 blackK=3 pieces=32),
   被双王校验正确拦截(疑似动画污染帧,非 bug)。

**关键矛盾**:
- PC 诊断脚本 `tools/diag_fresh.py`(完整复刻识别逻辑)对 adb screencap
  抓的同一画面识别**完全正确**,无未定点。
- App 端 MediaProjection 抓的帧却持续有 1~2 个未定点。
- → 说明 **MediaProjection 帧与 adb screencap 帧存在差异**
  (可能是:JJ 界面动画的时序差异、MediaProjection 压缩/色彩偏移、
  或截图瞬间光圈动画恰好落在该格)。
- 未定点集中在 row0(红方底线一排),redK=1 说明帅在 row0 中间能认出,
   但两端(0,0)/(0,1)等位置的 车/马 认不稳。

### 上一版 APK 的静默卡死(已随新 APK 消失,原因未定位)
旧版曾出现:unknown 4 之后 14 分钟完全无日志,autoJob 线程持续烧 ~20% CPU,
悬浮窗停在「等待稳定」不动。线程在 futex 定时等待,非死锁。新 APK 加了
异常日志(`runCatching.onFailure`)后未再复现——怀疑是识别/归一化抛异常
被 runCatching 静默吞掉导致热循环,但无直接证据。

## 三、下一步排查方向(按优先级)

1. **收尾**:清理 dump 落盘(验证已通过,可移除或做成开关);移除
   SearchCtl 的诊断 Log;28/29 子抖动可考虑识别成功后缓存局面 + 哈希
   未变时跳过重识别(省电)。
2. **遗留问题清单**(见第四节)逐项处理。

## 四、遗留问题清单

- [ ] START_STICKY 服务重启后悬浮窗与 autoJob 不重建(onStartCommand
      收到 null intent 时未重建窗口)——进程崩溃后悬浮窗永久消失。
- [ ] 重装 APK 后 MediaProjection 授权必失效,必须重新走授权流程
      (点按钮 → 授权弹窗 → 回来再点一次)。属系统限制,已知即可。
- [ ] 调试日志清理:BoardWatcher 3 处 + AutoLoop 2 处 Log,验证通过后
      降级或移除;`result.matrixText` 日志仅排障用。
- [ ] scratch 文件清理:`.scratch_*.png`、`tools/__pycache__/`、
      `tools/.scratch_*.png`。
- [ ] 提交:全部改动未提交。建议消息:
      `feat: 悬浮窗自动识别——双帧稳定检测 + 走子校验 + 方向归一化 + 非法局面防御`

## 五、排查环境备忘

- 设备:模拟器 127.0.0.1:5555(另有一台 emulator-5554 在线,
  所有 adb 命令必须 `-s 127.0.0.1:5555`)
- adb pull 到 Windows 路径须用 `"E:\\learn\\auto_cheese\\x.png"` 形式
- 构建:`JAVA_HOME=C:\Users\JSWPC2024101201\.jdks\temurin-17.0.12`;
  `gradle=D:\Android\gradle-8.7\bin\gradle.bat`;MSYS_NO_PATHCONV=1
- PC 诊断:在 tools/ 目录跑 `python diag_fresh.py`
  (读 `.scratch_debugframe.png`,完整复刻 App 识别逻辑含红黑硬门)
- UI 坐标:主界面悬浮窗按钮 (540,1176);JJ 练习模式底部按钮
  「重来」约 (540,1622);无 root,线程转储用
  `run-as com.cheese.assistant kill -3 <pid>`(trace 落 /data/anr,
  但无权限读,只有"活动/挂起"信号可用)
