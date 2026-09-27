---
name: hexo-post
description: 把文章正文转换为带 YAML frontmatter 的 Hexo 博客文章，写入 source/_posts 目录
---

# Hexo 博客生成技能

将普通 Markdown 文章包装成 Hexo 可识别的博客文章。

## 步骤

1. 使用 `file.read` 读取输入文章内容。
2. 生成 Hexo frontmatter（`title`、`date`、`tags`、`categories`）。
3. 使用 `file.write` 写入 `source/_posts/<slug>.md`。

## 产出

- 生成后的 Hexo 文章路径。