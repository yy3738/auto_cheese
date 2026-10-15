# -*- coding: utf-8 -*-
"""定点诊断:计算某格窗口与全部模板的 NCC 距离排序 + 红像素占比,
定位"红帅被读成黑炮"这类异色误读的根因(用户:提高识别率,别绕)。
用法: python tools/diag_cell.py [row col]  (默认 9 4 = 红帅位)
"""
import sys, io, json, base64
import numpy as np
from PIL import Image

sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import (bilinear_grid, refine_grid, to_gray, norm24,
                             window24, ncc_dist, redness)

SCREEN = r"E:\learn\auto_cheese\.scratch_debugframe.png"
row, col = (int(sys.argv[1]), int(sys.argv[2])) if len(sys.argv) > 2 else (9, 4)

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

a24 = np.asarray(window24(img, pts[row, col, 0], pts[row, col, 1], radius))
g = to_gray(a24)
qn, sd = norm24(g)

td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
r_red = redness(a24, g)
print(f"cell=({row},{col}) 中心=({pts[row,col,0]:.0f},{pts[row,col,1]:.0f})")
print(f"窗口 rawstd={float(a24.std()):.1f} redness={r_red:.1f} (REDNESS_GATE=40→>40 判红)")

tmpls = []
for t in td['templates']:
    im = Image.open(io.BytesIO(base64.b64decode(t['png']))).convert('RGB')
    a24t = np.asarray(im).astype(np.float32)
    gt = to_gray(a24t)
    qt, sdt = norm24(gt)
    tmpls.append((t['piece'], qt, sdt, redness(a24t, gt)))

dists = sorted(((ncc_dist(qn, qt), name, red) for name, qt, sdt, red in tmpls))[:8]
print("NCC 最近模板 TOP8:")
for d, name, red in dists:
    print(f"  {name:8s} d={d:.3f}  模板redness={red:.0f}")
