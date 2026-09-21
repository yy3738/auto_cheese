# auto_cheese — 中国象棋实战助手

悬浮窗形态的本地象棋引擎助手（个人自用）。

## 功能（开发中）

- [x] Pikafish 引擎本地集成（JNI 直驱，arm64-v8a / x86_64）
- [x] 手动摆盘 → 有限深度搜索（MultiPV 3）→ 中文纵线格式推荐走法
- [ ] 悬浮窗信息条
- [ ] MediaProjection 截屏 + OpenCV 校准式模板识别
- [ ] 跨对局局面知识库（Room + Zobrist）
- [ ] 复盘（评分曲线 + 坏棋标记）

## 构建

要求：Android Studio / Gradle 8.7 + JDK 17 + NDK + CMake 3.22。

NNUE 权重（~50MB）已包含在 `app/src/main/assets/pikafish.nnue`，
来源：[Pikafish Networks](https://github.com/official-pikafish/Networks/releases)。

```
gradle assembleDebug
```

## 技术选型

见 [CONTEXT.md](CONTEXT.md) 与 [docs/adr/](docs/adr/)。
