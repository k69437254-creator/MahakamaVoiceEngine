import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RUNTIME_DIR = ROOT / "engine/runtime/official"
TEST_DIR = ROOT / "scripts/moss_smoke_runtime"
SOURCE_MANIFEST = ROOT / "models/moss/tts/browser_poc_manifest.json"

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
stream_audio = []
chunk_times = []
generation_start = time.perf_counter()
first_chunk_latency = None

def decode_batch():
    global first_chunk_latency

    batch = list(pending)
    pending.clear()

    call_start = time.perf_counter()
    result = stream.run_frames(batch)
    call_end = time.perf_counter()

    if result is None:
        raise RuntimeError("Streaming decoder returned None.")

    audio, audio_length = result
    audio = np.asarray(audio)

    if audio.ndim != 3 or audio.shape[0] != 1 or audio.shape[1] != 2:
        raise RuntimeError(f"Unexpected streaming audio shape: {audio.shape}")

    audio_length = int(audio_length)
    if audio_length <= 0 or audio_length > audio.shape[2]:
        raise RuntimeError(f"Invalid streaming audio length: {audio_length}")

    chunk = audio[0, :, :audio_length].copy()
    stream_audio.append(chunk)
    chunk_times.append(call_end)

    if first_chunk_latency is None:
        first_chunk_latency = call_end - generation_start

    print(
        f"CHUNK {len(stream_audio)} | "
        f"frames={len(batch)} | "
        f"samples/channel={audio_length} | "
        f"decode={(call_end-call_start)*1000:.1f} ms | "
        f"since_start={(call_end-generation_start):.3f} s"
    )

def on_frame(all_frames, step_index, frame):
    pending.append([int(x) for x in frame])
    if len(pending) == 2:
        decode_batch()

frames = runtime.generate_audio_frames(
    request,
    on_frame=on_frame,
)
generation_end = time.perf_counter()

if pending:
    decode_batch()

if not frames or not stream_audio:
    raise RuntimeError("No generated frames or streaming audio.")

stream_result = np.concatenate(stream_audio, axis=1)

full_channels, full_length = runtime.decode_full_audio(frames)
full_result = np.stack(full_channels, axis=0).astype(np.float32, copy=False)

print("\n===== TIMING =====")
print("Generated frames:", len(frames))
print("Streaming chunks:", len(stream_audio))
print(f"First chunk latency: {first_chunk_latency:.4f} s")
print(f"Generation + streaming: {generation_end-generation_start:.4f} s")

if len(chunk_times) > 1:
    intervals = np.diff(chunk_times) * 1000
    print("Chunk intervals (ms):", [round(float(x), 1) for x in intervals])
    print(f"Average chunk interval: {np.mean(intervals):.1f} ms")

print("\n===== AUDIO COMPARISON =====")
print("Streaming shape:", stream_result.shape)
print("Full decode shape:", full_result.shape)
print("Full decoder length:", full_length)

if stream_result.shape != full_result.shape:
    print("COMPARISON: LENGTH/SHAPE MISMATCH")
else:
    difference = stream_result - full_result
    max_abs = float(np.max(np.abs(difference)))
    mean_abs = float(np.mean(np.abs(difference)))
    rms_diff = float(np.sqrt(np.mean(np.square(difference))))

    print(f"Maximum absolute difference: {max_abs:.8f}")
    print(f"Mean absolute difference: {mean_abs:.8f}")
    print(f"Difference RMS: {rms_diff:.8f}")

    print("EXACT MATCH:", bool(np.array_equal(stream_result, full_result)))

print("\nRESULT: COMPARISON COMPLETED")
