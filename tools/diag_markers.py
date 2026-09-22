# -*- coding: utf-8 -*-
"""诊断3：用实战帧（带选中光圈/标记）跑同一识别流程，复现未定点"""
import json
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import (CHAR2NAME, MASK, SHIFT, T, bilinear_grid,
                             ncc_dist, norm24, redness, refine_grid, to_gray,
                             truth_board, window24)

SCREEN = r"E:\learn\auto_cheese\.scratch_board3.png"

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

# 载入模板
td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
tmpls = []
for t in td['templates']:
    im = Image.open(__import__('io').BytesIO(
        __import__('base64').b64decode(t['png']))).convert('RGB')
    a24 = np.asarray(im).astype(np.float32)
    g = to_gray(a24)
    q, sd = norm24(g)
    tmpls.append((t['piece'], q, sd, redness(a24, g)))

truth = truth_board()
ok = 0; errors = []
for r in range(10):
    for c in range(9):
        idx = r * 9 + c
        a24 = np.asarray(window24(img, pts[r, c, 0], pts[r, c, 1], radius))
        g = to_gray(a24)
        q, sd = norm24(g)
        rd = redness(a24, g)
        name = truth[r][c]
        if sd < 32:
            got = 'EMPTY'
        else:
            best, bd = 'EMPTY', 1e9
            for n2, q2, sd2, rd2 in tmpls:
                if rd > 40 and not n2.startswith('R_'):
                    continue
                if rd <= 40 and n2.startswith('R_'):
                    continue
                d = ncc_dist(q, q2, rd, rd2)
                if d < bd:
                    bd, best = d, n2
            got = best
        exp = name or 'EMPTY'
        exp_name = CHAR2NAME.get(exp, exp) if exp != 'EMPTY' else 'EMPTY'
        if got == exp_name:
            ok += 1
        else:
            errors.append((r, c, exp_name, got, f"d={bd:.3f} sd={sd:.1f} rd={rd:.1f}"))
print(f"实战帧: {ok}/90")
for r, c, exp, got, msg in errors:
    print(f"  ({r},{c}) 真{exp} → {got}  {msg}")
