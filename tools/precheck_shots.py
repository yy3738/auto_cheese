# -*- coding: utf-8 -*-
"""shot13~20 批量预标质控: 用 CNN 对每个裁片分类, 与 NCC 粗标对比,
只把两者不一致(或 CNN 低置信)的格子拼成一张校对大图。
用户只需校对这张图上的格子, 回复格式: shotNN,r行,c列=真子(中文类名)"""
import os
import numpy as np
from PIL import Image, ImageDraw
import torch
import torch.nn as nn
import torch.nn.functional as F

DST = r'E:\learn\auto_cheese\samples\shot13~20'
CKPT = r'E:\learn\auto_cheese\tools\out\charcnn_best.pt'
IMG = 32
SHOTS = [f'shot{i}' for i in range(13, 21)]

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

# NCC 粗标来自 labels.tsv
ncc = {}
for s in SHOTS:
    d = os.path.join(r'E:\learn\auto_cheese\samples', s)
    with open(os.path.join(d, 'labels.tsv'), encoding='utf-8') as fp:
        next(fp)
        for line in fp:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 2:
                ncc[(s, p[0].replace('.png', ''))] = (p[1].rstrip('?'), float(p[2]))

cnn = {}
xs = []
keys = []
for s in SHOTS:
    d = os.path.join(r'E:\learn\auto_cheese\samples', s)
    for r in range(10):
        for c in range(9):
            fn = f'r{r}c{c}.png'
            im = Image.open(os.path.join(d, fn)).convert('L').resize((IMG, IMG), Image.BILINEAR)
            a = np.asarray(im, dtype=np.float32)
            xs.append((a - MEAN) / STDV)
            keys.append((s, r, c))
xs = torch.from_numpy(np.stack(xs)[:, None].astype(np.float32))
with torch.no_grad():
    pred = model(xs).argmax(1).numpy()
for (s, r, c), pi in zip(keys, pred.tolist()):
    cnn[(s, r, c)] = CLASSES[pi]

# 分歧格: CNN 与 NCC 判类不同(把 "?" 后缀去掉后比)
diffs = []
for (s, r, c), cn in cnn.items():
    nname, nd = ncc.get((s, f'r{r}c{c}'), ('', 1.0))
    if cn != nname or nd > 0.55:
        diffs.append((s, r, c, nname, cn, nd))
print(f"总格 {len(keys)}  分歧格 {len(diffs)}")

THUMB = 72
COLS = 10
rows = (len(diffs) + COLS - 1) // COLS
mont = Image.new('RGB', (THUMB * COLS + 20, THUMB * rows + 20), (245, 241, 232))
draw = ImageDraw.Draw(mont)
for i, (s, r, c, nname, cn, nd) in enumerate(diffs):
    d = os.path.join(r'E:\learn\auto_cheese\samples', s, f'r{r}c{c}.png')
    thumb = Image.open(d).convert('RGB').resize((THUMB, THUMB))
    x = (i % COLS) * THUMB + 10
    y = (i // COLS) * THUMB + 10
    mont.paste(thumb, (x, y))
    draw.rectangle([x, y, x + THUMB - 1, y + THUMB - 1], outline=(180, 60, 60))
    draw.text((x + 2, y + 2), f"{s[4:]},{r},{c}", fill=(200, 30, 30))
    draw.text((x + 2, y + THUMB - 22), f"ncc:{nname[:8]}", fill=(20, 20, 120))
    draw.text((x + 2, y + THUMB - 12), f"cnn:{cn[:8]}", fill=(20, 100, 20))
out = os.path.join(DST, 'check_montage.png')
mont.save(out)
print(f"校对图: {out}")
print("逐格确认: 图片上红字是 shot,行,列; 蓝字 NCC 粗标, 绿字 CNN 判类。")
print("错误的格子回复: shotNN,行,列=真子 (如 15,4,2=黑马)")
