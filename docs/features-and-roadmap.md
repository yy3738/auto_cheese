# 功能清单与路线图

> 更新时间:2026-09-23。已实现功能以代码为准(git 历史 + develop 未提交改动);
> 下一步规划综合了此前讨论与本轮验证中暴露的需求。
> 排查中的具体问题见 [overlay-auto-recognize-status.md](overlay-auto-recognize-status.md)。

## 一、已实现功能

### 1. 引擎(Pikafish + NNUE)
- Pikafish 通过 JNI 集成(`pikafish_jni.cpp`),NNUE 权重(64MiB)打包进
  assets,首次启动解压到内部存储
- 引擎选项:Threads=3、MultiPV=3、Hash=64
- `SearchController`:限时搜索(movetime 800ms)封装为挂起函数,解析
  UCI info/bestmove 流,按 rank 汇总 MultiPV 候选(评分/杀棋/深度/PV)
- **非法局面防御**:缺王 FEN 提前拦截 + `nativeSetPosition` 返回值检查,
  杜绝 Pikafish 缺王断言 SIGABRT 杀进程(本阶段核心修复)

### 2. 棋盘数据层
- `Position`:90 点棋盘模型,row0=黑方底线的 FEN 约定、走子生成、
  走法中文格式化(MoveFormatter.toChinese)
- 摆盘工作台(BoardView):点击摆子/擦除、摆开局/清空/换先、FEN 实时显示

### 3. 截屏链路(MediaProjection)
- 授权页(CapturePermissionActivity)→ 单例 ScreenCapture
  (VirtualDisplay + ImageReader,RGBA_8888)
- `captureFresh`:丢陈旧帧等新帧(修复后台不推流时取到几秒前旧帧的问题)
- `captureStable`:双帧差分稳定检测(48x48 降采样灰度量化比较,
  差异点 ≤ 2% 视为稳定)——对局动画结束的信号,容忍选中光圈等微动画

### 4. 校准式棋盘定位(通用第三方棋盘 App)
- 主界面「截屏校准棋盘」:3 秒倒计时切目标 App 抓屏,悬浮「校准」按钮
  点击进入 CalibrateActivity,标定棋盘四角 → BoardGrid(90 交叉点)
- GridRefiner 棋盘线吸附(修正手工标定的 ~8px 偏差)
- 校准结果持久化(files/calib_default.json),按目标 App 区分(TARGET_APP="default")

### 5. 棋子识别(NCC 模板匹配,PC 原型验证后移植)
- 模板采样:RecognizeActivity 工作台采样各色棋子 → TemplateStore 持久化
- 识别算法 v2:24x24 BOX 区域平均缩放灰度 → 零均值/单位方差归一化 →
  中心圆掩码 → ±3px 平移 NCC;SD_MIN=32 判空、REDNESS_GATE=40 红黑硬门、
  UNKNOWN_DIST=0.75 未定判定
- 三轮已知识别 bug 均已修:红方底线错位(BOX 缩放替代双线性)、
  陈旧帧漏识别、JJ 红色落子标记框污染 NCC(识别前剔除纯红标记像素并膨胀 1px)

### 6. 悬浮窗助手(前台服务)
- OverlayView 信息条:悬浮于其他 App 上层,可拖动、可关闭
- 手动模式:主界面分析 → FEN 经 intent 交给服务 → 悬浮窗显示候选
- **自动识别模式(本轮新增,验证中)**:startAutoLoop 循环——
  captureStable 等稳定 → 识别 → 可信校验(无未定点/双王各一/棋子数≥10)→
  方向归一化(红上黑下时上下翻转)→ MoveCheck 走子校验(恰好一子从 A 到 B,
  连续 3 次异常视为新对局重置)→ 引擎分析 → 悬浮窗显示「X方回合 + 候选」
- 识别失败帧落盘诊断(本轮新增):BoardWatcher 可选 dumpDir,识别失败时
  把 MediaProjection 原始帧写入 files/dump/(限 5 张),adb pull 供 PC 端对比

### 7. PC 端工具链(tools/)
- proto_recognize.py:识别算法原型(PC 端先验证再移植 App)
- diag_fresh.py:读 debugframe 完整复刻 App 识别逻辑,对照定位识别差异层
- diag.py / diag_markers.py:早期诊断脚本

## 二、下一步要实现的功能

### 阶段 A:悬浮窗自动识别收尾(当前进行中)
1. **解决 MediaProjection 帧与 screencap 帧的识别差异**——App 端持续
   1~2 个未定点而 PC 对同画面全对。用新加的 dumpFrame 落盘真实失败帧,
   PC 端 diag 对比定位(方向:动画时序 / MP 帧色彩差异 / row0 边缘干扰)
2. 确定修复策略(按诊断结果):放宽 UNKNOWN_DIST / 多帧投票 / row0 单独容差
3. 真机完整验证:开局自动出候选 → 走一步 → 自动更新回合与候选
4. 收尾:清理调试日志(BoardWatcher/AutoLoop 的 Log)、关闭 dumpFrame
   或改为开关控制、提交

### 阶段 B:稳定性修补
1. **START_STICKY 重启不重建悬浮窗**:进程被杀后系统重启 service,
   onStartCommand intent=null 时悬浮窗与 autoJob 均不重建,悬浮窗永久消失。
   在 null intent 分支重建(悬浮窗 + 若此前处于自动模式则重启 autoLoop)
2. 长时间运行内存观察(此前有 LOS 对象 25MB 的 GC 记录,疑似 Bitmap 缓存)

### 阶段 C:Room 知识库 + 复盘(用户已明确的下一阶段)
1. Room 持久化:对局记录(局面序列/FEN/引擎评分/实际走法)
2. 复盘功能:对局结束后逐步回放,对照引擎评分标注疑问手
3. 局面知识库:按局面特征(局面类型/评分区间)积累经验数据
4. (细节待与用户对齐后补充)

### 远期候选(未排期)
- 多目标 App 支持校准(目前 TARGET_APP 固定 "default")
- 引擎分析深度/时长可调(设置界面)
- 自动识别循环的低功耗优化(识别成功后画面未变时跳过重识别)

## 三、当前状态速览

- 代码:develop 分支,悬浮窗自动识别功能已实现但**未提交**
- 真机:闭环曾验证通过一次(识别→归一化→分析→悬浮窗显示),当前卡在
  row0 未定点导致的「等待稳定棋盘画面」循环(详见排查文档)
- 文档:本文档 + overlay-auto-recognize-status.md(问题清单/排查方向)+
  CLAUDE.md(环境与测试流程)
