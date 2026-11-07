import json
import sys
import time
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RUNTIME_DIR = ROOT / "engine/runtime/official"
SOURCE_MANIFEST = ROOT / "models/moss/tts/browser_poc_manifest.json"
TTS_META = ROOT / "models/moss/tts/tts_browser_onnx_meta.json"
CODEC_META = ROOT / "models/moss/codec/codec_browser_onnx_meta.json"
TOKENIZER = ROOT / "models/moss/tts/tokenizer.model"
TEST_DIR = ROOT / "scripts/moss_smoke_runtime"
OUTPUT = ROOT / "benchmarks/moss_junhao_smoke.wav"

for path in (SOURCE_MANIFEST, TTS_META, CODEC_META, TOKENIZER,
             RUNTIME_DIR / "ort_cpu_runtime.py"):
    if not path.is_file():
        raise FileNotFoundError(f"Required file missing: {path}")

TEST_DIR.mkdir(parents=True, exist_ok=True)
OUTPUT.parent.mkdir(parents=True, exist_ok=True)

manifest = json.loads(SOURCE_MANIFEST.read_text(encoding="utf-8"))
manifest["model_files"]["tts_meta"] = str(TTS_META.resolve())
manifest["model_files"]["codec_meta"] = str(CODEC_META.resolve())
manifest["model_files"]["tokenizer_model"] = str(TOKENIZER.resolve())

test_manifest = TEST_DIR / "browser_poc_manifest.json"
test_manifest.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2),
    encoding="utf-8"
)

sys.path.insert(0, str(RUNTIME_DIR))
from ort_cpu_runtime import OrtCpuRuntime

voice = next(v for v in manifest["builtin_voices"] if v["voice"] == "Junhao")
sample = next(s for s in manifest["text_samples"] if s["id"] == "zh_browser_poc")

print("===== MOSS SMOKE TEST =====")
print("Voice:", voice["voice"])
print("Text sample:", sample["id"])
print("Maximum frames: 8")
print("Execution provider: CPUExecutionProvider")

start = time.perf_counter()
runtime = OrtCpuRuntime(
    model_dir=TEST_DIR,
    thread_count=2,
    max_new_frames=8,
    do_sample=True,
    sample_mode="fixed",
)
init_time = time.perf_counter() - start
print(f"SESSION INITIALIZATION: {init_time:.2f} seconds")

request = runtime.build_voice_clone_request_rows(
    voice["prompt_audio_codes"],
    sample["text_token_ids"],
)

start = time.perf_counter()
frames = runtime.generate_audio_frames(request)
generation_time = time.perf_counter() - start

print("GENERATED FRAMES:", len(frames))
print(f"FRAME GENERATION: {generation_time:.2f} seconds")

if not frames:
    raise RuntimeError("No audio frames were generated.")

start = time.perf_counter()
channels, audio_length = runtime.decode_full_audio(frames)
decode_time = time.perf_counter() - start

if not channels or audio_length <= 0:
    raise RuntimeError("Audio decoder returned empty audio.")

if len(channels) != 2:
    raise RuntimeError(f"Expected stereo audio, received {len(channels)} channels.")

sample_rate = int(runtime.codec_meta["codec_config"]["sample_rate"])
audio = np.stack(channels, axis=1)
audio = np.nan_to_num(audio, nan=0.0, posinf=0.0, neginf=0.0)
audio = np.clip(audio, -1.0, 1.0)
pcm = np.round(audio * 32767.0).astype("<i2")

with wave.open(str(OUTPUT), "wb") as wav:
    wav.setnchannels(2)
    wav.setsampwidth(2)
    wav.setframerate(sample_rate)
    wav.writeframes(pcm.tobytes())

duration = len(audio) / sample_rate
peak = float(np.max(np.abs(audio)))
rms = float(np.sqrt(np.mean(np.square(audio))))

print(f"AUDIO DECODING: {decode_time:.2f} seconds")
print("SAMPLE RATE:", sample_rate)
print("CHANNELS:", len(channels))
print(f"AUDIO DURATION: {duration:.3f} seconds")
print(f"PEAK: {peak:.5f}")
print(f"RMS: {rms:.5f}")
print("WAV FILE:", OUTPUT)
print("FILE SIZE:", OUTPUT.stat().st_size, "bytes")
print("RESULT: AUDIO GENERATED")
