# -*- coding: utf-8 -*-
"""dump 帧全盘判定 + 多帧交叉: 找出高置信幻觉格"""
import sys
import os
import glob
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F
from proto_recognize import bilinear_grid, refine_grid

ck = torch.load(r'E:\learn\auto_cheese\tools\out\charcnn_best.pt', map_location='cpu', weights_only=False)
CLASSES, MEAN, STDV = ck['classes'], float(ck['mean']), float(ck['std'])


class Net(nn.Module):
    def __init__(s, n):
        super().__init__()
        s.c1 = nn.Conv2d(1, 16, 3, padding=1)
        s.c2 = nn.Conv2d(16, 24, 3, padding=1)
        s.c3 = nn.Conv2d(24, 24, 3, padding=1)
        s.fc = nn.Linear(24, n)

    def forward(s, x):
        x = F.max_pool2d(F.relu(s.c1(x)), 2)
        x = F.max_pool2d(F.relu(s.c2(x)), 2)
        x = F.relu(s.c3(x))
        return s.fc(F.adaptive_avg_pool2d(x, 1).flatten(1))


model = Net(len(CLASSES))
model.load_state_dict(ck['state'])
model.eval()


def gray32_area(crop):
    arr = np.asarray(crop.convert('L'), np.float32)
    h, w = arr.shape
    out = np.zeros((32, 32), np.float32)
    for oy in range(32):
        y0, y1 = oy*h//32, max((oy+1)*h//32, oy*h//32+1)
        for ox in range(32):
            x0, x1 = ox*w//32, max((ox+1)*w//32, ox*w//32+1)
            out[oy, ox] = arr[y0:y1, x0:x1].mean()
    return out


# 多帧交叉: 每格的判定序列
from collections import defaultdict
cell_preds = defaultdict(list)
frames = sorted(glob.glob(r'E:\learn\auto_cheese\dumps_now\dmp\mp_*.png'))
loaded = 0
for p in frames:
    try:
        img = Image.open(p)
        img.load()
        img = img.convert('RGB')
    except Exception:
        continue
    loaded += 1
    a = np.asarray(img).astype(np.float32)
    gray = 0.299*a[..., 0] + 0.587*a[..., 1] + 0.114*a[..., 2]
    corners = [(x, y) for x, y in [(70, 455), (1010, 455), (1010, 1455), (70, 1455)]]
    pts = refine_grid(gray, bilinear_grid(*corners))
    cell = 0.0
    n = 0
    for r in range(10):
        for c in range(8):
            cell += float(np.hypot(*(pts[r, c+1] - pts[r, c])))
            n += 1
    for r in range(9):
        for c in range(9):
            cell += float(np.hypot(*(pts[r+1, c] - pts[r, c])))
            n += 1
    cell /= n
    half = int(cell * 0.45)
    for r in range(10):
        for c in range(9):
            cx, cy = pts[r, c, 0], pts[r, c, 1]
            g = gray32_area(img.crop((int(cx-half), int(cy-half), int(cx+half), int(cy+half))))
            x = torch.from_numpy(((g - MEAN) / STDV)[None, None])
            with torch.no_grad():
                lg = model(x)[0]
            t = torch.topk(lg, 2)
            m = torch.sigmoid(t.values[0] - t.values[1]).item()
            name = CLASSES[t.indices[0].item()]
            cell_preds[(r, c)].append((name, m))
print(f'可用帧: {loaded}/{len(frames)}')
print()
print('各格判定(同一格多帧间不一致 = 幻觉/不稳):')
for r in range(10):
    row = []
    for c in range(9):
        ps = cell_preds.get((r, c), [])
        if not ps:
            row.append('    -    ')
            continue
        conf = [(n_, m_) for n_, m_ in ps if m_ >= 0.9]
        if not conf:
            row.append('    ?    ')
            continue
        names = set(n_ for n_, _ in conf)
        if len(names) > 1:
            row.append(f'  不稳{sorted(names)[:2]}'.ljust(12))
        else:
            row.append(f'  {list(names)[0][:8]}'.ljust(12))
    print(f'r{r}: ' + ' '.join(row))
