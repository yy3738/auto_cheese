# 引擎采用 Pikafish（NNUE + Alpha-Beta），不做 AlphaGo 式自训练

需求是"几步以内的正确走法"。AlphaGo 式方案（自我对弈训练神经网络 + MCTS）的唯一价值是从零学出强引擎，训练成本是 GPU 集群 + 数周，对开箱即用的需求是负收益。Pikafish 是 Stockfish 官方维护的中国象棋分支，棋力远超人类，通过 NDK 交叉编译 + JNI 集成，UCI 协议通信，默认 `movetime 800ms`、2-4 线程、MultiPV 3，三档时间预算可调。复盘与局面知识库都依赖引擎评分的绝对可信，故选最强引擎作地基。

## Considered Options

- **Pikafish（采纳）**：最强开源象棋引擎，NNUE 评估，Android 交叉编译有大量先例
- **AlphaGo 式自训练（否决）**：训练成本极高且结果必然弱于现成引擎
- **ElephantEye 象眼（否决）**：轻量但棋力低一档，知识库与复盘的价值会随之塌陷
- **自研引擎（否决）**：象棋规则细节（蹩马腿、塞象眼、对脸）易错，棋力上限低
