# -*- coding: utf-8 -*-
"""jj7 截图: NCC vs CNN 全盘对比"""
import os, sys
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import bilinear_grid, refine_grid, to_gray, norm24, window24, ncc_dist, redness

IMG = r'E:\learn\auto_cheese\_jj7.png'
import json, io, base64
td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
tmpls = []
for t in td['templates']:
    im = Image.open(io.BytesIO(base64.b64decode(t['png']))).convert('RGB')
    a24 = np.asarray(im).astype(np.float32)
    gt = to_gray(a24)
    qt, sdt = norm24(gt)
    tmpls.append((t['piece'], qt, sdt))

img = Image.open(IMG).convert('RGB')
sc = img.width / 1080.0
a = np.asarray(img).astype(np.float32)
gray = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
corners = [(int(x * sc), int(y * sc)) for x, y in [(70, 455), (1010, 455), (1010, 1455), (70, 1455)]]
pts = refine_grid(gray, bilinear_grid(*corners))

cell = 0.0; n = 0
for r in range(10):
    for c in range(8):
        cell += float(np.hypot(*(pts[r, c + 1] - pts[r, c]))); n += 1
for r in range(9):
    for c in range(9):
        cell += float(np.hypot(*(pts[r + 1, c] - pts[r, c]))); n += 1
cell /= n

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

HALF = int(cell * 0.45)
print(f'cell={cell:.1f} half={HALF}')
print()
for r in range(10):
    row_ncc = []
    row_cnn = []
    for c in range(9):
        cx, cy = pts[r, c, 0], pts[r, c, 1]
        # NCC 管线
        a24 = np.asarray(window24(img, cx, cy, cell * 0.48))
        g = to_gray(a24)
        qn, sd = norm24(g)
        dists = sorted(((float(ncc_dist(qn, qt)), nm) for nm, qt, _ in tmpls))
        d0, name0 = dists[0]
        # 复刻 App 的 std 判空门
        if sd < 32:
            ncc_out = 'EMPTY'
        else:
            ncc_out = name0 if d0 <= 0.75 else 'UNKNOWN'
        row_ncc.append(ncc_out)
        # CNN 管线
        half = int(cell * 0.45)
        box = img.crop((int(cx - half), int(cy - half), int(cx + half), int(cy + half)))
        im32 = box.convert('L').resize((32, 32), Image.BILINEAR)
        x = torch.from_numpy(((np.asarray(im32, np.float32) - MEAN) / STDV)[None, None])
        with torch.no_grad():
            lg = model(x)[0]
        top2 = torch.topk(lg, 2)
        margin = torch.sigmoid(top2.values[0] - top2.values[1]).item()
        row_cnn.append(f'{CLASSES[top2.indices[0].item()]}({margin:.2f})')
    print(f'r{r} NCC: ' + ' '.join(f'{x:9s}' for x in row_ncc))
    print(f'r{r} CNN: ' + ' '.join(f'{x:14s}' for x in row_cnn))
    print()
