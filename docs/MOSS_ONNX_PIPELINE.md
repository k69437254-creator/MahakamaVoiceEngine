# MOSS-TTS-Nano ONNX Pipeline

## Objective
Implement an offline Android TTS runtime with incremental audio generation.
Primary priority: minimize time to first playable audio.

## Verified model pipeline

### 1. Text and prompt preparation
- Encode text with the model's SentencePiece tokenizer.
- Build voice-cloning request rows using the model manifest.
- Prompt audio codes are part of the input sequence.

### 2. TTS prefill
Input:
- input_ids: int32 [1, sequence_length, n_vq + 1]
- attention_mask: int32 [1, sequence_length]

Outputs:
- global_hidden
- present KV-cache tensors described by TTS metadata.

### 3. Audio frame generation
The fixed sampled frame graph receives:
- global_hidden
- repetition_seen_mask
- assistant_random_u
- audio_random_u

Outputs:
- should_continue
- frame_token_ids

### 4. TTS decode step
Input:
- input_ids: int32 [1, 1, n_vq + 1]
- past_valid_lengths
- KV-cache tensors from the previous step

Outputs:
- next global_hidden
- updated KV-cache tensors

### 5. Streaming audio decoding
The codec streaming decoder receives:
- audio_codes: int32 [1, frame_count, num_quantizers]
- audio_code_lengths
- state tensors defined by codec metadata

It returns:
- audio tensor
- audio_lengths
- updated streaming state tensors

Streaming state must persist across decoder calls during one utterance
and must be reset between utterances.

## Android implementation requirements

- Use ONNX Runtime Android.
- Read graph names and tensor metadata from model metadata.
- Do not hardcode KV-cache tensor names or dimensions.
- Keep model sessions alive between synthesis requests.
- Release temporary tensors and results after use.
- Support cancellation and safe session release.
- Emit audio incrementally.
- Preserve stereo output; do not average channels to mono.
- Convert channel-major decoder output to interleaved PCM before AudioTrack playback.
- Keep model files outside the APK.

## Known limitations of the official Android example

- Uses pre-tokenized text IDs.
- Uses built-in voice prompt codes.
- Uses full audio decoding after frame generation.
- Does not implement incremental codec playback.
- Converts stereo output to mono.

It is a smoke-test example, not the final production engine.

## Performance measurements required

- Model initialization time.
- First generated frame latency.
- First decoded audio latency.
- First playable audio latency.
- Total generation time.
- Generated audio duration.
- Real-time factor.
- Peak memory usage.
- Playback continuity and underruns.
- Repeated-request stability.

No performance target is considered achieved until measured on the target Android device.
