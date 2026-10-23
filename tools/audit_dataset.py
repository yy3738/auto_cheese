# -*- coding: utf-8 -*-
"""数据集审计：重复、颜色矛盾、模型与标签分歧，输出批量标记清单。

用法:
  python tools/audit_dataset.py

输出:
  tools/out/dataset_audit.csv        每个样本一行（类/预测/margin/颜色/重复簇）
  tools/out/review_sheet_*.png       可疑样本拼图（编号与 CSV 行号对应）
"""
from __future__ import annotations

import csv
import glob
import json
import os
from collections import defaultdict

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = "samples/dataset"
OUT = "tools/out"
ASSETS = "app/src/main/assets"
IMG = 128
BATCH = 64


def dhash(path: str) -> int:
    """64-bit 差值哈希（9x8 灰度相邻差）。"""
    im = Image.open(path).convert("L").resize((9, 8), Image.BILINEAR)
    a = np.asarray(im, np.int16)
    bits = (a[:, 1:] > a[:, :-1]).flatten()
    v = 0
    for b in bits:
        v = (v << 1) | int(b)
    return v


def glyph_redness(arr: np.ndarray) -> float:
    """笔画红度：中心区域最暗 18% 像素的 mean(R-G)。"""
    h, w = arr.shape[:2]
    p = arr[int(h * .18):int(h * .82), int(w * .18):int(w * .82)].reshape(-1, 3)
    g = 0.299 * p[:, 0] + 0.587 * p[:, 1] + 0.114 * p[:, 2]
    dark = p[g <= np.percentile(g, 18)]
    return float((dark[:, 0] - dark[:, 1]).mean())


def main() -> None:
    meta = json.load(open(f"{ASSETS}/cnn_meta.json", encoding="utf-8"))
    classes = meta["classes"]
    mean = np.array(meta["mean"], np.float32).reshape(3, 1, 1)
    std = np.array(meta["std"], np.float32).reshape(3, 1, 1)
    sess = ort.InferenceSession(f"{ASSETS}/cnn.onnx", providers=["CPUExecutionProvider"])

    rows = []
    paths = sorted(glob.glob(f"{ROOT}/*/*.*"))
    for p in paths:
        cls = os.path.basename(os.path.dirname(p))
        rows.append({"path": p, "name": os.path.basename(p), "label": cls,
                     "hash": dhash(p), "redness": 0.0, "pred": "", "prob": 0.0})
    print(f"样本 {len(rows)}")

    # ---- 颜色矛盾 ----
    for r in rows:
        a = np.asarray(Image.open(r["path"]).convert("RGB"), np.float32)
        r["redness"] = round(glyph_redness(a), 1)

    # ---- 批量推理 ----
    for s in range(0, len(rows), BATCH):
        chunk = rows[s:s + BATCH]
        batch = []
        for r in chunk:
            a = np.asarray(Image.open(r["path"]).convert("RGB").resize((IMG, IMG), Image.BILINEAR),
                           np.float32).transpose(2, 0, 1) / 255.0
            batch.append((a - mean) / std)
        logits = sess.run(None, {"cells": np.stack(batch).astype(np.float32)})[0]
        for r, lg in zip(chunk, logits):
            order = np.argsort(lg)[::-1]
            top, second = int(order[0]), int(order[1])
            r["pred"] = classes[top]
            r["prob"] = round(float(1 / (1 + np.exp(-(lg[top] - lg[second])))), 4)

    # ---- 重复簇（Hamming <= 3，按类内聚类） ----
    def popcount(x: int) -> int:
        return bin(x).count("1")

    clusters: dict[tuple[str, int], list[dict]] = defaultdict(list)
    for r in rows:
        key = None
        for (cls, h), members in clusters.items():
            if cls == r["label"] and popcount(h ^ r["hash"]) <= 3:
                key = (cls, h)
                break
        if key is None:
            clusters[(r["label"], r["hash"])] = [r]
        else:
            clusters[key].append(r)
    dup_count = sum(len(m) - 1 for m in clusters.values())
    print(f"类内近重复 {dup_count} 张（{len(clusters)} 个唯一簇）")

    for r in rows:
        for (cls, h), members in clusters.items():
            if r in members:
                r["dup_of"] = members[0]["name"] if members[0] is not r else ""
                r["cluster_size"] = len(members)
                break

    # ---- 可疑清单 ----
    suspect = []
    for r in rows:
        reasons = []
        red_cls = r["label"].startswith("R_")
        if r["label"] != "EMPTY":
            if red_cls and r["redness"] < 35:
                reasons.append("label-red/glyph-black")
            if (not red_cls) and r["redness"] > 60:
                reasons.append("label-black/glyph-red")
        if r["pred"] != r["label"]:
            reasons.append(f"model={r['pred']}")
        if r["pred"] == r["label"] and r["prob"] < 0.80 and r["label"] != "EMPTY":
            reasons.append(f"low-margin={r['prob']:.2f}")
        if reasons:
            r["reasons"] = ";".join(reasons)
            suspect.append(r)

    os.makedirs(OUT, exist_ok=True)
    with open(f"{OUT}/dataset_audit.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["idx", "label", "name", "pred", "prob", "redness",
                    "dup_of", "cluster_size", "reasons"])
        for i, r in enumerate(rows):
            r["idx"] = i
            w.writerow([i, r["label"], r["name"], r["pred"], r["prob"], r["redness"],
                        r.get("dup_of", ""), r.get("cluster_size", 1),
                        r.get("reasons", "")])

    # ---- 每类统计 ----
    per = defaultdict(lambda: [0, 0])
    for r in rows:
        per[r["label"]][0] += 1
        if r.get("dup_of"):
            per[r["label"]][1] += 1
    print("\n类别             总数  近重复")
    for c in classes:
        print(f"{c:14s} {per[c][0]:5d} {per[c][1]:6d}")

    print(f"\n可疑样本 {len(suspect)}（详见 tools/out/dataset_audit.csv）")
    order = sorted(suspect, key=lambda r: (r["pred"] == r["label"], -r["prob"]))
    # 拼图，每张 96px，带编号
    from PIL import ImageDraw
    per_sheet = 80
    for si in range(0, len(order), per_sheet):
        chunk = order[si:si + per_sheet]
        cols = 10
        cell = 96
        rowsn = (len(chunk) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cell, rowsn * (cell + 14)), (255, 255, 255))
        d = ImageDraw.Draw(sheet)
        for k, r in enumerate(chunk):
            im = Image.open(r["path"]).convert("RGB").resize((cell, cell), Image.BILINEAR)
            x, y = (k % cols) * cell, (k // cols) * (cell + 14)
            sheet.paste(im, (x, y))
            d.text((x + 2, y + cell + 1), f"{r['idx']}", fill=(0, 0, 0))
        sheet.save(f"{OUT}/review_sheet_{si // per_sheet}.png")
    print(f"拼图: {OUT}/review_sheet_*.png（编号=CSV 行 idx）")


if __name__ == "__main__":
    main()
