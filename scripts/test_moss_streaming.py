import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RUNTIME_DIR = ROOT / "engine/runtime/official"
SOURCE_MANIFEST = ROOT / "models/moss/tts/browser_poc_manifest.json"
TEST_DIR = ROOT / "scripts/moss_smoke_runtime"

manifest = json.loads(SOURCE_MANIFEST.read_text(encoding="utf-8"))
manifest["model_files"]["tts_meta"] = str(
    (ROOT / "models/moss/tts/tts_browser_onnx_meta.json").resolve()
)
manifest["model_files"]["codec_meta"] = str(
    (ROOT / "models/moss/codec/codec_browser_onnx_meta.json").resolve()
)
manifest["model_files"]["tokenizer_model"] = str(
    (ROOT / "models/moss/tts/tokenizer.model").resolve()
)

TEST_DIR.mkdir(parents=True, exist_ok=True)
(TEST_DIR / "browser_poc_manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

sys.path.insert(0, str(RUNTIME_DIR))
from ort_cpu_runtime import OrtCpuRuntime

voice = next(v for v in manifest["builtin_voices"] if v["voice"] == "Junhao")
sample = next(s for s in manifest["text_samples"] if s["id"] == "zh_browser_poc")

print("===== STREAMING TEST =====")
print("Voice: Junhao (temporary test only)")
print("Maximum frames: 8")
print("Streaming batch size: 2")

runtime = OrtCpuRuntime(
    model_dir=TEST_DIR,
    thread_count=2,
    max_new_frames=8,
    do_sample=True,
    sample_mode="fixed",
)

request = runtime.build_voice_clone_request_rows(
    voice["prompt_audio_codes"],
    sample["text_token_ids"],
)

stream = runtime.codec_streaming_session
stream.reset()

pending = []
stream_results = []
callback_count = 0

def decode_batch():
    batch = list(pending)
    pending.clear()

    result = stream.run_frames(batch)
    if result is None:
        raise RuntimeError("Streaming decoder returned None.")

    audio, audio_length = result
    audio = np.asarray(audio)

    stream_results.append((audio.shape, int(audio_length)))

    print(
        f"STREAM CHUNK {len(stream_results)}: "
        f"input_frames={len(batch)}, "
        f"output_shape={audio.shape}, "
        f"audio_length={audio_length}"
    )

def on_frame(all_frames, step_index, frame):
    global callback_count
    callback_count += 1
    pending.append([int(x) for x in frame])

    if len(pending) == 2:
        decode_batch()

start = time.perf_counter()
frames = runtime.generate_audio_frames(
    request,
    on_frame=on_frame,
)
generation_time = time.perf_counter() - start

if pending:
    decode_batch()

print("\n===== STREAMING RESULTS =====")
print("Generated frames:", len(frames))
print("Callback count:", callback_count)
print("Streaming decoder calls:", len(stream_results))
print(f"Generation + streaming time: {generation_time:.2f} seconds")

if not frames:
    raise RuntimeError("No frames generated.")

if callback_count != len(frames):
    raise RuntimeError("Callback count does not match generated frames.")

if not stream_results:
    raise RuntimeError("No streaming decoder calls were made.")

print("\n===== FULL DECODE REFERENCE =====")
channels, full_length = runtime.decode_full_audio(frames)
print("Full decoder channels:", len(channels))
print("Full decoder samples per channel:", full_length)

print("\nRESULT: STREAMING PATH EXECUTED")
print("NOTE: Audio quality and playback latency are not established by this test.")
