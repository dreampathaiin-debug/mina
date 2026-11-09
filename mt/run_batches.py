import json, subprocess, sys, time
mode = sys.argv[1]
budget = float(sys.argv[2])
chunks = sys.argv[3:]
t0 = time.time()
for c in chunks:
    files = json.load(open(c))
    if not files: continue
    # recompute remaining (files may already be done)
    remaining = []
    import re
    cjk = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]')
    for f in files:
        try:
            if any(cjk.search(l) for l in open(f, encoding='utf-8')): remaining.append(f)
        except Exception: pass
    if not remaining:
        print(c, 'already done'); continue
    left = time.time() - t0
    if left > budget:
        print('BUDGET UP, remaining chunk:', c, len(remaining)); break
    r = subprocess.run(['python3', 'mt/mt.py', mode] + remaining)
    if r.returncode != 0:
        print('CHUNK FAILED', c, 'rc', r.returncode); break
print('elapsed', round(time.time()-t0, 1))
