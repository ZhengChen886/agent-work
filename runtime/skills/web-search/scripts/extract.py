import re, html, sys, io

def extract(path, out):
    with open(path, encoding='utf-8', errors='ignore') as f:
        t = f.read()
    t = re.sub(r'<script.*?</script>', '', t, flags=re.S)
    t = re.sub(r'<style.*?</style>', '', t, flags=re.S)
    t = re.sub(r'<[^>]+>', '\n', t)
    t = html.unescape(t)
    t = re.sub(r'[ \t]+', ' ', t)
    t = re.sub(r'\n\s*\n+', '\n', t)
    with open(out, 'w', encoding='utf-8') as f:
        f.write(t)
    print("done", len(t))

if __name__ == '__main__':
    extract(sys.argv[1], sys.argv[2])