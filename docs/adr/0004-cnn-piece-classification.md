# 逐格棋子分类改用 CNN（MobileNetV3-Small@128），NCC 退出主路

## Status

Accepted(2026-10-21),取代 [ADR-0002](0002-calibrated-template-matching.md)
中「NCC 模板匹配 + 红黑硬门」的判定核心;截屏 + 校准定位的地基不变。

## Context

NCC 模板匹配是"定死"的:只对线性亮度变化鲁棒,对叠加类污染(白光圈、
落子标记、动画残影、贴邻棋子阴影)完全无能为力,且红黑靠 `REDNESS_GATE`
二值硬门,红子被压色即翻成黑子。实测三类顽固误读(红帅判空/红车变黑车/
马象士三连闪)根因都在这里,循环层的锚点修复只能止血。

用户明确要求:**重点永远是提高识别率**,并选定方案 A(整体换血),
不接受的方案是在主路塞回 NCC 做兜底。

## Decision

- 逐格分类主路 = **MobileNetV3-Small,输入 3×128×128 RGB**,
  ImageNet 预训练微调,导出 ONNX,端上用 ONNX Runtime Mobile 推理
- 输入管线:`FrameSampling` 4 邻点双线性重采样,逐通道
  `(x/255-mean)/std`,与训练脚本 PIL `BILINEAR` 严格同源
- 只做**单次前向**:不做水平翻转 TTA(镜像汉字属训练分布外)
- 置信度 = `sigmoid(top1 - top2)`,三处实现符号统一
- 懒推理:每格 12 点灰度指纹,指纹未变复用缓存分类;指纹逐格同步
- NCC 源码保留但不接入运行路径:无影子对照、无回退、无工厂兜底
- 校准式四角定位、方向归一化、仲裁层(AnchorRecovery/speculate/
  margin-prune/落定确认期)全部保留——CNN 也会错,最后一公里靠它们

## Consequences

- 需要维护标注数据集(`samples/dataset/`,行列为 0 基,row0=屏幕最上排)
  与训练脚本(`tools/train_cnn.py`);模型随 APK 分发(`assets/cnn.onnx`)
- 验证口径改为**实时帧整批留出**(`Lmp_*`),硬错=0 才收录;
  cell 级随机划分因近重复会泄漏,不作门槛
- 动画/残影仍会以高置信形式误读,需继续用数据飞轮补样本,不在识别层
  调阈值追动画
- 新增风险面:训练与推理管线的任何参数漂移都会表现为"App 全错、PC 全对",
  必须用 App 落盘帧对拍(见 `docs/recurring-issues.md` 第 2 条)

## Alternatives

- **继续调 NCC 阈值/掩码**(否决):动画永远快过阈值,历史上已证明无效
- **NCC 与 CNN 影子双跑后逐格择优**(否决):用户已定 CNN 唯一主路,
  双跑带来两套分布与两套调参成本
- **端上训练/在线更新**(否决):手机算力不可行,且验证门槛无法在端上执行
