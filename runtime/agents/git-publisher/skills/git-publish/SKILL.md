---
name: git-publish
description: 对仓库执行 git add/commit/push 完成发布提交
---

# Git 发布提交技能

对当前仓库执行提交与推送。

## 步骤

1. 使用 `shell.exec` 执行 `git status` 查看改动。
2. 依次执行 `git add .`、`git commit -m "<说明>"`、`git push`。
3. 返回提交结果与 commit 摘要。

## 产出

- 提交结果（成功/失败及各命令输出）。