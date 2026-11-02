import json, os, re, sys, time, requests, html

RES = "/tmp/mika/app/src/main/res"
CACHE = "/tmp/mika/hitrans_cache.json"
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"}
cache = json.load(open(CACHE, encoding="utf-8")) if os.path.exists(CACHE) else {}

def entries():
    """(file, kind, name, sub, text) for every translatable text node."""
    out = []
    for fn in sorted(os.listdir(RES + "/values")):
        if not (fn.startswith("strings_") or fn == "strings.xml"):
            continue
        src = open(f"{RES}/values/{fn}", encoding="utf-8").read()
        for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', src, re.S):
            attrs, text = m.group(2), m.group(3)
            if 'translatable="false"' in attrs:
                continue
            out.append((fn, "string", m.group(1), None, text))
        for m in re.finditer(r'<plurals name="([^"]+)"([^>]*)>(.*?)</plurals>', src, re.S):
            for it in re.finditer(r'<item quantity="([^"]+)">([^<]*)</item>', m.group(3)):
                out.append((fn, "plurals", m.group(1), it.group(1), it.group(2)))
        for m in re.finditer(r'<string-array name="([^"]+)"([^>]*)>(.*?)</string-array>', src, re.S):
            for it in re.finditer(r'<item[^>]*>(.*?)</item>', m.group(3), re.S):
                out.append((fn, "array", m.group(1), None, it.group(1)))
    return out

PH = re.compile(r'%(?:[0-9]+\$)?[sdf]|%%')
def protect(t):
    return PH.sub(lambda m: f"~{PH.pattern[:0]}~", "") and None

def translate(text):
    """Translate one XML-escaped value; returns Hindi or None on failure."""
    if text in cache:
        return cache[text]
    if not re.search(r'[A-Za-z]', text) or len(text.strip()) <= 1:
        cache[text] = text
        return text
    plain = html.unescape(text)
    phs = PH.findall(plain)
    work = re.sub(r'\\n', '\u23ce', plain)  # keep literal \n out of the translator
    for i, ph in enumerate(phs):
        work = work.replace(ph, f"[{i}]", 1)
    try:
        r = requests.get("https://translate.googleapis.com/translate_a/single",
                         params={"client": "gtx", "sl": "en", "tl": "hi", "dt": "t", "q": work},
                         headers=UA, timeout=20)
        if r.status_code != 200:
            return None
        out = "".join(seg[0] for seg in r.json()[0] if seg and seg[0])
    except Exception:
        return None
    # restore placeholders
    got = re.findall(r'\[(\d+)\]', out)
    if sorted(int(g) for g in got) != sorted(range(len(phs))):
        cache[text] = None
        return None
    for i, ph in enumerate(phs):
        out = re.sub(r'\[\s*%s\s*\]' % i, ph, out, count=1)
    out = out.replace("\u23ce", "\\n").strip()
    cache[text] = html.escape(out, quote=False) if "&" not in text else out
    return cache[text]

def write_hi():
    os.makedirs(f"{RES}/values-hi", exist_ok=True)
    for fn in sorted(os.listdir(RES + "/values")):
        if not (fn.startswith("strings_") or fn == "strings.xml"):
            continue
        src = open(f"{RES}/values/{fn}", encoding="utf-8").read()
        dst = src
        for m in list(re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', src, re.S)):
            if 'translatable="false"' in m.group(2):
                continue
            hi = cache.get(m.group(3))
            if hi:
                dst = dst.replace(m.group(0), m.group(0).replace(m.group(3), hi, 1), 1)
        for m in list(re.finditer(r'<plurals name="([^"]+)"([^>]*)>(.*?)</plurals>', src, re.S)):
            for it in re.finditer(r'<item quantity="([^"]+)">([^<]*)</item>', m.group(3)):
                hi = cache.get(it.group(2))
                if hi:
                    old = it.group(0); new = old.replace(it.group(2), hi, 1)
                    dst = dst.replace(old, new, 1)
        for m in list(re.finditer(r'<string-array name="([^"]+)"([^>]*)>(.*?)</string-array>', src, re.S)):
            for it in re.finditer(r'<item[^>]*>(.*?)</item>', m.group(3), re.S):
                hi = cache.get(it.group(1))
                if hi:
                    old = it.group(0); new = old.replace(it.group(1), hi, 1)
                    dst = dst.replace(old, new, 1)
        open(f"{RES}/values-hi/{fn}", "w", encoding="utf-8").write(dst)

mode = sys.argv[1] if len(sys.argv) > 1 else "run"
if mode == "stats":
    e = entries()
    todo = [t for _, _, _, _, t in e if t not in cache]
    done = [t for _, _, _, _, t in e if t in cache and cache[t]]
    print(f"total={len(e)} translated={len(done)} todo={len(todo)} chars_todo={sum(len(html.unescape(t)) for t in todo)}")
elif mode == "run":
    e = entries()
    todo = [(f, t) for f, _, _, _, t in e if t not in cache]
    print(f"todo this pass: {len(todo)}")
    ok = fail = 0
    for i, (f, t) in enumerate(todo):
        r = translate(t)
        if r is None:
            fail += 1
        else:
            ok += 1
        if i % 50 == 0:
            json.dump(cache, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
            print(f"{i}/{len(todo)} ok={ok} fail={fail}", flush=True)
        time.sleep(0.18)
    json.dump(cache, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
    write_hi()
    print(f"pass done ok={ok} fail={fail}; cache={len(cache)}")
elif mode == "write":
    write_hi()
    print("written")
