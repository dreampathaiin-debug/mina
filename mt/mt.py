import json, re, os, sys, time, urllib.request, urllib.parse

CJK = '\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff'
CJKP = '\u3001\u3002\uff0c\uff1a\uff1b\uff1f\uff01\u300c\u300d\u300e\u300f\uff08\uff09\u300a\u300b\u00b7\u2014\u2026\uff05\uff40'
re_cjkchar = re.compile('[' + CJK + ']')
re_cjkrun = re.compile('[' + CJK + ']+')
re_cjkp = re.compile('[' + CJKP + ']+')

CACHE_PATH = '/workspace/meena/mt/cache.json'
CACHE = json.load(open(CACHE_PATH)) if os.path.exists(CACHE_PATH) else {}

def is_cjk(c):
    return ('\u3400' <= c <= '\u4dbf') or ('\u4e00' <= c <= '\u9fff') or ('\uf900' <= c <= '\ufaff')

def mt_request(text):
    url = 'https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=zh-CN&tl=en&q=' + urllib.parse.quote(text)
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36'})
    data = json.loads(urllib.request.urlopen(req, timeout=15).read().decode())
    if isinstance(data, list) and data and isinstance(data[0], list):
        return ''.join(p[0] if isinstance(p, list) else p for p in data[0])
    if isinstance(data, list) and all(isinstance(x, str) for x in data):
        return ' '.join(data)
    return str(data)

def mt(text):
    if text in CACHE: return CACHE[text]
    chunks = []
    if len(text) > 1400:
        parts = re.split(r'(?<=[。!?])', text)
        cur = ''
        for p in parts:
            if len(cur) + len(p) > 1400 and cur: chunks.append(cur); cur = p
            else: cur += p
        if cur: chunks.append(cur)
    else:
        chunks = [text]
    out = []
    for ch in chunks:
        for attempt in range(5):
            try:
                r = mt_request(ch); time.sleep(0.05); break
            except Exception as e:
                if attempt == 4: raise
                time.sleep(1.5 * (attempt + 1))
        out.append(r)
    res = ''.join(out)
    CACHE[text] = res
    return res

def tokenize(line):
    toks = []; i = 0; n = len(line)
    while i < n:
        c = line[i]
        if line.startswith('${', i):
            j = line.find('}', i)
            if j == -1: toks.append(('asc', c)); i += 1
            else: toks.append(('ph', line[i:j+1])); i = j + 1
        elif c in CJKP:
            j = i
            while j < n and line[j] in CJKP: j += 1
            toks.append(('pun', line[i:j])); i = j
        elif is_cjk(c):
            m = re_cjkrun.match(line, i); toks.append(('cjk', m.group(0))); i = m.end()
        elif c.isspace():
            j = i
            while j < n and line[j] == ' ': j += 1
            toks.append(('sp', line[i:j])); i = j
        elif c.isdigit():
            j = i
            while j < n and (line[j].isdigit() or line[j] == '.'): j += 1
            toks.append(('num', line[i:j])); i = j
        elif c == '%':
            m = re.match(r'%\d+\$[sd]|%[sd]', line[i:])
            if m: toks.append(('pct', m.group(0))); i += m.end()
            else: toks.append(('asc', c)); i += 1
        elif c.isalpha():
            j = i
            while j < n and (line[j].isalpha() or line[j] == '_') and not is_cjk(line[j]): j += 1
            toks.append(('asc', line[i:j])); i = j
        else:
            toks.append(('asc', c)); i += 1
    return toks

def build_spans(toks):
    spans = []; i = 0; N = len(toks)
    def qualify(j):
        # token at j absorbable into a span?
        if j >= N: return False
        k, s = toks[j]
        if k in ('cjk', 'pun', 'ph', 'pct'): return True
        if k == 'num':
            nj = j + 1
            while nj < N and toks[nj][0] == 'sp': nj += 1
            return nj < N and toks[nj][0] in ('cjk', 'pun', 'ph', 'pct')
        if k == 'sp':
            nj = j + 1
            if nj >= N: return False
            return qualify(nj)
        if k == 'asc':
            if not re.fullmatch(r'[A-Za-z]{1,8}', s): return False
            nj = j + 1
            while nj < N and toks[nj][0] == 'sp': nj += 1
            if nj >= N: return False
            nk = toks[nj][0]
            if nk in ('cjk', 'pun', 'ph', 'pct'): return True
            if nk == 'num' and nj + 1 < N and toks[nj+1][0] in ('cjk', 'pun'): return True
            return False
        return False
    while i < N:
        if qualify(i):
            j = i
            while j < N and qualify(j): j += 1
            # trim trailing sp
            while j > i and toks[j-1][0] == 'sp': j -= 1
            spans.append((i, j)); i = j
        else:
            i += 1
    return spans

