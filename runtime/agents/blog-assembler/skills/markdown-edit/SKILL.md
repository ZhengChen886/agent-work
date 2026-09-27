---
name: markdown-edit
description: 编辑与润色 Markdown 文件，并标出需要上传图床的本地图片
---

# Markdown 编辑技能

对博客 Markdown 做发布前的修改与润色。

## 步骤

1. 使用 `file.read` 读取目标 Markdown。
2. 调整标题、结构与格式；识别需要上传图床的本地图片路径。
3. 使用 `file.write` 写回修改后的 Markdown。

## 产出

- 修改后的 Markdown 路径。
- 需要上传图床的本地图片清单。