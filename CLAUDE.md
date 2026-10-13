# 弈枰(中国象棋实战悬浮助手) — 项目须知

个人自用 Android App(悬浮窗形态,不上架,原名「棋室」,2026-10 更名「弈枰」)。
develop 分支开发,中文 commit,尾行 `Co-Authored-By: Claude ... <noreply@anthropic.com>`。

## 文档地图(改动前先看对应文档)

- [docs/usage.md](docs/usage.md) 使用文档(操作流程/排障)
- [docs/features-and-roadmap.md](docs/features-and-roadmap.md) 功能清单与路线图
- [docs/development-lessons.md](docs/development-lessons.md) 开发经验沉淀(新会话排障前必读)
- [docs/overlay-auto-recognize-status.md](docs/overlay-auto-recognize-status.md) 识别排查史(归档)
- [CONTEXT.md](CONTEXT.md) 领域语言;[docs/adr/](docs/adr/) 架构决策

## 构建环境

```bash
export JAVA_HOME="C:\Users\JSWPC2024101201\.jdks\temurin-17.0.12" MSYS_NO_PATHCONV=1
D:/Android/gradle-8.7/bin/gradle.bat assembleDebug -q
D:/Android/Sdk/platform-tools/adb.exe -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

- gradle:`D:\Android\gradle-8.7\bin\gradle.bat`;adb:`D:\Android\Sdk\platform-tools\adb.exe`
- Git Bash 下运行 Windows 程序须 `MSYS_NO_PATHCONV=1`,否则路径被改写

## 测试设备(模拟器)

- **所有 adb 命令必须 `-s 127.0.0.1:5555`**——本机常有两台设备在线
  (127.0.0.1:5555 和 emulator-5554),不指定会报 "more than one device"
- adb pull/push 到 Windows 路径须用 `"E:\\learn\\auto_cheese\\x.png"` 形式,
  `/e/learn/...` 会失败
- 截屏:`adb -s 127.0.0.1:5555 exec-out screencap -p > "E:\\learn\\auto_cheese\\x.png"`
- 无 root(production build):线程转储用
  `adb shell run-as com.cheese.assistant kill -3 <pid>`(trace 落 /data/anr 无权限读)

## 测试流程要点

- **每次 install -r 重装后 MediaProjection 授权必失效**:点悬浮窗按钮只弹
  Toast「授权后请再点一次此按钮」。完整流程:点按钮 → 授权页 → 回主界面再点一次
- 点悬浮窗按钮时若弹出「棋盘校准」悬浮按钮,那是 testCapture 流程的残留
  (校准按钮常驻不自动消失),点一下它关掉即可,与悬浮窗自动识别无关

## PC 识别诊断

在 `tools/` 目录运行 `python diag_fresh.py`——读取 `.scratch_debugframe.png`,
完整复刻 App 端识别逻辑(24x24 BOX 缩放 + 中心圆掩码 NCC + 红黑硬门),
用于对照 App 端识别结果与 PC 端是否一致。

## App 内置日志(排障用)

所有业务日志经 `util/AppLog` 同时进 logcat 与落盘 `files/logs/app-MMdd-HHmmss.log`
(按会话命名,自动保留最近 4 份,崩溃堆栈同步落盘)。用户报告"卡死/异常"时拉日志:

```bash
# 列出日志文件
adb -s 127.0.0.1:5555 shell "run-as com.cheese.assistant ls -t files/logs"
# 拉最新一份(卡死那天的那份)
adb -s 127.0.0.1:5555 exec-out run-as com.cheese.assistant \
  cat files/logs/<文件名> > "E:\\learn\\auto_cheese\\crash.log"
```

新增业务日志一律用 `AppLog.d/i/w/e(tag, msg)`(需要堆栈传第三个参数),
不要直接 android.util.Log——否则不落盘,事后无法排障。

## UI 坐标(1080x1920 模拟器)

- 主界面悬浮窗按钮为右下角朱红大圆,中心 (885,1531);JJ 人机对战 (540,1490);
  开始对弈 (540,1325);练习模式底部「重来」(540,1622)。改主界面布局后按钮
  位置会漂移,用 uiautomator dump 重测

## 识别/引擎关键约定(改动前必读)

- 识别矩阵 row0 = 屏幕最上排;Position/FEN 约定 row0 = 黑方底线。
  JJ 可能让红方在屏幕上方(我方执黑),必须走 normalizeOrientation 翻转
- Pikafish 对非法局面 set_position 返回 false;忽略返回值继续 go() 会触发
  缺王断言 abort 杀进程。SearchController.search() 已把关,勿移除
- `~/.claude/projects/e--learn-auto-cheese/memory/setup-lessons.md` 有
  引擎集成(setoption 格式、回调注册)的历史教训
