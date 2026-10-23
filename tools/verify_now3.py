# -*- coding: utf-8 -*-
"""_now3 验证: 残影格 EMPTY 置信 + 全盘 unknown 计数"""
import os
import sys
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import bilinear_grid, refine_grid

img = Image.open(r'E:\learn\auto_cheese\_now3.png').convert('RGB')
a = np.asarray(img).astype(np.float32)
gray = 0.299*a[...,0]+0.587*a[...,1]+0.114*a[...,2]
corners = [(x, y) for x, y in [(70,455),(1010,455),(1010,1455),(70,1455)]]
pts = refine_grid(gray, bilinear_grid(*corners))
cell = 0.0
n = 0
for r in range(10):
    for c in range(8):
        cell += float(np.hypot(*(pts[r, c+1]-pts[r, c])))
        n += 1
for r in range(9):
    for c in range(9):
        cell += float(np.hypot(*(pts[r+1, c]-pts[r, c])))
        n += 1
cell /= n
half = int(cell*0.45)
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


for r, c in [(9, 7), (9, 1), (7, 6), (2, 2), (7, 2)]:
    cx, cy = pts[r, c, 0], pts[r, c, 1]
    g = gray32_area(img.crop((int(cx-half), int(cy-half), int(cx+half), int(cy+half))))
    x = torch.from_numpy(((g-MEAN)/STDV)[None, None])
    with torch.no_grad():
        lg = model(x)[0]
    t = torch.topk(lg, 2)
    m = torch.sigmoid(t.values[0]-t.values[1]).item()
    print(f'r{r}c{c}: {CLASSES[t.indices[0].item()]} m={m:.2f}')

n_unk = 0
for r in range(10):
    for c in range(9):
        cx, cy = pts[r, c, 0], pts[r, c, 1]
        g = gray32_area(img.crop((int(cx-half), int(cy-half), int(cx+half), int(cy+half))))
        x = torch.from_numpy(((g-MEAN)/STDV)[None, None])
        with torch.no_grad():
            lg = model(x)[0]
        t = torch.topk(lg, 2)
        if torch.sigmoid(t.values[0]-t.values[1]).item() < 0.9:
            n_unk += 1
print(f'unknown: {n_unk}/90')
