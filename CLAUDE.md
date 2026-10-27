# 弈枰(中国象棋实战悬浮助手) — 项目须知

个人自用 Android App(悬浮窗形态,不上架,原名「棋室」,2026-10 更名「弈枰」)。
develop 分支开发(方案 A / CNN 主路在 `feature-cnn`),中文 commit,尾行
`Co-Authored-By: Claude ... <noreply@anthropic.com>`。

## 文档地图(改动前先看对应文档)

> 2026-10 的诊断日志/旧模型/发行包已归档到本地 `archive/`（gitignore）：
> 文档里引用的 `_*.log` 在 `archive/logs-2026-10/`，旧模型与审图在
> `archive/tools-out-old/`，Pikafish 发行包在 `archive/pikafish/`。

- [docs/usage.md](docs/usage.md) 使用文档(操作流程/排障)
- [docs/features-and-roadmap.md](docs/features-and-roadmap.md) 功能清单与路线图
- [docs/development-lessons.md](docs/development-lessons.md) 开发经验沉淀(新会话排障前必读)
- [docs/recurring-issues.md](docs/recurring-issues.md) 复发问题登记册(症状→病因速查,先看这里)
- [docs/charcnn-upgrade.md](docs/charcnn-upgrade.md) CNN 迁移设计与数据标注口径
- [docs/overlay-auto-recognize-status.md](docs/overlay-auto-recognize-status.md) 识别排查史(归档)
- [CONTEXT.md](CONTEXT.md) 领域语言;[docs/adr/](docs/adr/) 架构决策

## 构建环境

```bash
export JAVA_HOME="C:\Users\JSWPC2024101201\.jdks\temurin-17.0.12" MSYS_NO_PATHCONV=1
D:/Android/gradle-8.7/bin/gradle.bat assembleDebug -q
D:/Android/Sdk/platform-tools/adb.exe -s <序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

- gradle:`D:\Android\gradle-8.7\bin\gradle.bat`;adb:`D:\Android\Sdk\platform-tools\adb.exe`
- Git Bash 下运行 Windows 程序须 `MSYS_NO_PATHCONV=1`,否则路径被改写

## 测试设备(模拟器)

- **所有 adb 命令必须带 `-s <序列号>`**——本机可能多台设备在线
  (历史上有 `127.0.0.1:5555`(Android 9) 与 `emulator-5554`(Android 14),
  以 `adb devices` 实际输出为准),不指定会报 "more than one device"
- Android 14+ 的投屏授权与 Android 9 行为不同,排障时先确认系统版本
- adb pull/push 到 Windows 路径须用 `"E:\\learn\\auto_cheese\\x.png"` 形式,
  `/e/learn/...` 会失败
- 截屏:`adb -s <序列号> exec-out screencap -p > "E:\\learn\\auto_cheese\\x.png"`
- 无 root(production build):线程转储用
  `adb shell run-as com.cheese.assistant kill -3 <pid>`(trace 落 /data/anr 无权限读)

## 测试流程要点

- **每次 install -r 重装后 MediaProjection 授权必失效**:完整流程为
  点按钮 → 授权页 → 授权成功服务自动启动(单按钮流程,无需二次点击)。
  重装后棋谱/FEN 由安装事件自动清盘
- 首次使用需在系统设置里授权〈使用情况访问〉(前台 App 判定主轨;
  未授权时退回 JJ 边条像素指纹)
- 点悬浮窗按钮时若弹出「棋盘校准」悬浮按钮,那是 testCapture 流程的残留
  (校准按钮常驻不自动消失),点一下它关掉即可,与悬浮窗自动识别无关
- **不要主动用 adb 驱动用户正在操作的界面**(force-stop/am start/monkey
  会顶掉对局与实况页,多次造成"识别不出来"假象);由用户测试

## PC 识别诊断(CNN 对拍)

`tools/proto_recognize.py` + `tools/crop_cells.py` 可对**App 自己落盘的帧**
复刻 App 端管线:校准四角 → 原始网格 → 双线性裁格 → `assets/cnn.onnx` 推理。
对拍时必须用 App 落盘帧(`files/dump/`),不要用 adb screencap 代替;
Kotlin 端与 PC 端的插值方式/crop 边界/归一化/是否 TTA 必须逐参数一致。

## App 内置日志(排障用)

所有业务日志经 `util/AppLog` 同时进 logcat 与落盘 `files/logs/app-MMdd-HHmmss.log`
(按会话命名,自动保留最近 4 份,崩溃堆栈同步落盘)。用户报告"卡死/异常"时拉日志:

```bash
# 列出日志文件
adb -s <序列号> shell "run-as com.cheese.assistant ls -t files/logs"
# 拉最新一份(卡死那天的那份)
adb -s <序列号> exec-out run-as com.cheese.assistant \
  cat files/logs/<文件名> > "E:\\learn\\auto_cheese\\crash.log"
