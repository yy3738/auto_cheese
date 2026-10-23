# -*- coding: utf-8 -*-
"""复盘开局截图(完美标签): 90格 CNN/NCC 全量对照 + 空格样本收割
初始局面 ground truth: 红上黑下, row0=红底线"""
import os
import re
import sys
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import bilinear_grid, refine_grid, to_gray, norm24, window24, ncc_dist

IMG_PATH = r'E:\learn\auto_cheese\_jj8.png'
OUT = r'E:\learn\auto_cheese\samples\replay_start'

R = ['R_ROOK', 'R_KNIGHT', 'R_BISHOP', 'R_ADVISOR', 'R_KING', 'R_ADVISOR', 'R_BISHOP', 'R_KNIGHT', 'R_ROOK']
TRUTH = [R, ['EMPTY']*9,
         ['EMPTY', 'R_CANNON', 'EMPTY', 'EMPTY', 'EMPTY', 'EMPTY', 'EMPTY', 'R_CANNON', 'EMPTY'],
         ['R_PAWN', 'EMPTY', 'R_PAWN', 'EMPTY', 'R_PAWN', 'EMPTY', 'R_PAWN', 'EMPTY', 'R_PAWN'],
         ['EMPTY']*9, ['EMPTY']*9,
         ['B_PAWN', 'EMPTY', 'B_PAWN', 'EMPTY', 'B_PAWN', 'EMPTY', 'B_PAWN', 'EMPTY', 'B_PAWN'],
         ['EMPTY', 'B_CANNON', 'EMPTY', 'EMPTY', 'EMPTY', 'EMPTY', 'EMPTY', 'B_CANNON', 'EMPTY'],
         ['EMPTY']*9,
         ['B_ROOK', 'B_KNIGHT', 'B_BISHOP', 'B_ADVISOR', 'B_KING', 'B_ADVISOR', 'B_BISHOP', 'B_KNIGHT', 'B_ROOK']]

ck = torch.load(r'E:\learn\auto_cheese\tools\out\charcnn_best.pt', map_location='xs' in dir() and 'cpu' or 'cpu', weights_only=False)
CLASSES, MEAN, STDV = ck['classes'], float(ck['mean']), float(ck['std'])


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

img = Image.open(IMG_PATH).convert('RGB')
sc = img.width / 1080.0
a = np.asarray(img).astype(np.float32)
gray = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
corners = [(int(x * sc), int(y * sc)) for x, y in [(70, 455), (1010, 455), (1010, 1455), (70, 1455)]]
pts = refine_grid(gray, bilinear_grid(*corners))
cell = 0.0
n = 0
for r in range(10):
    for c in range(8):
        cell += float(np.hypot(*(pts[r, c + 1] - pts[r, c])))
        n += 1
for r in range(9):
    for c in range(9):
        cell += float(np.hypot(*(pts[r + 1, c] - pts[r, c])))
        n += 1
cell /= n

os.makedirs(OUT, exist_ok=True)
half = int(cell * 0.45)

xs, keys = [], []
cnn_ok = 0
cnn_err = []
rows_txt = []
for r in range(10):
    line_cnn = []
    for c in range(9):
        cx, cy = pts[r, c, 0], pts[r, c, 1]
        box = img.crop((int(cx - half), int(cy - half), int(cx + half), int(cy + half)))
        box.save(os.path.join(OUT, f'r{r}c{c}.png'))
        im32 = box.convert('L').resize((32, 32), Image.BILINEAR)
        xs.append((np.asarray(im32, np.float32) - MEAN) / STDV)
        keys.append((r, c))
        xs[-1]  # keep
xs_t = torch.from_numpy(np.stack(xs)[:, None].astype(np.float32))
with torch.no_grad():
    logits = model(xs_t)
    prob = torch.softmax(logits, 1)
conf, pred = prob.max(1)
conf = conf.numpy()
pred = pred.numpy()

from collections import Counter
err_cells = []
for i, (r, c) in enumerate(keys):
    truth = TRUTH[r][c]
    got = CLASSES[pred[i]]
    if got != truth:
        err_cells.append((r, c, truth, got, conf[i]))
    else:
        cnn_ok += 1

# NCC 对照
import json
import io
import base64
td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
tmpls = []
for t in td['templates']:
    im = Image.open(io.BytesIO(base64.b64decode(t['png']))).convert('RGB')
    gt = to_gray(np.asarray(im).astype(np.float32))
    qt, sdt = norm24(gt)
    tmpls.append((t['piece'], qt, sdt))
ncc_ok = 0
ncc_err = []
for r in range(10):
    for c in range(9):
        cx, cy = pts[r, c, 0], pts[r, c, 1]
        a24 = np.asarray(window24(img, cx, cy, cell * 0.48))
        g = to_gray(a24)
        qn, sd = norm24(g)
        if sd < 32:
            got = 'EMPTY'
        else:
            dists = sorted(((float(ncc_dist(qn, qt)), nm) for nm, qt, _ in tmpls))
            got = dists[0][1] if dists[0][0] <= 0.75 else 'UNKNOWN'
        truth = TRUTH[r][c]
        if got == truth:
            ncc_ok += 1
        else:
            ncc_err.append((r, c, truth, got))

print(f'=== 复盘开局 90 格(完美标签) ===')
print(f'CNN: {cnn_ok}/90 正确 ({cnn_ok/0.9:.1f}%)')
if err_cells:
    print('CNN 错误格:')
    for r, c, t, g, cf in err_cells:
        print(f'  r{r}c{c} 真={t} 判={g} conf={cf:.2f}')
print(f'NCC: {ncc_ok}/90 正确 ({ncc_ok/0.9:.1f}%)')
if ncc_err:
    print('NCC 错误格:')
    for r, c, t, g in ncc_err[:15]:
        print(f'  r{r}c{c} 真={t} 判={g}')

# 空格样本收割: 58 个 EMPTY 全部入库(这正是中线石纹热点缺的)
cnt = Counter()
for (r, c) in keys:
    cnt[TRUTH[r][c]] += 1
print(f'\n可收割样本: {dict(cnt)}')
print(f'裁片已存 {OUT} (58 EMPTY + 32 棋子, 全部带完美标签)')
