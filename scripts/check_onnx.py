from pathlib import Path

ROOT = Path("models/moss")
failed = False

def varint(b, p):
    v = 0
    s = 0
    while p < len(b):
        x = b[p]
        p += 1
        v |= (x & 127) << s
        if x < 128:
            return v, p
        s += 7
        if s >= 70:
            raise ValueError("Invalid varint")
    raise ValueError("Truncated varint")

def fields(b):
    p = 0
    while p < len(b):
        tag, p = varint(b, p)
        n, w = tag >> 3, tag & 7
        if not n:
            raise ValueError("Invalid field number")
        if w == 0:
            v, p = varint(b, p)
        elif w == 1:
            v = b[p:p+8]
            p += 8
        elif w == 2:
            size, p = varint(b, p)
            v = b[p:p+size]
            if len(v) != size:
                raise ValueError("Truncated field")
            p += size
        elif w == 5:
            v = b[p:p+4]
            p += 4
        else:
            raise ValueError("Unsupported wire type")
        if p > len(b):
            raise ValueError("Field overflow")
        yield n, w, v

def inspect(path):
    data = path.read_bytes()
    graph = next(
        (v for n,w,v in fields(data) if n == 7 and w == 2),
        None
    )
    if graph is None:
        raise ValueError("Graph not found")

    tensors = [
        v for n,w,v in fields(graph) if n == 5 and w == 2
    ]
    refs = []

    for tensor in tensors:
        entries = {}
        for n,w,v in fields(tensor):
            if n == 13 and w == 2:
                pair = {}
                for a,b,c in fields(v):
                    if b == 2 and a in (1,2):
                        pair[a] = c.decode("utf-8")
                if 1 in pair and 2 in pair:
                    entries[pair[1]] = pair[2]
        if "location" in entries:
            refs.append(entries)

    print(f"\nMODEL: {path.name}")
    print("PROTOBUF: READABLE")
    print("INITIALIZERS:", len(tensors))
    print("EXTERNAL TENSORS:", len(refs))

    for ref in refs:
        loc = ref["location"]
        target = (path.parent / loc).resolve()
        base = path.parent.resolve()

        if base not in target.parents:
            raise ValueError("External path escapes model directory")

        if not target.is_file():
            raise ValueError("Missing external file: " + loc)

        size = target.stat().st_size
        offset = int(ref.get("offset", "0"))
        length = ref.get("length")

        if offset < 0 or offset > size:
            raise ValueError("Invalid external offset: " + loc)

        if length is not None:
            length = int(length)
            if length < 0 or offset + length > size:
                raise ValueError("Invalid external range: " + loc)

        print("OK:", loc, "size=", size,
              "offset=", offset, "length=", length or "unspecified")

models = [
    ROOT / "tts" / f for f in [
        "moss_tts_prefill.onnx",
        "moss_tts_decode_step.onnx",
        "moss_tts_local_decoder.onnx",
        "moss_tts_local_cached_step.onnx",
        "moss_tts_local_fixed_sampled_frame.onnx",
    ]
] + [
    ROOT / "codec" / f for f in [
        "moss_audio_tokenizer_encode.onnx",
        "moss_audio_tokenizer_decode_full.onnx",
        "moss_audio_tokenizer_decode_step.onnx",
    ]
]

for model in models:
    try:
        inspect(model)
    except Exception as e:
        failed = True
        print("\nFAIL:", model.name, str(e))

print("\nRESULT:", "FAILED" if failed else "STRUCTURAL CHECK PASSED")
print("This check does not execute ONNX operators.")
