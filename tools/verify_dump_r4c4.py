# -*- coding: utf-8 -*-
"""新模型对 dump r4c4 裁片的判定验证"""
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, r'E:\learn\auto_cheese\tools')
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

for p in sorted(glob.glob(r'E:\learn\auto_cheese\dumps_1018b\dmp\mp_*.png')):
    try:
        img = Image.open(p)
        img.load()
        img = img.convert('RGB')
    except Exception:
        print('跳过损坏:', os.path.basename(p))
        continue
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
    cx, cy = pts[4, 4, 0], pts[4, 4, 1]
    crop = img.crop((int(cx - half), int(cy - half), int(cx + half), int(cy + half)))
    im = crop.convert('L').resize((32, 32), Image.BILINEAR)
    x = torch.from_numpy(((np.asarray(im, np.float32) - MEAN) / STDV)[None, None])
    with torch.no_grad():
        lg = model(x)[0]
    t = torch.topk(lg, 2)
    m = torch.sigmoid(t.values[0] - t.values[1]).item()
    name = CLASSES[t.indices[0].item()]
    tag = 'OK' if (name == 'EMPTY' and m >= 0.9) else ('拒判' if m < 0.9 else '幻觉')
    print(f'{os.path.basename(p)}: {name} m={m:.2f} [{tag}]')
