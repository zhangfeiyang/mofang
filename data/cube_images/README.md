# 魔方图片数据集

非标注的魔方真实图片集合，给 Cube AR 项目用（训练 / 测试 / 演示）。

## 当前规模

| 来源 | 文件数 | 大小 |
|------|--------|------|
| Wikimedia Commons | 28 | 39 MB |
| Bing 图片搜索 | 13 | 4.7 MB |
| **合计** | **41** | **43.3 MB** |

## 目录结构

```
data/cube_images/
├── wikimedia/         # Wikimedia Commons 的 CC 授权图片
└── bing/              # Bing 图片搜索结果
```

## 来源说明

- **Wikimedia Commons**：从 `Category:Rubik's Cube`、`Category:Magic cubes`、`Category:Rubik's Cube patterns` 分类拿到所有位图文件名，逐个查 `imageinfo`（带 `iiurlwidth=1024` 取缩略图 URL）后下载。文件均为 CC 授权（具体许可随文件元数据变化）。
- **Bing 图片搜索**：用 Bing Images 异步接口 `/images/async?q="..."` 拉取，强制精确短语匹配 + URL/标题过滤——命中 `rubik|cubie|moyu|speedcube|cfop|f2l|pll|oll|魔方` 之一，同时排除 `disney|castle|breed|iphone|3d print|...` 及 amazon/aliexpress/pinterest 等商品域名。可能有少量边缘相关（如魔方教程截图、动漫里的魔方）混入。

## 重新下载 / 续传

```bash
# Wikimedia（注意：本机代理 IP 多次触发后会触发全局 API 封禁 30~60 分钟）
PYTHONUNBUFFERED=1 python3 scripts/download_wm_thumb.py

# Bing（更宽松，过滤在 URL/标题层）
PYTHONUNBUFFERED=1 python3 scripts/download_bing_v4.py
```

两个脚本都读 `*_log.json` 跳过已成功的（断点续传）。

## 验证

```bash
python3 scripts/verify_dataset.py
```

检查每个文件 magic bytes，过滤掉非图片（错误页 / HTML 重定向等）。

## 已知限制

- Wikimedia 限流：本机 `HTTP_PROXY=http://127.0.0.1:7890` 经代理出网，多次高并发后 commons.wikimedia.org 会被整体封禁（`You are making too many requests`，curl 直连也照样拿到这段文本而非 JSON）。脚本里 30s/60s/120s 退避不能完全规避，最终就是停手等约 1 小时。
- Wikimedia 缩略图服务器 `upload.wikimedia.org` 对单 IP 也限流；本轮约 1/3 的查询返 429，但部分能拿到，所以最终还能攒 28 张。
- Bing 异步接口分页到 `first=200` 之后基本返 0 条。
- StockSnap.io 对非浏览器 UA 直接返 403，不是限流而是硬拒。
- Open Images V7 的 600 个 boxable 类里**没有 Rubik's Cube**，Puzzle / Cube / Twisty puzzle 这类细类都不存在；只有 `Toy` 太宽没法筛。
- 未做内容级去重和"图确实是魔方"的过滤。文件名/URL 看起来像不等于内容像，做训练前建议人工过一遍。
