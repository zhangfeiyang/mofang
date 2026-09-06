# 真机魔方标注集

由 `scripts/pack_real_dataset.py` 从 `frames/` 两个视频目录合并而来。

- 图片：`images/{视频名}_{原文件名}.jpg`，避免两个目录都叫 `frame_000001.jpg` 时互相覆盖
- `annotations.json`：合并后的 `cube-mark/v2`，含全部四边形
- `split.json`：按每个视频时间顺序 80/20 划分；未标框的帧单独列出
- `preview.jpg`：抽样可视化，红框是面积最大的面（训练主目标），青框是同一帧上的其它面

未标框的两帧画面里仍有魔方，训练时会跳过，不当作负样本。
