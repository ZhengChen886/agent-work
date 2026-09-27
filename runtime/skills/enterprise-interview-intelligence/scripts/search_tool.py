import sys, re, html, io, os, time, json
import requests

def bing_search(query, count=20, mkt='zh-CN'):
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
               'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8'}
    url = 'https://www.bing.com/search'
    params = {'q': query, 'count': count, 'mkt': mkt, 'setlang': 'zh-hans'}
    r = requests.get(url, params=params, headers=headers, timeout=25)
    r.encoding = 'utf-8'
    text = r.text
    results = []
    items = re.findall(r'<li class="b_algo".*?</li>', text, re.DOTALL)
    for it in items:
        tm = re.search(r'<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', it, re.DOTALL)
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

def baidu_search(query, count=15):
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'}
    url = 'https://www.baidu.com/s'
    params = {'wd': query, 'rn': count}
    r = requests.get(url, params=params, headers=headers, timeout=25)
    r.encoding = 'utf-8'
    text = r.text
    results = []
    items = re.findall(r'<div class="result[^"]*".*?</div>\s*</div>', text, re.DOTALL)
    for it in items:
        tm = re.search(r'<h3[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', it, re.DOTALL)
        if not tm:
            continue
        url2 = tm.group(1)
        title = re.sub(r'<[^>]+>', '', tm.group(2))
        title = html.unescape(title).strip()
        sm = re.search(r'<span class="content-right_[^"]*">(.*?)</span>', it, re.DOTALL) or re.search(r'<div class="c-abstract[^"]*">(.*?)</div>', it, re.DOTALL)
        snippet = ''
        if sm:
            snippet = re.sub(r'<[^>]+>', '', sm.group(1))
            snippet = html.unescape(snippet).strip()
        results.append((title, url2, snippet))
    return results

def main():
    query_file = sys.argv[1] if len(sys.argv) > 1 else 'queries.json'
    engine = sys.argv[2] if len(sys.argv) > 2 else 'bing'
    outfile = sys.argv[3] if len(sys.argv) > 3 else 'search_out.txt'
    with io.open(query_file, 'r', encoding='utf-8') as f:
        queries = json.load(f)
    if isinstance(queries, str):
        queries = [queries]
    fn = bing_search if engine == 'bing' else baidu_search
    with io.open(outfile, 'w', encoding='utf-8') as f:
        for q in queries:
            f.write('===== QUERY: %s =====\n' % q)
            try:
                results = fn(q, 15)
                if not results:
                    f.write('(no results)\n')
                for title, u, s in results:
                    f.write('TITLE: %s\n' % title)
                    f.write('URL: %s\n' % u)
                    f.write('SNIP: %s\n' % s[:500])
                    f.write('---\n')
            except Exception as e:
                f.write('ERROR: %s\n' % e)
            time.sleep(1.5)
    print('done ->', outfile)

if __name__ == '__main__':
    main()