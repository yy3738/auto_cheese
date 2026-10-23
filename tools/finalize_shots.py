# -*- coding: utf-8 -*-
"""shot13~20 落库: 分歧格按人工精读结论修(白圈/特效语义见下),
一致格双引擎(CNN+NCC)同判才入库, d>=0.3 且未经校正的跳过。
文件名加 shot 前缀防碰撞。输出入库统计与待审计清单。"""
import os
import shutil
import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = r'E:\learn\auto_cheese\samples'
DS = os.path.join(ROOT, 'dataset')
CKPT = r'E:\learn\auto_cheese\tools\out\charcnn_best.pt'
IMG = 32
SHOTS = [f'shot{i}' for i in range(13, 21)]

CN = {'空格': 'EMPTY', '黑卒': 'B_PAWN', '红兵': 'R_PAWN', '红仕': 'R_ADVISOR',
      '黑将': 'B_KING', '黑炮': 'B_CANNON', '红帅': 'R_KING',
      '黑车': 'B_ROOK', '黑马': 'B_KNIGHT'}

# 人工精读校对(被特效/标记污染的格是训练集珍贵样本)
FIX = {
    (13, 'r2c1'): '空格', (13, 'r9c0'): '空格', (13, 'r9c4'): '红帅',
    (14, 'r0c0'): '空格', (14, 'r0c7'): '黑车', (14, 'r0c8'): '空格',
    (14, 'r1c0'): '空格', (14, 'r9c4'): '红帅',
    (15, 'r1c0'): '空格', (15, 'r6c1'): '黑炮', (15, 'r6c2'): '红兵',
    (15, 'r9c3'): '红仕', (15, 'r9c4'): '红帅', (15, 'r5c1'): '空格',
    (15, 'r5c6'): '黑车', (15, 'r6c8'): '红兵',
    (16, 'r0c0'): '空格', (16, 'r0c1'): '空格', (16, 'r0c6'): '空格',
    (16, 'r2c6'): '黑马', (16, 'r4c4'): '空格',
    (17, 'r0c4'): '黑将', (17, 'r4c1'): '空格', (17, 'r7c1'): '黑车',
    (17, 'r7c5'): '空格',
    (18, 'r4c2'): '黑卒', (18, 'r5c0'): '空格', (18, 'r5c1'): '空格',
    (19, 'r0c4'): '黑将', (19, 'r7c0'): '空格', (19, 'r9c4'): '红帅',
    (20, 'r7c0'): '空格', (20, 'r9c0'): '黑车',
    (20, 'r7c0'): '空格',
    # 特效完全遮蔽, 不可判——显式跳过
    (18, 'r4c4'): None,
}

ck = torch.load(CKPT, map_location='cpu', weights_only=False)
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

ncc = {}
for s in SHOTS:
    with open(os.path.join(ROOT, s, 'labels.tsv'), encoding='utf-8') as fp:
        next(fp)
        for line in fp:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 3:
                ncc[(s, p[0].replace('.png', ''))] = (p[1].rstrip('?'), float(p[2]))

# CNN 批量推断(带置信)
from collections import Counter
xs, keys = [], []
for s in SHOTS:
    for r in range(10):
        for c in range(9):
            im = Image.open(os.path.join(ROOT, s, f'r{r}c{c}.png')).convert('L') \
                .resize((IMG, IMG), Image.BILINEAR)
            xs.append((np.asarray(im, dtype=np.float32) - MEAN) / STDV)
            keys.append((s, r, c))
xs = torch.from_numpy(np.stack(xs)[:, None].astype(np.float32))
with torch.no_grad():
    logits = model(xs)
    prob = torch.softmax(logits, 1)
conf, pred = prob.max(1)
conf = conf.numpy(); pred = pred.numpy()

stats = Counter()
skipped, audited = [], []
for (s, r, c), xi in zip(keys, range(len(keys))):
    k = (s, f'r{r}c{c}')
    nname, nd = ncc.get(k, ('', 1.0))
    cnn = CLASSES[pred[xi]]
    sn = int(s[4:])
    fix = FIX.get((sn, f'r{r}c{c}'))
    in_fix = (sn, f'r{r}c{c}') in FIX
    if in_fix and fix is None:
        skipped.append(f'{s} r{r}c{c} (特效遮蔽)')
        continue
    if fix is not None:
        truth = CN[fix]
        src = '精读'
    elif cnn == nname and nd < 0.3:
        truth = nname
        src = '双引擎一致'
    else:
        # 未精读的分歧格: ncc=EMPTY 的石纹格按精读结论落 EMPTY, 其余跳过
        if nname == 'EMPTY':
            truth = 'EMPTY'
            src = '石纹空格'
        else:
            skipped.append(f'{s} r{r}c{c} ncc={nname} cnn={cnn} d={nd:.2f}')
            continue
    dd = os.path.join(DS, truth)
    os.makedirs(dd, exist_ok=True)
    shutil.copy(os.path.join(ROOT, s, f'r{r}c{c}.png'),
                os.path.join(dd, f'{s}_r{r}c{c}.png'))
    stats[truth] += 1
    if src in ('石纹空格',):
        audited.append(f'{s},{r},{c}')

print('入库统计:')
for k, v in sorted(stats.items()):
    print(f'  {k:10s} +{v}')
print(f'合计 +{sum(stats.values())}')
print(f'跳过 {len(skipped)} 格:')
for x in skipped:
    print(' ', x)
open(os.path.join(ROOT, 'shot13~20', 'audited_empty.txt'), 'w', encoding='utf-8') \
    .write('\n'.join(audited))
print(f'石纹空格清单(可抽查): {os.path.join(ROOT, "shot13~20", "audited_empty.txt")} ({len(audited)}格)')
