# -*- coding: utf-8 -*-
"""全盘 90 格普查:每格 rawstd/红占比/最近模板——定位"某格判空/误读"的
像素级根因(如红帅被将军特效黑圈罩住判空)。用法: python tools/diag_grid.py
"""
import io, json, base64
import numpy as np
from PIL import Image

import sys
sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import (bilinear_grid, refine_grid, to_gray, norm24,
                             window24, ncc_dist, redness)

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

td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
tmpls = []
for t in td['templates']:
    im = Image.open(io.BytesIO(base64.b64decode(t['png']))).convert('RGB')
    a24 = np.asarray(im).astype(np.float32)
    g = to_gray(a24)
    q, sd = norm24(g)
    tmpls.append((t['piece'], q, ncc_dist, redness(a24, g)))

SD_MIN = 32
print(f"cell={cell:.1f}  判空阈值 SD_MIN={SD_MIN}")
print("格(i,j) | rawstd red% | 判定(空/红/黑) | 最近3模板 d")
for r in range(10):
    cells = []
    for c in range(9):
        a24 = np.asarray(window24(img, pts[r, c, 0], pts[r, c, 1], radius))
        g = to_gray(a24)
        std = float(a24.std())
        red = redness(a24, g)
        verdict = '空' if std < SD_MIN else ('红?' if red > 40 else '黑?')
        qn, _ = norm24(g)
        dists = sorted(((float(ncc_dist(qn, qt)), nm) for nm, qt, _, _ in tmpls))[:3]
        dstr = ' '.join(f"{nm}:{d:.2f}" for d, nm in dists)
        if std >= SD_MIN or (r, c) == (9, 4):
            cells.append(f"({r},{c}) std={std:5.1f} red={red:5.1f} {verdict:3s} [{dstr}]")
    for line in cells:
        print(' ', line)
EOF = None
