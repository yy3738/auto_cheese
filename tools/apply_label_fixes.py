# -*- coding: utf-8 -*-
"""按 dataset_audit.csv 的 idx 批量修正标签（移动文件到正确类别目录）。

用法:
  python tools/apply_label_fixes.py 278=R_KNIGHT 2124=EMPTY ...
  python tools/apply_label_fixes.py --dry-run 278=R_KNIGHT

编号 = tools/out/dataset_audit.csv 的 idx 列。
同名文件已存在时加 __fixN 后缀，不覆盖。
"""
from __future__ import annotations

import csv
import os
import shutil
import sys

CSV = "tools/out/dataset_audit.csv"
ROOT = "samples/dataset"


def main() -> None:
    args = [a for a in sys.argv[1:]]
    dry = "--dry-run" in args
    edits = [a for a in args if not a.startswith("--")]
    rows = {r["idx"]: r for r in csv.DictReader(open(CSV, encoding="utf-8-sig"))}
    for e in edits:
        if "=" not in e:
            print("跳过无效参数:", e)
            continue
        idx, cls = e.split("=", 1)
        r = rows.get(idx)
        if r is None:
            print("找不到 idx:", idx)
            continue
        src = os.path.join(ROOT, r["label"], r["name"])
        if not os.path.exists(src):
            print("源文件不存在:", src)
            continue
        if r["label"] == cls:
            print("已是目标类:", idx, r["label"])
            continue
        dst_dir = os.path.join(ROOT, cls)
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, r["name"])
        n = 1
        while os.path.exists(dst):
            stem, ext = os.path.splitext(r["name"])
            dst = os.path.join(dst_dir, f"{stem}__fix{n}{ext}")
            n += 1
        print(("DRY " if dry else "MOVE ") + f"{idx} {r['label']} -> {cls}  {r['name']}")
        if not dry:
            shutil.move(src, dst)


if __name__ == "__main__":
    main()
