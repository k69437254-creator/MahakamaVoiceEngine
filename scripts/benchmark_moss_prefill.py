import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "engine/runtime/official"))

from ort_cpu_runtime import (
    OrtCpuRuntime,
    _flatten2d_int32,
    _flatten3d_int32,
)

manifest = json.loads(
    (ROOT / "models/moss/tts/browser_poc_manifest.json").read_text()
)

manifest["model_files"]["tts_meta"] = str(
    (ROOT / "models/moss/tts/tts_browser_onnx_meta.json").resolve()
)
manifest["model_files"]["codec_meta"] = str(
    (ROOT / "models/moss/codec/codec_browser_onnx_meta.json").resolve()
)
manifest["model_files"]["tokenizer_model"] = str(
    (ROOT / "models/moss/tts/tokenizer.model").resolve()
)

test_dir = ROOT / "scripts/moss_smoke_runtime"
test_dir.mkdir(parents=True, exist_ok=True)
(test_dir / "browser_poc_manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False)
)

voice = next(v for v in manifest["builtin_voices"] if v["voice"] == "Junhao")
sample = next(s for s in manifest["text_samples"] if s["id"] == "zh_browser_poc")

runtime = OrtCpuRuntime(
    model_dir=test_dir,
    thread_count=2,
    max_new_frames=8,
    do_sample=True,
    sample_mode="fixed",
)

print("===== PREFILL LENGTH BENCHMARK =====")
print("Reference frames available:", len(voice["prompt_audio_codes"]))

for count in (98, 48, 24):
    codes = voice["prompt_audio_codes"][:count]

    request = runtime.build_voice_clone_request_rows(
        codes,
        sample["text_token_ids"],
    )

    ids, ids_dims = _flatten3d_int32([request["inputIds"]])
    mask, mask_dims = _flatten2d_int32(request["attentionMask"])

    feeds = {
        "input_ids": ids.reshape(ids_dims),
        "attention_mask": mask.reshape(mask_dims),
    }

    sequence_length = ids_dims[1]

    # One warm-up call, excluded from measurements.
    runtime.sessions["prefill"].run(None, feeds)

    measurements = []

    for _ in range(3):
        start = time.perf_counter()
        runtime.sessions["prefill"].run(None, feeds)
        measurements.append(time.perf_counter() - start)

    print(
        f"REFERENCE={count} | "
        f"SEQUENCE={sequence_length} | "
        f"TIMES={[round(x, 4) for x in measurements]} | "
        f"AVERAGE={np.mean(measurements):.4f} s"
    )

print("\nRESULT: PREFILL BENCHMARK COMPLETED")