```

新增业务日志一律用 `AppLog.d/i/w/e(tag, msg)`(需要堆栈传第三个参数),
不要直接 android.util.Log——否则不落盘,事后无法排障。

## UI 坐标

- 下列坐标按 **1080x1920** 模拟器实测;当前 Android 14 模拟器是 1080x2400,
  坐标不同,改布局/换设备后一律用 `uiautomator dump` 重测
- 主界面悬浮窗按钮为右下角朱红大圆,中心 (885,1531);JJ 人机对战 (540,1490);
  开始对弈 (540,1325);练习模式底部「重来」(540,1622)

## 识别/引擎关键约定(改动前必读)

- **CNN 主路唯一**:`RecognizerEngineFactory` 只返回 `CnnEngine`;NCC 已退出
  全部运行路径(源码保留但不得塞回兜底/影子对照——用户已定架构)
- **OnnxCnn 只做单次前向**:翻转 TTA 会把镜像汉字喂成分布外输入,实测制造
  幻影棋子(margin=1.0 顶掉真子)
- **FrameSampling 必须是 4 邻点双线性**:面积均值在 104→128 上采样会退化成
  块复制,与训练 PIL `BILINEAR` 分布背离,表现为 App 内全体低置信拒判
- `margin = sigmoid(top1 - top2)`,训练/PC 脚本/Android 三处符号必须一致
- 懒推理的指纹数组必须与分类结果**逐格同步**更新,否则冻结期错配固化
- 识别矩阵 row0 = 屏幕最上排;Position/FEN 约定 row0 = 黑方底线。
  JJ 可能让红方在屏幕上方(我方执黑),必须走 normalizeOrientation 翻转
- **引擎是进程级单例**(`EngineHolder`,2026-10-26):CheeseApp 启动即预热,
  主界面/悬浮窗共用同一个 SearchController;JNI `g_engine` 是进程级全局,
  **不要**再新建第二个 controller。实战 movetime 1200ms、复盘 3000ms
- **JNI 直调 `Engine::go` 必须自己设 `limits.startTime`**(UCI 层才设),
  否则时间检查 `now-0` 立即 stop(历史 bug:每手 29-90ms、depth 3-7)
- **推荐列表只出一次**:不要恢复"渐进首搜/浅深度补搜"——实测会让候选
  先出一批又换一批、顺序还变(用户明确反馈)
- **暂停语义**:暂停只停采集/推荐,保留棋谱与投屏;继续时首帧只重同步、
  暂停期间(JJ 沙盘/摆子)的走子不记账
- **Android 14 投屏**:先 `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`
  再 `getMediaProjection`,并在 `createVirtualDisplay` 前注册
  `MediaProjection.Callback`;停止服务要 `ScreenCapture.stop()` 释放投屏
- Pikafish 对非法局面 set_position 返回 false;忽略返回值继续 go() 会触发
  缺王断言 abort 杀进程。SearchController.search() 已把关,勿移除
- `~/.claude/projects/e--learn-auto-cheese/memory/setup-lessons.md` 有
  引擎集成(setoption 格式、回调注册)的历史教训
