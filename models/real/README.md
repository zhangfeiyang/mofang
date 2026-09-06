# 真机魔方检测模型

在 `data/real_cubes`（108 帧、106 张有框）上训练的 `CubeFaceNet`。

| | width 1.0 | **width 1.5（当前）** |
|---|---|---|
| 参数量 | 0.15M | 0.32M |
| 验证集角点误差（画面宽） | 0.113 | **0.093** |
| 验证集 presence（阈值 0.85） | 21/21 | **21/21** |
| ONNX | 0.61 MB | **1.29 MB** |

输入 `1×3×288×160` RGB，输出四个归一化角点 + presence，与 App 里 `CubeFaceModel` 一致。

```bash
cp models/real/cubeface.onnx app/src/main/assets/
```

验证可视化见 `val_preview.jpg`（绿=标注最大面，红=预测）。
