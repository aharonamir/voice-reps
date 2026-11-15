#!/usr/bin/env python3
"""Compile the pinned tokenizer.json into the exact binary read by EmotionTokenizer.
Usage: python tools/compile_emotion_tokenizer.py /path/to/tokenizer.json
Fetch tokenizer.json from:
https://huggingface.co/Horizon-Labs/multilingual-emotions-small/resolve/ac26dadbdafc26276e01093790104679bf33aec5/tokenizer.json
No model inference or user text leaves the device.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys

source = Path(sys.argv[1]).read_bytes()
# Reject a newer/different tokenizer, even if the repository's main branch moves.
EXPECTED = '4379fe7f4a7af10b1ac15ffc8ae90f446f92082393699e9dd54b8c3b5ec0abad'
assert hashlib.sha256(source).hexdigest() == EXPECTED, 'Not the pinned tokenizer revision'
j = json.loads(source)
vocab = j['model']['vocab']
pairs = sorted(((vocab[a] << 32) | vocab[b], rank, vocab[a + b])
               for rank, (a, b) in enumerate(j['model']['merges']))
out = Path(__file__).resolve().parent.parent / 'app/src/main/assets/emotion-tokenizer.bin'
with out.open('wb') as f:
    def integer(x): f.write(struct.pack('>i', x))
    def string(x):
        data = x.encode('utf-8'); integer(len(data)); f.write(data)
    integer(len(vocab))
    for token, _ in sorted(vocab.items(), key=lambda x: x[1]): string(token)
    integer(len(pairs))
    for key, rank, merged in pairs: f.write(struct.pack('>qii', key, rank, merged))
    integer(len(j['added_tokens']))
    for token in j['added_tokens']:
        string(token['content']); integer(token['id'])
        integer(int(token['lstrip'])); integer(int(token['rstrip']))
print(out, out.stat().st_size, 'bytes')
