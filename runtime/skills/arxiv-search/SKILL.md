---
name: arxiv-search
description: "搜索 arXiv 预印本库获取学术论文。支持关键词搜索、相关性排序、最大结果数限制。适用于：查找最新机器学习论文、跟踪特定领域研究进展、获取论文标题和摘要。无需 API 密钥，免费使用。"
---

# arXiv 论文搜索

本技能提供 Python 脚本搜索 arXiv 并返回格式化结果。

## 适用场景

当你需要以下任务时可使用本技能：
- 查找最新的机器学习/深度学习论文
- 跟踪特定研究领域的最新进展
- 获取论文标题和摘要用于文献综述
- 搜索计算生物学、生物信息学相关论文
- 获取尚未发表在期刊上的预印本论文

## 使用方法

The skill provides a Python script that searches arXiv and returns formatted results.

### Basic Usage

**Note:** Always use the absolute path from your skills directory (shown in the system prompt above).

If running deepagents from a virtual environment:
```bash
.venv/bin/python [YOUR_SKILLS_DIR]/arxiv-search/scripts/arxiv_search.py "your search query" [--max-papers N]
```

Or for system Python:
```bash
python3 [YOUR_SKILLS_DIR]/arxiv-search/scripts/arxiv_search.py "your search query" [--max-papers N]
```

Replace `[YOUR_SKILLS_DIR]` with the absolute skills directory path from your system prompt (e.g., `~/.deepagents/agent/skills` or the full absolute path).

**Arguments:**
- `query` (required): The search query string (e.g., "neural networks protein structure", "single cell RNA-seq")
- `--max-papers` (optional): Maximum number of papers to retrieve (default: 10)

### Examples

Search for machine learning papers:
```bash
.venv/bin/python ~/.deepagents/agent/skills/arxiv-search/scripts/arxiv_search.py "deep learning drug discovery" --max-papers 5
```

Search for computational biology papers:
```bash
.venv/bin/python ~/.deepagents/agent/skills/arxiv-search/scripts/arxiv_search.py "protein folding prediction"
```

Search for bioinformatics methods:
```bash
.venv/bin/python ~/.deepagents/agent/skills/arxiv-search/scripts/arxiv_search.py "genome assembly algorithms"
```

## Output Format

The script returns formatted results with:
- **Title**: Paper title
- **Summary**: Abstract/summary text

Each paper is separated by blank lines for readability.

## Features

- **Relevance sorting**: Results ordered by relevance to query
- **Fast retrieval**: Direct API access with no authentication required
- **Simple interface**: Clean, easy-to-parse output
- **No API key required**: Free access to arXiv database

## Dependencies

This skill requires the `arxiv` Python package. The script will detect if it's missing and show an error.

**If you see "Error: arxiv package not installed":**

If running deepagents from a virtual environment (recommended), use the venv's Python:
```bash
.venv/bin/python -m pip install arxiv
```

Or for system-wide install:
```bash
python3 -m pip install arxiv
```

The package is not included in deepagents by default since it's skill-specific. Install it on-demand when first using this skill.

## Notes

- arXiv is particularly strong for:
    - Computer science (cs.LG, cs.AI, cs.CV)
    - Quantitative biology (q-bio)
    - Statistics (stat.ML)
    - Physics and mathematics
- Papers are preprints and may not be peer-reviewed
- Results include both recent uploads and older papers
- Best for computational/theoretical work in biology