"""Copies the server bash scripts out of ServerSetup.cs (C# raw strings) into Android assets, verbatim."""
# Usage: py -3 tools/gen_server_scripts.py <ServerSetup.cs> app/src/main/assets/server
import os, re, sys
src = open(sys.argv[1], encoding='utf-8-sig').read()
out = sys.argv[2]
os.makedirs(out, exist_ok=True)
# Every raw string: optional $ prefix, """\n body \n<indent>"""
blocks = []
for m in re.finditer(r'(\$*)"""\n(.*?)\n([ \t]*)"""', src, re.S):
    indent = m.group(3)
    lines = m.group(2).split('\n')
    body = '\n'.join(l[len(indent):] if l.startswith(indent) else l.lstrip() for l in lines)
    # the C# member this belongs to: nearest preceding "string Name"
    head = src[:m.start()]
    name = re.findall(r'string (\w+)\s*(?:\(|=)', head)[-1]
    blocks.append((name, body))
count = {}
for name, body in blocks:
    count[name] = count.get(name, 0) + 1
    fn = name if count[name] == 1 else f"{name}{count[name]}"
    open(os.path.join(out, fn + '.sh'), 'w', encoding='utf-8', newline='\n').write(body + '\n')
    print(fn, len(body))
