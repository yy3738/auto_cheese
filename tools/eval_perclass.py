# -*- coding: utf-8 -*-
"""用已训练模型对全数据集逐类评估: 正确率 + 混淆去向(训练集自评, 偏乐观;
留出集太小只能当参考)。用途: 定位需要补采数据的弱类。"""
import os
import sys
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = r'E:\learn\auto_cheese\samples\dataset'
CKPT = r'E:\learn\auto_cheese\tools\out\charcnn_best.pt'
IMG = 32

ck = torch.load(CKPT, map_location='cpu', weights_only=False)
CLASSES = ck['classes']
MEAN, STDV = float(ck['mean']), float(ck['std'])

class Net(nn.Module):
    def __init__(self, n):
        super().__init__()
        self.c1 = nn.Conv2d(1, 16, 3, padding=1)
        self.c2 = nn.Conv2d(16, 24, 3, padding=1)
        self.c3 = nn.Conv2d(24, 24, 3, padding=1)
        self.fc = nn.Linear(24, n)
    def forward(self, x):
        x = F.max_pool2d(F.relu(self.c1(x)), 2)
        x = F.max_pool2d(F.relu(self.c2(x)), 2)
        x = F.relu(self.c3(x))
        return self.fc(F.adaptive_avg_pool2d(x, 1).flatten(1))

model = Net(len(CLASSES))
model.load_state_dict(ck['state'])
model.eval()

conf = np.zeros((len(CLASSES), len(CLASSES)), dtype=int)
xs, ys, names = [], [], []
for ci, c in enumerate(CLASSES):
    d = os.path.join(ROOT, c)
    for fn in sorted(os.listdir(d)):
        im = Image.open(os.path.join(d, fn)).convert('L')
        a = np.asarray(im.resize((IMG, IMG), Image.BILINEAR), dtype=np.float32)
        t = torch.from_numpy(((a - MEAN) / STDV).astype(np.float32)).unsqueeze(0)
        xs.append(t); ys.append(ci); names.append(fn)
xs = torch.stack(xs)
ys = torch.tensor(ys)
with torch.no_grad():
    pred = model(xs).argmax(1)
for y, p in zip(ys.tolist(), pred.tolist()):
    conf[y, p] += 1

print(f"{'class':12s} {'n':>4s} {'acc':>6s}  主要误判去向")
rows = []
for ci, c in enumerate(CLASSES):
    n = int(conf[ci].sum())
    acc = conf[ci, ci] / n if n else float('nan')
    wrong = [(conf[ci, pi], CLASSES[pi]) for pi in range(len(CLASSES))
             if pi != ci and conf[ci, pi] > 0]
    wrong.sort(reverse=True)
    rows.append((acc, n, c, wrong))
for acc, n, c, wrong in sorted(rows):
    top = ', '.join(f"{w[1]}×{w[0]}" for w in wrong[:3]) or '-'
    flag = ' ⚠' if acc < 0.95 else ''
    print(f"{c:12s} {n:4d} {acc:6.3f}  {top}{flag}")
print(f"\n总体 {conf.trace()}/{conf.sum()} = {conf.trace()/conf.sum():.4f} (无增强训练集自评)")
