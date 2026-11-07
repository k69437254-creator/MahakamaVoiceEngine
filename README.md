# Mahakama Voice Engine

An independent, offline-first Android neural text-to-speech engine.

## Initial architecture

- Kotlin engine API independent of any specific model.
- ONNX Runtime backend.
- Arabic text preprocessing and tokenizer integration.
- Incremental audio decoding and streaming playback.
- Pluggable model and voice management.
- On-device performance metrics.

## Initial model target

MOSS-TTS-Nano ONNX.

The model is not bundled in the application. Model files will be managed separately.

## Performance priorities

1. First-audio latency.
2. Real-time factor below 1 where device performance permits.
3. Continuous streaming without playback gaps.
4. Stable repeated synthesis.
5. Offline operation after model installation.

No performance target is considered achieved until measured on the target device.
