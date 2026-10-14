import sys,json,numpy as np,time; sys.argv=['x']; sys.path.insert(0,'tools')
import build_tok as B, onnxruntime as ort
L=json.load(open('labels.json')); kept=B.VM['kept_old_ids']; rm={o:i for i,o in enumerate(kept)}
so=ort.SessionOptions(); so.intra_op_num_threads=4
s=ort.InferenceSession('decision.int8.onnx',so); names=[o.name for o in s.get_outputs()]
def enc(t):
    raw=[2]+B.encode_raw("[T:msg] <persona=off> <story=off> [target] "+t)[:254]+[1]
    ids=[rm.get(i,3) for i in raw]; n=len(ids); return raw,ids+[0]*(256-n),[1]*n+[0]*(256-n)
rows=[json.loads(l)['text'] for l in open('/workspace/lastchat-memory-model/data_v5/v5_real_eval_pool.jsonl')]
rows=[r for r in rows if 3<len(r)<100][:300]
extra=["thank you so much, this is amazing!!","ugh this is so frustrating, nothing works","I'm really sad today","wait what? how does that even work","OMG I got the job!!!","hey"]
found={}; gold=[]
for t in extra+rows:
    raw,i,m=enc(t); o=dict(zip(names,s.run(None,{'input_ids':np.array([i]),'attention_mask':np.array([m])})))
    e=L['heads']['emotion'][int(o['emotion'].argmax())]
    if t in extra or len(found.setdefault(e,[]))<2:
        found.setdefault(e,[]).append(t)
        gold.append({"text":t,"raw_ids":raw,"ids":i[:len(raw)],"logits":{k:o[k][0].tolist() for k in names if k!='span'}})
json.dump(gold,open('goldens.json','w'))
for k,v in found.items(): print(k,v)
f={'input_ids':np.array([i]),'attention_mask':np.array([m])}
for th in (1,4):
    so.intra_op_num_threads=th; s1=ort.InferenceSession('decision.int8.onnx',so); [s1.run(None,f) for _ in range(2)]
    t0=time.time(); [s1.run(None,f) for _ in range(5)]; print('ms',th,(time.time()-t0)*200)
