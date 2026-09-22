# -*- coding: utf-8 -*-
"""诊断4：验证 captureFresh 修复后的 App 抓帧，全 90 点识别结果"""
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import (CHAR2NAME, bilinear_grid, ncc_dist, norm24,
                             refine_grid, to_gray, window24)

SCREEN = r"E:\learn\auto_cheese\.scratch_debugframe.png"

img = Image.open(SCREEN).convert('RGB')
a = np.asarray(img).astype(np.float32)
gray = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
corners = [(70, 455), (1010, 455), (1010, 1455), (70, 1455)]
pts = refine_grid(gray, bilinear_grid(*corners))
cell = 0.0; n = 0
for r in range(10):
    for c in range(8):
        cell += float(np.hypot(*(pts[r, c + 1] - pts[r, c]))); n += 1
for r in range(9):
    for c in range(9):
        cell += float(np.hypot(*(pts[r + 1, c] - pts[r, c]))); n += 1
cell /= n
radius = cell * 0.48
print(f"cell={cell:.1f} radius={radius:.1f}")

td = __import__('json').load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
tmpls = []
for t in td['templates']:
    im = Image.open(__import__('io').BytesIO(
        __import__('base64').b64decode(t['png']))).convert('RGB')
    a24 = np.asarray(im).astype(np.float32)
    g = to_gray(a24)
    q, sd = norm24(g)
    tmpls.append((t['piece'], q, sd, __import__('proto_recognize').redness(a24, g)))

results = []
for r in range(10):
    row = []
    for c in range(9):
        a24 = np.asarray(window24(img, pts[r, c, 0], pts[r, c, 1], radius))
        g = to_gray(a24)
        q, sd = norm24(g)
        rd = __import__('proto_recognize').redness(a24, g)
        if sd < 32:
            row.append('.')
            continue
        best, bd = '?', 1e9
        for n2, q2, sd2, rd2 in tmpls:
            if rd > 40 and not n2.startswith('R_'):
                continue
            if rd <= 40 and n2.startswith('R_'):
                continue
            d = ncc_dist(q, q2, rd, rd2)
            if d < bd:
                bd, best = d, n2
        row.append(best.replace('B_', 'b').replace('R_', 'r') + ('' if bd < 0.75 else '?'))
    results.append(' '.join(f"{p:>3}" for p in row))
print('\n'.join(results))
