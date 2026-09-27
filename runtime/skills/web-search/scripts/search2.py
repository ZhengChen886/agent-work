import sys, subprocess, urllib.parse, re, html, os

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

def fetch(url, out):
    subprocess.run(["curl.exe", "-s", "-m", "25", "-L", "-A", UA, url, "-o", out], check=False)

def clean(src, dst):
    t = open(src, encoding='utf-8', errors='ignore').read()
    t = re.sub(r'<script.*?</script>', '', t, flags=re.S)
    t = re.sub(r'<style.*?</style>', '', t, flags=re.S)
    t = re.sub(r'<[^>]+>', '\n', t)
    t = html.unescape(t)
    t = re.sub(r'[ \t]+', ' ', t)
    t = re.sub(r'\n\s*\n+', '\n', t)
    open(dst, 'w', encoding='utf-8').write(t)
    return len(t)

queries = {
    "baidu_ai": "威健集团 AI开发工程师 招聘 岗位职责",
    "baidu_rich": "威健实业 代理 存储 记忆体 AI服务器",
    "baidu_news": "威健 2025 AI 服务器 存储 代理 动态",
}

for key, q in queries.items():
    url = "https://www.baidu.com/s?wd=" + urllib.parse.quote(q)
    h = key + ".html"
    t = key + ".txt"
    fetch(url, h)
    n = clean(h, t)
    print(key, "raw:", os.path.getsize(h), "clean:", n)