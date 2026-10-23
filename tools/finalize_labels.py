# -*- coding: utf-8 -*-
"""标注落库:把裁好的 90 格按 标签 归档到训练数据集 samples/dataset/<类>/。
用法: python tools/finalize_labels.py <tag> [纠正项...]
纠正项格式: 行,列=类名  类名支持中文(红车/黑马/空/士...)或英文(R_ROOK/B_KING/EMPTY)
无纠正项的格子若 NCC 置信(≤0.55)按自动标注归档;模糊(>0.55)跳过防污染
"""
import sys, os, shutil

CN2CLS = {
    "空": "EMPTY", "红车": "R_ROOK", "黑马": "B_KNIGHT", "红炮": "R_CANNON",
    "黑炮": "B_CANNON", "红马": "R_KNIGHT", "黑车": "B_ROOK", "红兵": "R_PAWN",
    "黑卒": "B_PAWN", "红相": "R_BISHOP", "红象": "R_BISHOP", "黑象": "B_BISHOP",
    "红仕": "R_ADVISOR", "黑士": "B_ADVISOR", "红帅": "R_KING", "黑将": "B_KING",
    "红帥": "R_KING", "黑將": "B_KING", "红俥": "R_ROOK", "黑車": "B_ROOK",
    "红傌": "R_KNIGHT", "黑馬": "B_KNIGHT", "红砲": "R_CANNON", "黑砲": "B_CANNON",
    "卒": "B_PAWN", "兵": "R_PAWN",
}
EN2CLS = {
    "EMPTY": "EMPTY", "R_ROOK": "R_ROOK", "R_KNIGHT": "R_KNIGHT",
    "R_CANNON": "R_CANNON", "R_BISHOP": "R_BISHOP", "R_ADVISOR": "R_ADVISOR",
    "R_KING": "R_KING", "R_PAWN": "R_PAWN", "B_ROOK": "B_ROOK",
    "B_KNIGHT": "B_KNIGHT", "B_CANNON": "B_CANNON", "B_BISHOP": "B_BISHOP",
    "B_ADVISOR": "B_ADVISOR", "B_KING": "B_KING", "B_PAWN": "B_PAWN",
}

tag = sys.argv[1]
fixes = {}
for a in sys.argv[2:]:
    if "=" not in a:
        continue
    pos, name = a.split("=", 1)
    r, c = pos.split(",")
    cls = CN2CLS.get(name.strip()) or EN2CLS.get(name.strip().upper())
    if cls is None:
        print(f"!! 未知类名 {name} (格 {pos}),忽略")
        continue
    fixes[(int(r), int(c))] = cls

root = r'E:\learn\auto_cheese\samples'
srcdir = os.path.join(root, tag)
ds = os.path.join(root, 'dataset')
tsv = [l.split('\t') for l in open(os.path.join(srcdir, 'labels.tsv'),
                                   encoding='utf-8').read().splitlines()[1:]]

kept = fixed = skipped = 0
for parts in tsv:
    if len(parts) < 2:
        continue
    fn, auto = parts[0], parts[1]
    d = float(parts[2]) if len(parts) > 2 and parts[2] else 9.0
    rc = fn.replace('.png', '')
    r, c = rc[1:].split('c')
    key = (int(r), int(c))
    label = fixes.get(key)
    if label is not None:
        fixed += 1
    else:
        if d > 0.55:
            skipped += 1
            continue
        label = EN2CLS.get(auto.split('?')[0])
        if label is None:
            skipped += 1
            continue
    dst = os.path.join(ds, label)
    os.makedirs(dst, exist_ok=True)
    shutil.copy(os.path.join(srcdir, fn),
                os.path.join(dst, f"{tag}_{r}c{c}.png"))
    kept += 1

print(f"标签 {fixed} 修正,自动 {kept-fixed} 归档,模糊跳过 {skipped}")
print(f"数据集: {ds}")
for cls in sorted(os.listdir(ds)):
    if not os.path.isdir(os.path.join(ds, cls)):
        continue
    n = len(os.listdir(os.path.join(ds, cls)))
    print(f"  {cls:12s} {n}")
