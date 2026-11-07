import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "engine/runtime/official"))

from ort_cpu_runtime import OrtCpuRuntime

# Load the existing manifest without modifying the original.
manifest = json.loads(
    (ROOT / "models/moss/tts/browser_poc_manifest.json").read_text(
        encoding="utf-8"
    )
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
    json.dumps(manifest, ensure_ascii=False),
    encoding="utf-8",
)

voice = next(
    v for v in manifest["builtin_voices"]
    if v["voice"] == "Junhao"
)
sample = next(
    s for s in manifest["text_samples"]
    if s["id"] == "zh_browser_poc"
)

print("===== REPEATED STREAMING BENCHMARK =====")
print("Voice: Junhao (temporary test only)")
print("Text: zh_browser_poc")
print("Thread count: 2")
print("Maximum generated frames: 8")
print("Streaming batch size: 2")
print("Repeats per reference length: 3")
print("Reference lengths: 98, 48, 24")

init_start = time.perf_counter()

runtime = OrtCpuRuntime(
    model_dir=test_dir,
    thread_count=2,
    max_new_frames=8,
    do_sample=True,
    sample_mode="fixed",
)

init_time = time.perf_counter() - init_start
stream = runtime.codec_streaming_session
sample_rate = int(runtime.codec_meta["codec_config"]["sample_rate"])

print(f"Session initialization: {init_time:.2f} s")
print(f"Sample rate: {sample_rate}")
print("\nSession initialized. Starting measurements...")

results = []

for reference_count in (98, 48, 24):
    print(f"\n{'=' * 12} REFERENCE: {reference_count} {'=' * 12}")

    for trial in range(1, 4):
        print(f"\n--- TRIAL {trial}/3 ---")

        request = runtime.build_voice_clone_request_rows(
            voice["prompt_audio_codes"][:reference_count],
            sample["text_token_ids"],
        )

        stream.reset()
        runtime.rng = np.random.default_rng(1234)

        pending = []
        chunk_end_times = []
        chunk_decode_times = []
        total_audio_samples = [0]
        callback_count = [0]
        first_chunk_latency = [None]

        start = time.perf_counter()

        def decode_batch():
            batch = list(pending)
            pending.clear()

            call_start = time.perf_counter()
            result = stream.run_frames(batch)
            call_end = time.perf_counter()

            if result is None:
                raise RuntimeError(
                    "Streaming decoder returned None."
                )

            audio, audio_length = result
            audio = np.asarray(audio)

            if (
                audio.ndim != 3
                or audio.shape[0] != 1
                or audio.shape[1] != 2
            ):
                raise RuntimeError(
                    f"Unexpected audio shape: {audio.shape}"
                )

            audio_length = int(audio_length)

            if audio_length <= 0 or audio_length > audio.shape[2]:
                raise RuntimeError(
                    f"Invalid audio length: {audio_length}"
                )

            total_audio_samples[0] += audio_length
            chunk_end_times.append(call_end)
            chunk_decode_times.append(call_end - call_start)

            if first_chunk_latency[0] is None:
                first_chunk_latency[0] = call_end - start

            print(
                f"CHUNK {len(chunk_end_times)} | "
                f"frames={len(batch)} | "
                f"samples/channel={audio_length} | "
                f"decode={(call_end-call_start)*1000:.1f} ms | "
                f"since_start={call_end-start:.3f} s"
            )

        def on_frame(all_frames, step_index, frame):
            if len(frame) != 16:
                raise RuntimeError(
                    f"Unexpected frame width: {len(frame)}"
                )

            pending.append([int(x) for x in frame])
            callback_count[0] += 1

            if len(pending) == 2:
                decode_batch()

        frames = runtime.generate_audio_frames(
            request,
            on_frame=on_frame,
        )

        if pending:
            decode_batch()

        end = time.perf_counter()
        total_time = end - start

        if not frames:
            raise RuntimeError("No frames generated.")

        if callback_count[0] != len(frames):
            raise RuntimeError(
                f"Callback mismatch: "
                f"{callback_count[0]} vs {len(frames)}"
            )

        if not chunk_end_times:
            raise RuntimeError("No streaming chunks were produced.")

        intervals = [
            (chunk_end_times[i] - chunk_end_times[i - 1]) * 1000
            for i in range(1, len(chunk_end_times))
        ]

        audio_duration = total_audio_samples[0] / sample_rate

        if audio_duration <= 0:
            raise RuntimeError("Invalid generated audio duration.")

        rtf = total_time / audio_duration

        result = {
            "reference": reference_count,
            "trial": trial,
            "frames": len(frames),
            "chunks": len(chunk_end_times),
            "first_chunk": first_chunk_latency[0],
            "total_time": total_time,
            "audio_duration": audio_duration,
            "rtf": rtf,
            "average_chunk_decode": float(np.mean(chunk_decode_times)),
            "average_interval": (
                float(np.mean(intervals)) if intervals else 0.0
            ),
        }

        results.append(result)

        print("\nTRIAL SUMMARY")
        print("Generated frames:", len(frames))
        print("Streaming chunks:", len(chunk_end_times))
        print(f"First chunk latency: {result['first_chunk']:.4f} s")
        print(f"Total generation + streaming: {total_time:.4f} s")
        print(f"Audio duration: {audio_duration:.3f} s")
        print(f"RTF: {rtf:.2f}x")
        print(
            f"Average chunk decode: "
            f"{result['average_chunk_decode']:.3f} s"
        )
        print(
            f"Average chunk interval: "
            f"{result['average_interval']:.1f} ms"
        )

print("\n===== FINAL STATISTICAL COMPARISON =====")
print(
    "Reference | First chunk mean±SD | "
    "Total mean±SD | RTF mean±SD"
)

for reference_count in (98, 48, 24):
    group = [
        item for item in results
        if item["reference"] == reference_count
    ]

    first = np.array([x["first_chunk"] for x in group])
    total = np.array([x["total_time"] for x in group])
    rtf_values = np.array([x["rtf"] for x in group])
    intervals = np.array([x["average_interval"] for x in group])
    chunk_decode = np.array(
        [x["average_chunk_decode"] for x in group]
    )

    print(
        f"{reference_count:>9} | "
        f"{np.mean(first):.3f} ± {np.std(first):.3f} s | "
        f"{np.mean(total):.3f} ± {np.std(total):.3f} s | "
        f"{np.mean(rtf_values):.2f} ± {np.std(rtf_values):.2f}x"
    )

    print(
        f"          Average chunk interval: "
        f"{np.mean(intervals):.1f} ms | "
        f"Average chunk decode: {np.mean(chunk_decode):.3f} s"
    )

print("\n===== INDIVIDUAL TRIALS =====")
for item in results:
    print(
        f"Ref={item['reference']:>2} | "
        f"Trial={item['trial']} | "
        f"First={item['first_chunk']:.3f}s | "
        f"Total={item['total_time']:.3f}s | "
        f"RTF={item['rtf']:.2f}x"
    )

print("\nRESULT: REPEATED STREAMING BENCHMARK COMPLETED")
