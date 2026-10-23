# -*- coding: utf-8 -*-
"""仲裁式入库(2026-10-20, 纯 CNN 单核):
1. 先跑 tools/crop_cells.py 产 90 裁片(+NCC 粗标仅作废, 不使用)
2. cnn.onnx 逐格判定:
   - CNN 置信(m>=0.9) → 归档(CNN 类名)
   - CNN 拒判 → 跳过(靠后续帧/下次采集补全, 不借任何外部引擎)
用法: python tools/ingest_adjudicated.py <图片> <tag>
"""
import os, sys, subprocess, json
import numpy as np
import torch
import onnxruntime as ort
from PIL import Image

ROOT = r'E:\learn\auto_cheese'
DS = os.path.join(ROOT, r'samples\dataset')

def main():
    path, tag = sys.argv[1], sys.argv[2]
    r = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'crop_cells.py'),
                        path, tag], capture_output=True, text=True)
    srcdir = os.path.join(ROOT, 'samples', tag)
    if not os.path.isdir(srcdir):
        print('crop_cells failed:', (r.stderr or '')[-300:])
        return
    sess = ort.InferenceSession(
        os.path.join(ROOT, r'app\src\main\assets\cnn.onnx'),
        providers=['CPUExecutionProvider'])
    meta = json.load(open(os.path.join(ROOT, r'app\src\main\assets\cnn_meta.json')))
    classes = meta['classes']
    mean = np.asarray(meta['mean'], np.float32)[:, None, None]
    std = np.asarray(meta['std'], np.float32)[:, None, None]

    added = skipped = 0
    for fn in sorted(os.listdir(srcdir)):
        if not fn.endswith('.png'):
            continue
        cell = fn.replace('.png', '')
        crop_path = os.path.join(srcdir, fn)
        arr = np.asarray(Image.open(crop_path).convert('RGB')
                         .resize((meta['img'], meta['img']), Image.BILINEAR),
                         np.float32).transpose(2, 0, 1)
        t = torch.from_numpy((arr/255.0 - mean) / std)[None]
        with torch.no_grad():
            lg = sess.run(None, {'cells': t.numpy()})[0][0]
        bi = int(lg.argmax())
        # sigmoid(+diff): 差为正越大越置信(2026-10-20 修: 旧式漏负号,全部误判拒)
        m = float(1/(1+np.exp(-(lg[bi]-np.max(np.delete(lg, bi))))))
        if m < 0.9:
            skipped += 1
            continue
        label = classes[bi]
        if not os.path.isdir(os.path.join(DS, label)):
            skipped += 1
            continue
        import shutil
        shutil.copyfile(crop_path, os.path.join(DS, label, f'{tag}_{cell}.png'))
        added += 1
    print(f'{tag}: added={added} skipped(CNN 拒判)={skipped}')

if __name__ == '__main__':
    main()
