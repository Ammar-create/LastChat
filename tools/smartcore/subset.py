import sys,json,struct; sys.argv=['x']; sys.path.insert(0,'tools')
import build_tok as B
G=json.load(open('goldens.json')); used=set(); uchars=set()
orig=B.mtab
class Rec(dict):
    def get(s,k,d=None):
        v=orig.get(k)
        if v is not None: used.add(k)
        return v
B.mtab=Rec()
P="[T:msg] <persona=off> <story=off> [target] "
for g in G:
    assert [2]+B.encode_raw(P+g['text'])[:254]+[1]==g['raw_ids']; uchars|=set(P+g['text'])
chars={s:i for s,i in B.chars.items() if s in uchars or s=='▁'}
mk=sorted((k,orig[k]) for k in used); kept=B.VM['kept_old_ids']
with open('/workspace/repos/LastChat/app/src/test/resources/smartcore/tokenizer_subset.bin','wb') as f:
    f.write(b'LCTK'); f.write(struct.pack('>iiiii',1,B.VM['unk_new_id'],len(chars),len(B.added),len(mk)))
    f.write(struct.pack('>256i',*B.byte_ids))
    for s,i in chars.items(): f.write(struct.pack('>ii',ord(s),i))
    for s,i in B.added.items(): b=s.encode(); f.write(struct.pack('>ii',i,len(b))); f.write(b)
    for k,(r,res) in mk: f.write(struct.pack('>qii',k,r,res))
    f.write(struct.pack('>ii',B.VM['old_vocab'],len(kept))); f.write(struct.pack(f'>{len(kept)}i',*kept))
print(len(mk),len(chars))
