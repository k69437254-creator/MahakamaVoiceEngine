import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RUNTIME_DIR = ROOT / "engine/runtime/official"
TEST_DIR = ROOT / "scripts/moss_smoke_runtime"
SOURCE_MANIFEST = ROOT / "models/moss/tts/browser_poc_manifest.json"

manifest = json.loads(
    SOURCE_MANIFEST.read_text(encoding="utf-8")
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

TEST_DIR.mkdir(parents=True, exist_ok=True)

(TEST_DIR / "browser_poc_manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

sys.path.insert(0, str(RUNTIME_DIR))
from ort_cpu_runtime import OrtCpuRuntime


def memory_mb():
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    except Exception:
        pass
    return None


print("===== INITIALIZATION =====")
init_start = time.perf_counter()

runtime = OrtCpuRuntime(
    model_dir=TEST_DIR,
    thread_count=2,
    max_new_frames=8,
    do_sample=True,
    sample_mode="fixed",
)

init_time = time.perf_counter() - init_start

print(f"Session initialization: {init_time:.3f} s")
print("Session keys:", list(runtime.sessions.keys()))
print(f"Memory after initialization: {memory_mb():.1f} MB")

# Record every ONNX session.run call separately.
stats = {}


class TimedSession:
    def __init__(self, session, name, stats_dict):
        self._session = session
        self._name = name
        self._stats = stats_dict

    def __getattr__(self, name):
        return getattr(self._session, name)

    def run(self, *args, **kwargs):
        start = time.perf_counter()
        try:
            return self._session.run(*args, **kwargs)
        finally:
            elapsed = time.perf_counter() - start
            item = self._stats.setdefault(
                self._name,
                {"calls": []},
            )
            item["calls"].append(elapsed)


# Wrap all runtime sessions.
wrapped_by_id = {}

for name, session in list(runtime.sessions.items()):
    proxy = TimedSession(session, name, stats)
    wrapped_by_id[id(session)] = proxy
    runtime.sessions[name] = proxy

# Ensure the streaming decoder uses the same timed session.
stream = runtime.codec_streaming_session
stream_session_name = None
stream_stats_name = None

for attr, value in vars(stream).items():
    if hasattr(value, "run") and hasattr(value, "get_inputs"):
        original_id = id(value)
        proxy = wrapped_by_id.get(original_id)

        if proxy is None:
            proxy = TimedSession(
                value,
                "codec_streaming",
                stats,
            )
            wrapped_by_id[original_id] = proxy

        setattr(stream, attr, proxy)
        stream_session_name = attr
        stream_stats_name = proxy._name
        break

if stream_session_name is None:
    raise RuntimeError(
        "Could not locate the ONNX session inside "
        "CodecStreamingDecodeSession. Generation was not started."
    )

print("Streaming session attribute:", stream_session_name)
print("Streaming timing key:", stream_stats_name)

# Use the same temporary test voice, but only 24 reference frames.
voice = next(
    v for v in manifest["builtin_voices"]
    if v["voice"] == "Junhao"
)
sample = next(
    s for s in manifest["text_samples"]
    if s["id"] == "zh_browser_poc"
)

reference_count = 24

request = runtime.build_voice_clone_request_rows(
    voice["prompt_audio_codes"][:reference_count],
    sample["text_token_ids"],
)

runtime.rng = np.random.default_rng(1234)
stream.reset()

sample_rate = int(
    runtime.codec_meta["codec_config"]["sample_rate"]
)

pending = []
chunk_count = [0]
first_chunk_time = [None]
total_audio_samples = [0]

generation_start = time.perf_counter()


def decode_batch():
    batch = list(pending)
    pending.clear()

    result = stream.run_frames(batch)

    if result is None:
        raise RuntimeError(
            "Streaming decoder returned None."
        )

    audio, length = result
    audio = np.asarray(audio)

    if (
        audio.ndim != 3
        or audio.shape[0] != 1
        or audio.shape[1] != 2
    ):
        raise RuntimeError(
            f"Unexpected streaming output shape: {audio.shape}"
        )

    length = int(length)

    if length <= 0 or length > audio.shape[2]:
        raise RuntimeError(
            f"Invalid audio length: {length}"
        )

    total_audio_samples[0] += length
    chunk_count[0] += 1

    elapsed = time.perf_counter() - generation_start

    if first_chunk_time[0] is None:
        first_chunk_time[0] = elapsed

    print(
        f"STREAM CHUNK {chunk_count[0]} | "
        f"frames={len(batch)} | "
        f"samples/channel={length} | "
        f"since_start={elapsed:.3f} s"
    )


def on_frame(all_frames, step_index, frame):
    if len(frame) != 16:
        raise RuntimeError(
            f"Unexpected frame width: {len(frame)}"
        )

    pending.append([int(x) for x in frame])

    if len(pending) == 2:
        decode_batch()


print("\n===== GENERATION: REFERENCE 24 =====")
print("Voice: Junhao (temporary test only)")
print("Text: zh_browser_poc")
print("Reference frames:", reference_count)
print("Maximum generated frames: 8")
print("Thread count: 2")
print("Streaming batch size: 2")

frames = runtime.generate_audio_frames(
    request,
    on_frame=on_frame,
)

if pending:
    decode_batch()

generation_elapsed = (
    time.perf_counter() - generation_start
)

if not frames:
    raise RuntimeError("No frames were generated.")

if chunk_count[0] == 0:
    raise RuntimeError("No streaming chunks were produced.")

audio_duration = total_audio_samples[0] / sample_rate

print("\n===== GENERATION SUMMARY =====")
print("Generated frames:", len(frames))
print("Streaming chunks:", chunk_count[0])
print(
    f"First streaming output: "
    f"{first_chunk_time[0]:.4f} s"
)
print(
    f"Generation + streaming total: "
    f"{generation_elapsed:.4f} s"
)
print(f"Audio duration: {audio_duration:.3f} s")
print(
    f"RTF: {generation_elapsed / audio_duration:.2f}x"
)
print(f"Memory after generation: {memory_mb():.1f} MB")

# Print all individual ONNX call timings.
print("\n===== ONNX SESSION TIMINGS =====")

for name, item in stats.items():
    calls = item["calls"]
    total = sum(calls)
    average = total / len(calls)
    maximum = max(calls)

    print(
        f"{name}: calls={len(calls)} | "
        f"total={total:.4f} s | "
        f"average={average*1000:.2f} ms | "
        f"max={maximum*1000:.2f} ms"
    )

    if len(calls) > 1:
        print(
            "  Individual calls (ms):",
            [round(x * 1000, 2) for x in calls],
        )

# Match each sampler call with its corresponding TTS decode call.
sampler = stats.get("local_fixed_sampled_frame", {}).get("calls", [])
decoder = stats.get("decode", {}).get("calls", [])
prefill = stats.get("prefill", {}).get("calls", [])
codec = stats.get(stream_stats_name, {}).get("calls", [])

print("\n===== PER-FRAME BREAKDOWN =====")

paired_count = min(len(sampler), len(decoder))

for index in range(paired_count):
    sampler_ms = sampler[index] * 1000
    decoder_ms = decoder[index] * 1000

    print(
        f"Frame {index + 1:02d} | "
        f"sampler={sampler_ms:.2f} ms | "
        f"decode_step={decoder_ms:.2f} ms | "
        f"combined={sampler_ms + decoder_ms:.2f} ms"
    )

print("\n===== STAGE SUMMARY =====")

if prefill:
    print(f"Prefill: {sum(prefill):.4f} s")
else:
    print("Prefill: NOT RECORDED")

print(
    f"Frame sampler: {sum(sampler):.4f} s | "
    f"calls={len(sampler)}"
)
print(
    f"TTS decode_step: {sum(decoder):.4f} s | "
    f"calls={len(decoder)}"
)
print(
    f"Streaming codec: {sum(codec):.4f} s | "
    f"calls={len(codec)}"
)

measured_total = (
    sum(prefill)
    + sum(sampler)
    + sum(decoder)
    + sum(codec)
)

print(f"Measured ONNX total: {measured_total:.4f} s")
print(
    f"Unaccounted wall time: "
    f"{generation_elapsed - measured_total:.4f} s"
)

if sampler:
    print(
        f"Sampler average: "
        f"{np.mean(sampler)*1000:.2f} ms/frame"
    )

if decoder:
    print(
        f"Decode average: "
        f"{np.mean(decoder)*1000:.2f} ms/frame"
    )

if codec:
    print(
        f"Codec average: "
        f"{np.mean(codec)*1000:.2f} ms/chunk"
    )

if generation_elapsed > 0:
    print(
        f"Frame generation rate: "
        f"{len(frames)/generation_elapsed:.3f} frames/s"
    )

print("\nRESULT: REFERENCE 24 PERFORMANCE DIAGNOSTICS COMPLETED")
