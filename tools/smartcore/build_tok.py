"""Convert HF tokenizer.json (mmBERT/Gemma BPE) + vocab_map.json into compact decision_tokenizer.bin, plus a reference encoder."""
import json, struct, sys
T = json.load(open(sys.argv[1] if len(sys.argv)>1 else '/workspace/v5export/tokenizer.json'))
VM = json.load(open('/workspace/v5export/vocab_map.json'))
vocab = T['model']['vocab']; inv = {i:s for s,i in vocab.items()}
merges = T['model']['merges']
added = {x['content']: x['id'] for x in T['added_tokens']}
chars = {s:i for s,i in vocab.items() if len(s)==1}
byte_ids = [vocab.get(f'<0x{b:02X}>',-1) for b in range(256)]
mtab = {}
for r,(a,b) in enumerate(merges):
    if isinstance(a,str) and ' ' in a and b is None: a,b=a.split(' ')
    ia, ib = vocab[a], vocab[b]; res = vocab[a+b]
    k = (ia<<32)|ib
    if k not in mtab: mtab[k]=(r,res)
UNK = vocab['<unk>']
def bpe(word):
    syms=[]; fuse=[False]
    for ch in word:
        if ch in chars: syms.append(chars[ch])
        else:
            bs=[byte_ids[b] for b in ch.encode('utf-8')]
            if -1 in bs:
                if not (syms and syms[-1]==UNK and fuse[0]): syms.append(UNK)
                fuse[0]=True; continue
            syms.extend(bs)
        fuse[0]=False
    while len(syms)>1:
        best=None
        for i in range(len(syms)-1):
            m=mtab.get((syms[i]<<32)|syms[i+1])
            if m and (best is None or m[0]<best[0]): best=(m[0],i,m[1])
        if not best: break
        _,i,res=best; syms[i:i+2]=[res]
    return syms
addl = sorted(added, key=len, reverse=True)
def encode_raw(text):
    out=[]; seg=''; i=0
    def flush(s):
        if not s: return
        s=s.replace(' ','▁')
        if not s.startswith('▁'): s='▁'+s
        words=[]; cur=''
        for c in s:
            if c=='▁' and cur: words.append(cur); cur=''
            cur+=c
        if cur: words.append(cur)
        for w in words: out.extend(bpe(w))
    while i<len(text):
        m=None
        for a in addl:
            if text.startswith(a,i): m=a; break
        if m: flush(seg); seg=''; out.append(added[m]); i+=len(m)
        else: seg+=text[i]; i+=1
    flush(seg); return out
if __name__=='__main__':
    kept = VM['kept_old_ids']
    mk = sorted(mtab.items())
    with open('/workspace/v5export/decision_tokenizer.bin','wb') as f:
        f.write(b'LCTK'); f.write(struct.pack('>iiiii', 1, VM['unk_new_id'], len(chars), len(added), len(mk)))
        f.write(struct.pack('>256i', *byte_ids))
        for s,i in chars.items(): f.write(struct.pack('>ii', ord(s), i))
        for s,i in added.items(): b=s.encode(); f.write(struct.pack('>ii', i, len(b))); f.write(b)
        for k,(r,res) in mk: f.write(struct.pack('>qii', k, r, res))
        f.write(struct.pack('>ii', VM['old_vocab'], len(kept))); f.write(struct.pack(f'>{len(kept)}i', *kept))
    print('chars',len(chars),'added',len(added),'merges',len(mk))
