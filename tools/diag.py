# -*- coding: utf-8 -*-
"""诊断2：打印若干点的灰度图 ASCII（16x16，亮度 0-9）"""
import sys
sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import load, bilinear_grid, refine_grid, CROP
import numpy as np
from PIL import Image

img, arr, gray = load()
corners = [(180, 595), (900, 595), (900, 1395), (180, 1395)]
pts = bilinear_grid(*corners)
pts, _, _ = refine_grid(gray, pts)
radius = 88.3 * 0.48

def show(r, c, label):
    cx, cy = pts[r, c]
    x0 = int(np.clip(cx - radius, 0, arr.shape[1] - 1))
    y0 = int(np.clip(cy - radius, 0, arr.shape[0] - 1))
    x1 = int(np.clip(cx + radius, 0, arr.shape[1] - 1))
    y1 = int(np.clip(cy + radius, 0, arr.shape[0] - 1))
    win = arr[y0:y1, x0:x1]
    sub = np.asarray(
        Image.fromarray(win.astype(np.uint8)).resize((32, 32), Image.BOX)
    ).astype(np.float32)
    g = 0.299 * sub[..., 0] + 0.587 * sub[..., 1] + 0.114 * sub[..., 2]
    print(f"--- {label} ({r},{c}) min={g.min():.0f} max={g.max():.0f} mean={g.mean():.0f}")
    lo, hi = g.min(), g.max()
    for row in range(0, 32, 2):
        line = ''.join(str(int((g[row, col] - lo) / max(1, hi - lo) * 9))
                       for col in range(32))
        print('   ' + line)

for r, c, lab in [(9, 0, '俥'), (9, 4, '帥'), (3, 0, '卒'), (8, 4, '空河'),
                  (4, 4, '空'), (1, 4, '空上')]:
    show(r, c, lab)
