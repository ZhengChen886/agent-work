import sys, re, html, io
import requests

def bing_search(query, count=20, mkt='en-US'):
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'}
    url = 'https://www.bing.com/search'
    params = {'q': query, 'count': count, 'mkt': mkt, 'setlang': 'en'}
    r = requests.get(url, params=params, headers=headers, timeout=25)
    text = r.text
    results = []
    items = re.findall(r'<li class="b_algo".*?</li>', text, re.DOTALL)
    for it in items:
        tm = re.search(r'<h2[^>]*><a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', it, re.DOTALL)
        if not tm:
            continue
        url2 = tm.group(1)
        title = re.sub(r'<[^>]+>', '', tm.group(2))
        title = html.unescape(title).strip()
        sm = re.search(r'<p[^>]*>(.*?)</p>', it, re.DOTALL)
        snippet = ''
        if sm:
            snippet = re.sub(r'<[^>]+>', '', sm.group(1))
            snippet = html.unescape(snippet).strip()
        results.append((title, url2, snippet))
    return results

if __name__ == '__main__':
    q = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 15
    out = sys.argv[3] if len(sys.argv) > 3 else 'bing_out.txt'
    lines = []
    for title, u, s in bing_search(q, n):
        lines.append('TITLE: ' + title)
        lines.append('URL: ' + u)
        lines.append('SNIP: ' + s[:400])
        lines.append('---')
    with io.open(out, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    print('wrote', len(lines), 'lines to', out)