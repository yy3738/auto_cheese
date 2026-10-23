# -*- coding: utf-8 -*-
"""这一局影子指标分析 v2: detail 行归属其前面的 frame 行"""
import re
import sys
from collections import Counter

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
LOG = r'E:\learn\auto_cheese\_log4.txt'
GAME_START = '09:09:30'

pat_frame = re.compile(
    r'^\S+ (\S+) D/charcnn: frame: diffs=(\d+)/90 unknown=(\d+)/90 '
    r'同判=(\d+)% 参评=(\d+) 跳格=(\d+) 耗时=(\d+)ms')
pat_unk = re.compile(r'\(r(\d),c(\d)\) UNKNOWN m=([\d.]+) ncc=(\S+)')
pat_dis = re.compile(r'\(r(\d),c(\d)\) ncc=(\S+) cnn=(\S+) m=([\d.]+)')

frames = []          # 对局时段的 frame
pending_game = False # 上一行 frame 是否属对局
unk_cells = Counter()
dis_cells = Counter()
dis_ex = {}
unk_ex = {}

with open(LOG, encoding='utf-8', errors='replace') as f:
    for line in f:
        m = pat_frame.match(line)
        if m:
            pending_game = m.group(1) >= GAME_START
            if pending_game:
                frames.append(dict(
                    t=m.group(1), diffs=int(m.group(2)), unknown=int(m.group(3)),
                    agree=int(m.group(4)), cost=int(m.group(7))))
            continue
        if not pending_game:
            continue
        for u in pat_unk.finditer(line):
            key = (int(u.group(1)), int(u.group(2)))
            unk_cells[key] += 1
            unk_ex.setdefault(key, (u.group(3), u.group(4)))
        for d in pat_dis.finditer(line):
            key = (int(d.group(1)), int(d.group(2)))
            dis_cells[key] += 1
            dis_ex.setdefault(key, (d.group(3), d.group(4), float(d.group(5))))

n = len(frames)
tot_d = sum(f['diffs'] for f in frames)
tot_u = sum(f['unknown'] for f in frames)
ag = [f['agree'] for f in frames]
print(f'对局时段帧数 {n} ({GAME_START} 起)')
print(f'diffs 总计 {tot_d} 平均 {tot_d/n:.2f}/帧')
print(f'unknown 总计 {tot_u} 平均 {tot_u/n:.2f}/帧 (占参评 {tot_u/(n*90)*100:.2f}%)')
print(f'同判率: 100%帧 {sum(1 for a in ag if a==100)}/{n} ({sum(1 for a in ag if a==100)/n*100:.1f}%), '
      f'90-99% {sum(1 for a in ag if 90<=a<100)}, <90% {sum(1 for a in ag if a<90)}')
low = [f for f in frames if f['agree'] < 90]
for f in low:
    print(f'  低同判帧: {f["t"]} agree={f["agree"]}% diffs={f["diffs"]}')
print(f'\nUNKNOWN 热点 top10 (m=首例置信, ncc=该格NCC判定):')
for (r, c), cnt in unk_cells.most_common(10):
    mm, ne = unk_ex[(r, c)]
    print(f'  r{r}c{c}: {cnt} ({cnt/n*100:.0f}%帧) m={mm} ncc={ne}')
print(f'\n分歧热点 top10 (ncc vs cnn 首例):')
for (r, c), cnt in dis_cells.most_common(10):
    ne, cn, mm = dis_ex[(r, c)]
    print(f'  r{r}c{c}: {cnt} ({cnt/n*100:.0f}%帧) ncc={ne} cnn={cn} m={mm}')
if dis_cells:
    mv = [v[2] for v in dis_ex.values()]
    print(f'\n分歧首例置信度: min={min(mv):.2f} max={max(mv):.2f} >=0.9的 {sum(1 for v in mv if v>=0.9)}/{len(mv)}')
