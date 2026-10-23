# -*- coding: utf-8 -*-
"""导出 CharCNN 权重为 Android 端可加载的 JSON:
权重 Flatten 逐层输出(与 Kotlin 侧 Conv2d 全排列约定一致), 同时带
归一化参数和类名表。Kotlin 无依赖纯数组运算推理用。
RGB 3 通道: mean/std 为逐通道 3 元数组; 自检输入为 3×32×32 通道平面排列
(与 FrameSampling.toRgb32 和 Kotlin logits 的 [ch][y][x] 布局一致)。
"""
import json
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

CKPT = r'E:\learn\auto_cheese\tools\out\charcnn_best.pt'
OUT = r'E:\learn\auto_cheese\app\src\main\assets\charcnn.json'

ck = torch.load(CKPT, map_location='cpu', weights_only=False)
sd = ck['state']


def flat(k):
    return np.asarray(sd[k].cpu().numpy(), dtype=np.float64).ravel().tolist()


w1, w2, w3, wfc = flat('c1.weight'), flat('c2.weight'), flat('c3.weight'), flat('fc.weight')
b1, b2, b3, bfc = flat('c1.bias'), flat('c2.bias'), flat('c3.bias'), flat('fc.bias')

# 与 PC 端相同排列做一次 PC 端推理点检, 确认 flatten 顺序无歧义
w1a = sd['c1.weight'].numpy(); b1a = sd['c1.bias'].numpy()
w2a = sd['c2.weight'].numpy(); b2a = sd['c2.bias'].numpy()
w3a = sd['c3.weight'].numpy(); b3a = sd['c3.bias'].numpy()
wfa = sd['fc.weight'].numpy(); bfa = sd['fc.bias'].numpy()


def ref_forward(a):
    # a: (3,32,32) 通道平面
    x = torch.from_numpy(a[None].astype(np.float32))
    x = F.max_pool2d(F.relu(F.conv2d(x, torch.from_numpy(w1a), torch.from_numpy(b1a), padding=1)), 2)
    x = F.max_pool2d(F.relu(F.conv2d(x, torch.from_numpy(w2a), torch.from_numpy(b2a), padding=1)), 2)
    x = F.relu(F.conv2d(x, torch.from_numpy(w3a), torch.from_numpy(b3a), padding=1))
    x = F.max_pool2d(x, 2).flatten(1)
    return (x @ torch.from_numpy(wfa).t() + torch.from_numpy(bfa))


mean = np.asarray(ck['mean'], dtype=np.float64)   # (3,)
std = np.asarray(ck['std'], dtype=np.float64)

# 随机输入对拍用参考输出
rng = np.random.default_rng(0)
probe = rng.uniform(0, 255, (3, 32, 32))
ref = ref_forward((probe - mean[:, None, None]) / std[:, None, None]).argmax(1).item()

# 真实样本自检向量: r9c4(白圈红帥历史失败帧) 的输入+全部 logits,
# Android 端集成后用同一输入对拍, 逐项误差 <1e-3 才算实现正确。
# 注意模型训练时输入是 (x-mean[c])/std[c], 参考 logits 必须喂归一化后的图
from PIL import Image
hist = np.asarray(Image.open(
    r'E:\learn\auto_cheese\samples\game1\r9c4.png').convert('RGB')
    .resize((32, 32), Image.BILINEAR), dtype=np.float64)   # (32,32,3)
hist = hist.transpose(2, 0, 1)                             # (3,32,32) 通道平面
probe2 = (hist - mean[:, None, None]) / std[:, None, None]
logits = ref_forward(probe2).numpy()

data = {
    'img': 32,
    'mean': mean.tolist(),   # 逐通道 3 元
    'std': std.tolist(),
    'classes': ck['classes'],
    # conv 权重按 [out][in][3*3] 排列; fc 按 [out][24]
    'w1': w1, 'b1': b1,
    'w2': w2, 'b2': b2,
    'w3': w3, 'b3': b3,
    'wfc': wfc, 'bfc': bfc,
    'probe_argmax': int(ref),
    # 自检: 原始 3×32×32 通道平面(0-255) + 归一化输入 + 15 类 logits + 正确类名
    'selftest_input': hist.ravel().tolist(),
    'selftest_norm': probe2.ravel().tolist(),
    'selftest_logits': np.asarray(logits, dtype=np.float64).ravel().tolist(),
    'selftest_class': ck['classes'][int(logits.argmax())],
}
json.dump(data, open(OUT, 'w'), separators=(',', ':'))
print(f"OK → {OUT}  ({len(open(OUT).read())//1024} KB)")
print(f"probe 3x32x32 随机图参考 argmax = {ref} ({ck['classes'][ref]})")