def translate_line(line):
    toks = tokenize(line)
    spans = build_spans(toks)
    out = list(line)
    ph_re = re.compile(r'ZPX(\d+)Z')
    for (a, b) in spans:
        seg = ''.join(t[1] for t in toks[a:b])
        phs = re_ph.findall(seg) if False else []
        # collect placeholders in order
        phs = []
        buf = ''
        idx = 0
        toks_seg = toks[a:b]
        # build protected string
        prot = ''
        pidx = 0
        pieces = []
        for t in toks_seg:
            if t[0] in ('ph', 'pct'):
                pieces.append(('T', t[1]))
            else:
                pieces.append(('S', t[1]))
        # merge pieces into string with markers
        prot = ''
        marks = []
        nph = 0
        for k, s in pieces:
            if k == 'T':
                prot += 'ZPX%dZ' % nph; marks.append(s); nph += 1
            else:
                prot += s
        tr = mt(prot)
        # restore
        def restore(m):
            i = int(m.group(1))
            return marks[i] if i < len(marks) else m.group(0)
        tr = ph_re.sub(restore, tr)
        # replace tokens a..b in out via positions
        # recompute char positions
        pos = 0
        start = None; end = None
        for idx2, t in enumerate(toks):
            if idx2 == a: start = pos
            pos += len(t[1])
            if idx2 == b - 1: end = pos
        out[start:end] = list(tr)
    return ''.join(out)

# rebuild via replace on original line using left-to-right sequential replace (spans disjoint, unique enough)
def translate_line2(line):
    toks = tokenize(line)
    spans = build_spans(toks)
    if not spans: return line
    res = ''; prev = 0
    # need char offsets: recompute
    offs = []; pos = 0
    for t in toks: offs.append(pos); pos += len(t[1])
    for (a, b) in spans:
        res += line[prev:offs[a]]
        seg = ''.join(t[1] for t in toks[a:b])
        phs = []; prot = ''; marks = []
        for k, s in toks[a:b]:
            if k in ('ph', 'pct'): prot += 'ZPX%dZ' % len(marks); marks.append(s)
            else: prot += s
        tr = mt(prot)
        def restore(m):
            i = int(m.group(1))
            return marks[i] if i < len(marks) else m.group(0)
        tr = re.sub(r'ZPX(\d+)Z', restore, tr)
        res += tr; prev = offs[a] + len(seg)
    res += line[prev:]
    return res

# main: files from argv, mode main|test
mode = sys.argv[1]
files = sys.argv[2:]
report = {'files': 0, 'lines': 0, 'spans': 0, 'skipped_cjk_outside_comment': 0, 'untranslated': []}
for f in files:
    lines = open(f, encoding='utf-8').read().splitlines(keepends=True)
    changed = 0
    newlines = []
    for ln in lines:
        raw = ln.rstrip('\n')
        eol = ln[len(raw):]
        if not re_cjkchar.search(raw):
            newlines.append(ln); continue
        if mode == 'main':
            # comment-only translation
            m = re.match(r'^(\s*(?:\*|//|/\*\*?|//?!)?.*)$', raw)
            # find comment start: first '//' outside quotes
            idx = None
            inq = False
            i = 0
            while i < len(raw):
                c = raw[i]
                if c == '\\': i += 2; continue
                if c == '"': inq = not inq
                if not inq and raw.startswith('//', i) and (i == 0 or raw[i-1] != ':'):
                    idx = i; break
                i += 1
            block = re.match(r'^\s*(/\*\*?|\*)', raw)
            if block:
                cstart = re.match(r'^\s*/\*\*?', raw)
                if cstart: cstart = cstart.end()
                else: cstart = re.match(r'^\s*\*', raw).end()
                head, tail = raw[:cstart], raw[cstart:]
            elif idx is not None and re_cjkchar.search(raw, idx):
                head, tail = raw[:idx], raw[idx:]
            elif idx is None and re_cjkchar.search(raw):
                report['untranslated'].append(f + ' :: ' + raw.strip()[:100]); newlines.append(ln); continue
            else:
                newlines.append(ln); continue
            if re_cjkchar.search(tail):
                nt = translate_line2(tail); report['spans'] += 1; changed += 1
                newlines.append(head + nt + eol)
            else:
                newlines.append(ln)
        else:
            nt = translate_line2(raw); report['spans'] += 1; changed += 1
            newlines.append(nt + eol)
        report['lines'] += 1
    open(f, 'w', encoding='utf-8').write(''.join(newlines))
    json.dump(CACHE, open(CACHE_PATH, 'w'))
    report['files'] += 1
    print(f, 'lines_changed', changed)
json.dump(CACHE, open(CACHE_PATH, 'w'))
json.dump(report['untranslated'], open('/workspace/meena/mt/untranslated.json', 'w'), ensure_ascii=False)
print('files', report['files'], 'cjk lines seen', report['lines'], 'spans', report['spans'], 'skipped_main_string_cjk', len(report['untranslated']))
