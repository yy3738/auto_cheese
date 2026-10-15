# -*- coding: utf-8 -*-
"""训练样本采集工具:
用法: python tools/crop_cells.py <截图路径> [tag]
功能:
  1. 按校准网格把截图裁成 90 个格责图片 → samples/<tag>/r{行}c{列}.png
  2. NCC 自动粗标(仅参考,错误正是我们要收集的样本)
  3. 生成校对大图 samples/<tag>/montage.png
     —— 每格缩略图上标"格名:粗配类?疑_",你只需回复标错的格子
"""
import sys, os, io, json, base64
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, r'E:\learn\auto_cheese\tools')
from proto_recognize import (bilinear_grid, refine_grid, to_gray, norm24,
                             window24, ncc_dist, redness)

SCREEN = sys.argv[1] if len(sys.argv) > 1 else r"E:\learn\auto_cheese\.scratch_debugframe.png"
TAG = sys.argv[2] if len(sys.argv) > 2 else "s1"
ROOT = r'E:\learn\auto_cheese\samples'

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
half = int(cell * 0.45)

# 模板加载(NCC 粗标用)
td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
tmpls = []
for t in td['templates']:
    im = Image.open(io.BytesIO(base64.b64decode(t['png']))).convert('RGB')
    a24 = np.asarray(im).astype(np.float32)
    gt = to_gray(a24)
    qt, sdt = norm24(gt)
    tmpls.append((t['piece'], qt, sdt))

outdir = os.path.join(ROOT, TAG)
os.makedirs(outdir, exist_ok=True)

THUMB = 88
montage = Image.new('RGB', (THUMB * 9 + 40, THUMB * 10 + 60), (245, 241, 232))
draw = ImageDraw.Draw(montage)

labels_tsv = []
saved = 0
for r in range(10):
    for c in range(9):
        cx, cy = pts[r, c, 0], pts[r, c, 1]
        box = img.crop((int(cx - half), int(cy - half), int(cx + half), int(cy + half)))
        fn = f"r{r}c{c}.png"
        box.save(os.path.join(outdir, fn))

        a24 = np.asarray(window24(img, cx, cy, radius))
        g = to_gray(a24)
        qn, sd = norm24(g)
        dists = sorted(((float(ncc_dist(qn, qt)), nm) for nm, qt, _ in tmpls))
        d0, name0 = dists[0]
        auto = f"{name0}?" if d0 > 0.55 else name0
        labels_tsv.append(f"{fn}\t{auto}\t{d0:.3f}\t{'?'}")

        thumb = box.resize((THUMB, THUMB))
        x = c * THUMB + 20
        y = r * THUMB + 20
        montage.paste(thumb, (x, y))
        draw.rectangle([x, y, x + THUMB - 1, y + THUMB - 1], outline=(180, 170, 150))
        draw.text((x + 2, y + 2), f"{r},{c}", fill=(200, 30, 30))
        draw.text((x + 2, y + THUMB - 12), auto[:12], fill=(20, 20, 120))

with open(os.path.join(outdir, 'labels.tsv'), 'w', encoding='utf-8') as fp:
    fp.write("file\tauto\td\tneed_check\n")
    fp.write('\n'.join(labels_tsv))
montage.save(os.path.join(outdir, 'montage.png'))
print(f"OK: {saved and ''}{len(labels_tsv)} 格 → {outdir}")
print(f"校对图: {os.path.join(outdir, 'montage.png')}")
print("自动粗配已标注在缩略图左下;错误格直接用 '格名=真子' 回复即可")
