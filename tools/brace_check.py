import sys
path = sys.argv[1]
f = open(path, encoding='utf-8').read().split('\n')
stack = []
s = 0
for ln, line in enumerate(f, 1):
    i = 0
    while i < len(line):
        c = line[i]
        if s == 1:
            break
        elif s == 2:
            if line[i:i+2] == '*/':
                s = 0
                i += 2
                continue
        elif s == 3:
            if c == '\\':
                i += 2
                continue
            if c == '"':
                s = 0
        elif s == 4:
            s = 0
        else:
            if line[i:i+2] == '//':
                s = 1
                break
            if line[i:i+2] == '/*':
                s = 2
                i += 2
                continue
            if c == '"':
                s = 3
            elif c == "'":
                s = 4
            elif c == '{':
                stack.append(ln)
            elif c == '}':
                if stack:
                    stack.pop()
                else:
                    print('extra } at', ln)
        i += 1
    if s in (1, 4):
        s = 0
print('unclosed { at lines:', stack[:12])
