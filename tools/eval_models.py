# -*- coding: utf-8 -*-
"""新旧 CNN 同集验收（不重训）。

用法: python tools/eval_models.py
口径与 App 一致：128x128 BILINEAR、ImageNet 归一化、单次前向、不 TTA。
"""
from __future__ import annotations

import glob
import json
import os

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = "samples/dataset"
ASSETS = "app/src/main/assets"
IMG = 128
OLD = "tools/out/cnn_old.onnx"
NEW = "app/src/main/assets/cnn.onnx"

CLASSES = sorted(d for d in os.listdir(ROOT) if os.path.isdir(os.path.join(ROOT, d)))
MEAN = np.array([0.485, 0.456, 0.406], np.float32).reshape(3, 1, 1)
STD = np.array([0.229, 0.224, 0.225], np.float32).reshape(3, 1, 1)


def load(path: str) -> np.ndarray:
    a = np.asarray(Image.open(path).convert("RGB").resize((IMG, IMG), Image.BILINEAR),
                   np.float32)
    return ((a / 255.0 - MEAN.transpose(1, 2, 0)) / STD.transpose(1, 2, 0)
            ).transpose(2, 0, 1)


def paths() -> list[tuple[str, str]]:
    out = []
    for c in CLASSES:
        for p in glob.glob(f"{ROOT}/{c}/*.*"):
            out.append((p, c))
    return out


def predict(sess, batch: list[np.ndarray]) -> tuple[list[int], list[float]]:
    logits = sess.run(None, {"cells": np.stack(batch).astype(np.float32)})[0]
    idx, margins = [], []
    for lg in logits:
        o = np.argsort(lg)[::-1]
        idx.append(int(o[0]))
        margins.append(float(1 / (1 + np.exp(-(lg[o[0]] - lg[o[1]])))))
    return idx, margins


def ring(arr: np.ndarray) -> np.ndarray:
    """合成 JJ 红圈：半径 0.30-0.48 格距、宽 2-6px。"""
    a = arr.copy()
    h, w = a.shape[:2]
    rng = np.random.default_rng(20261022)
    cy, cx = h // 2 + 2, w // 2 - 2
    rr = h * 0.40
    width = max(2.0, h * 0.035)
    yy, xx = np.mgrid[0:h, 0:w]
    d2 = (yy - cy) ** 2 + (xx - cx) ** 2
    band = (d2 <= rr * rr) & (d2 >= (rr - width) ** 2)
    a[band] = a[band] * 0.3 + np.array((225, 25, 25), np.float32) * 0.7
    return a


def main() -> None:
    old = ort.InferenceSession(OLD, providers=["CPUExecutionProvider"])
    new = ort.InferenceSession(NEW, providers=["CPUExecutionProvider"])
    items = paths()
    print(f"样本 {len(items)}")

    live = [(p, c) for p, c in items if os.path.basename(p).startswith("Lmp_")]
    hard_names = set()
    sec = ""
    for line in open("tools/out/review_list.txt", encoding="utf-8"):
        if line.startswith("##"):
            sec = line.strip()
        elif sec == "## EMPTY_FP" and line.strip():
            hard_names.add(line.rstrip("\n").split("\t")[-1].strip())
    hard = [(p, c) for p, c in items if os.path.basename(p) in hard_names and c == "EMPTY"]
    print(f"留出 {len(live)}  空位硬负 {len(hard)}")

    def report(name: str, sess) -> None:
        li, lm = predict(sess, [load(p) for p, _ in live])
        lhard = sum(1 for i, m, (_, c) in zip(li, lm, live)
                    if m >= 0.9 and CLASSES[i] != c)
        lconf = sum(1 for m in lm if m >= 0.9)
        hi, hm = predict(sess, [load(p) for p, _ in hard]) if hard else ([], [])
        hbad = sum(1 for i, m in zip(hi, hm)
                   if m >= 0.9 and CLASSES[i] != "EMPTY")
        # 黑子+红圈颜色翻转
        blacks = [(p, c) for p, c in items if c.startswith("B_")]
        ri, _ = predict(sess, [load(p) for p, _ in blacks])
        rbad = [os.path.basename(p) for (p, c), i in zip(blacks, ri)
                if CLASSES[i].startswith("R_")]
        print(f"{name}: 留出 conf={lconf}/{len(live)} hard={lhard} "
              f"空位硬负错={hbad}/{len(hard)} 红圈黑子翻转={len(rbad)}/{len(blacks)}")
        if rbad:
            print("   翻转样本:", ", ".join(rbad[:12]))

    report("旧", old)
    report("新", new)

    # 修正过标签的样本：新模型应预测为当前目录类
    fixed = ["hjj3_9c1", "g27_8c2", "shot1_9c8", "d1315_r3c4", "now10_r2c0",
             "now10_r3c0", "now3_r9c1", "shot15_r6c0", "now5_r9c5", "now5_r4c3",
             "now5_r4c0", "U1_r9c0", "now3_r8c8", "now3_r9c0", "U1_r4c4",
             "U1_r4c8", "d1315_r2c8"]
    sel = [(p, c) for p, c in items if os.path.splitext(os.path.basename(p))[0] in fixed]
    if sel:
        ni, nm = predict(new, [load(p) for p, _ in sel])
        ok = sum(1 for i, c in zip(ni, sel) if CLASSES[i] == c[1])
        print(f"修正标签样本: 新模型 {ok}/{len(sel)}")


if __name__ == "__main__":
    main()
