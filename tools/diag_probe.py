# -*- coding: utf-8 -*-
"""离线复刻 App 端 CNN 识别（单帧整盘），用于定位单格误读。

用法:
  python tools/diag_probe.py <frame.png> [calib.json]

按 App 的 BoardGrid（四角双线性）+ CnnEngine（每格 2*half 裁剪、
half=cellSize*0.45）+ FrameSampling（双线性到 128）跑同一模型，
输出 90 格分类与置信，便于和 App 内日志逐格对照。
"""
import json
import sys

import numpy as np
from PIL import Image
import onnxruntime as ort

ASSETS = "app/src/main/assets"
CALIB = "files/calib_default.json"


def load_grid(calib):
    o = json.load(open(calib, encoding="utf-8"))
    tl = np.array([o["tl"]["x"], o["tl"]["y"]], np.float32)
    tr = np.array([o["tr"]["x"], o["tr"]["y"]], np.float32)
    br = np.array([o["br"]["x"], o["br"]["y"]], np.float32)
    bl = np.array([o["bl"]["x"], o["bl"]["y"]], np.float32)
    xs = np.zeros(90, np.float32)
    ys = np.zeros(90, np.float32)
    for r in range(10):
        v = r / 9.0
        for c in range(9):
            u = c / 8.0
            top = tl + (tr - tl) * u
            bot = bl + (br - bl) * u
            p = top + (bot - top) * v
            xs[r * 9 + c] = p[0]
            ys[r * 9 + c] = p[1]
    d = []
    for r in range(10):
        for c in range(8):
            i = r * 9 + c
            d.append(np.hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i]))
    for r in range(9):
        for c in range(9):
            i = r * 9 + c
            d.append(np.hypot(xs[i + 9] - xs[i], ys[i + 9] - ys[i]))
    return xs, ys, float(np.mean(d))


def refine(gray, xs, ys, w, h):
    """复刻 GridRefiner.refine：逐线扫描暗度峰 + 最小二乘拟合。"""
    SCAN = 12

    def darkness(x, y):
        if x < 1 or x >= w - 1 or y < 1 or y >= h - 1:
            return 0.0
        return 255.0 - gray[y - 1:y + 2, x - 1:x + 2].mean()

    col_fit, col_ok = [None] * 9, [False] * 9
    for k in range(9):
        sx, sy = [], []
        for i in range(9):
            ym = (ys[i * 9 + k] + ys[(i + 1) * 9 + k]) / 2
            xm = (xs[i * 9 + k] + xs[(i + 1) * 9 + k]) / 2
            x0, x1 = max(1, int(xm) - SCAN), min(w - 2, int(xm) + SCAN)
            if x1 <= x0:
                continue
            vals = [darkness(x, int(ym)) for x in range(x0, x1 + 1)]
            bx = x0 + int(np.argmax(vals))
            sy.append(ym)
            sx.append(float(bx))
        if len(sy) >= 4:
            col_fit[k] = np.polyfit(sy, sx, 1)
            col_ok[k] = True
    row_fit, row_ok = [None] * 10, [False] * 10
    for r in range(10):
        sxv, syv = [], []
        for c in range(8):
            xm = (xs[r * 9 + c] + xs[r * 9 + c + 1]) / 2
            yq = int(ys[r * 9 + c])
            y0, y1 = max(1, yq - SCAN), min(h - 2, yq + SCAN)
            if y1 <= y0:
                continue
            vals = [darkness(int(xm), y) for y in range(y0, y1 + 1)]
            by = y0 + int(np.argmax(vals))
            sxv.append(xm)
            syv.append(float(by))
        if len(sxv) >= 5:
            row_fit[r] = np.polyfit(sxv, syv, 1)
            row_ok[r] = True
    nx, ny = xs.copy(), ys.copy()
    for r in range(10):
        for c in range(9):
            if col_ok[c] and row_ok[r]:
                y = row_fit[r][1] + row_fit[r][0] * xs[r * 9 + c]
                x = col_fit[c][1] + col_fit[c][0] * y
                nx[r * 9 + c], ny[r * 9 + c] = x, y
    return nx, ny


def main():
    frame = sys.argv[1] if len(sys.argv) > 1 else "_d1.png"
    calib = sys.argv[2] if len(sys.argv) > 2 else CALIB
    meta = json.load(open(f"{ASSETS}/cnn_meta.json", encoding="utf-8"))
    classes, img = meta["classes"], meta["img"]
    mean = np.array(meta["mean"], np.float32).reshape(3, 1, 1)
    std = np.array(meta["std"], np.float32).reshape(3, 1, 1)
    sess = ort.InferenceSession(f"{ASSETS}/cnn.onnx", providers=["CPUExecutionProvider"])
    im = Image.open(frame).convert("RGB")
    xs, ys, cell = load_grid(calib)
    raw_x0, raw_y0 = float(xs[0]), float(ys[0])
    if "--raw" not in sys.argv:
        a = np.asarray(im, np.float32)
        gray = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
        xs, ys = refine(gray, xs, ys, im.width, im.height)
        print("refined (0,0)=(%.1f,%.1f) raw=(%.1f,%.1f)" % (
            xs[0], ys[0], raw_x0, raw_y0))
    half = int(cell * 0.45)
    size = half * 2
    cells, crops = [], []
    for i in range(90):
        cx, cy = int(xs[i]), int(ys[i])
        x0, y0 = cx - half, cy - half
        box = im.crop((x0, y0, x0 + size, y0 + size)).resize((img, img), Image.BILINEAR)
        arr = np.asarray(box, np.float32).transpose(2, 0, 1) / 255.0
        crops.append((arr - mean) / std)
        cells.append(i)
    batch = np.stack(crops).astype(np.float32)
    logits = sess.run(None, {"cells": batch})[0]
    grid = [[None] * 9 for _ in range(10)]
    conf = [[None] * 9 for _ in range(10)]
    for i, lg in enumerate(logits):
        order = np.argsort(lg)[::-1]
        bi, sj = int(order[0]), int(order[1])
        margin = 1.0 / (1.0 + np.exp(-(lg[bi] - lg[sj])))
        cls = classes[bi]
        grid[i // 9][i % 9] = "・" if cls == "EMPTY" else cls
        conf[i // 9][i % 9] = margin
    print("cell=%.2f half=%d size=%d" % (cell, half, size))
    for r in range(10):
        print(r, " ".join(f"{str(v):9s}" for v in grid[r]))
    print("\nmargin<0.9 cells:")
    for r in range(10):
        for c in range(9):
            if conf[r][c] < 0.9:
                print("  (%d,%d) %s margin=%.3f" % (r, c, grid[r][c], conf[r][c]))
    print("\n(0,0) crop written to tools/out/probe_0_0.png")
    import os
    os.makedirs("tools/out", exist_ok=True)
    im.crop((int(xs[0]) - half, int(ys[0]) - half,
             int(xs[0]) + half, int(ys[0]) + half)).resize(
        (256, 256), Image.NEAREST).save("tools/out/probe_0_0.png")


if __name__ == "__main__":
    main()
