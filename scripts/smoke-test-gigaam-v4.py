#!/usr/bin/env python3
"""Linux CPU smoke test of the packaged GigaAM v4 files and Silero VAD.

Install sherpa-onnx==1.13.0 and numpy, run scripts/download-model.sh, then:
    python3 scripts/smoke-test-gigaam-v4.py
Additional 16 kHz mono PCM16 WAVs can be passed with repeated --audio flags.
This is not an Android/device test or a speech-quality benchmark.
"""

import argparse
from pathlib import Path
import time
import wave

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parent.parent
PREFIX = "gigaam_v4_e2e_rnnt_"
SAMPLE_RATE = 16000


def load_wav(path):
    with wave.open(str(path), "rb") as src:
        if (src.getnchannels(), src.getsampwidth(), src.getframerate()) != (1, 2, SAMPLE_RATE):
            raise ValueError(f"{path}: expected mono, PCM16, 16 kHz WAV")
        return np.frombuffer(src.readframes(src.getnframes()), dtype="<i2").astype(np.float32) / 32768


def recognize(recognizer, samples):
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples)
    started = time.monotonic()
    recognizer.decode_stream(stream)
    return stream.result.text.strip(), time.monotonic() - started


def segments(samples):
    silero = sherpa_onnx.SileroVadModelConfig(
        model=str(ROOT / "app/src/main/assets/silero_vad.onnx"),
        threshold=0.5,
        min_silence_duration=0.5,
        min_speech_duration=0.25,
        window_size=512,
        max_speech_duration=30.0,
    )
    detector = sherpa_onnx.VoiceActivityDetector(
        sherpa_onnx.VadModelConfig(silero_vad=silero, sample_rate=SAMPLE_RATE, num_threads=1),
        buffer_size_in_seconds=60,
    )
    result = []
    for start in range(0, len(samples), 512):
        chunk = samples[start : start + 512]
        if len(chunk) != 512:
            chunk = np.pad(chunk, (0, 512 - len(chunk)))
        detector.accept_waveform(chunk)
        while not detector.empty():
            result.append(np.array(detector.front.samples, dtype=np.float32, copy=True))
            detector.pop()
    detector.flush()
    while not detector.empty():
        result.append(np.array(detector.front.samples, dtype=np.float32, copy=True))
        detector.pop()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, default=ROOT / "models/gigaam-v4")
    parser.add_argument("--audio", type=Path, action="append", default=[])
    args = parser.parse_args()

    paths = {name: str(args.model_dir / (PREFIX + suffix)) for name, suffix in {
        "encoder": "encoder_int8.onnx", "decoder": "decoder.onnx",
        "joiner": "joint.onnx", "tokens": "tokens.txt",
    }.items()}
    recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
        **paths, num_threads=2, sample_rate=SAMPLE_RATE, feature_dim=64,
        model_type="nemo_transducer", provider="cpu",
    )
    print(f"sherpa-onnx {sherpa_onnx.__version__}, CPU")
    silence = np.zeros(SAMPLE_RATE * 2, dtype=np.float32)
    text, _ = recognize(recognizer, silence)
    assert text == "", f"silence unexpectedly produced {text!r}"
    assert not segments(silence), "VAD unexpectedly detected speech in silence"
    print("silence: no transcript and no VAD segments")

    sample = ROOT / "app/src/main/assets/benchmark.wav"
    for audio_path in [sample, *args.audio]:
        audio = load_wav(audio_path)
        text, elapsed = recognize(recognizer, audio)
        if not text:
            raise AssertionError(f"{audio_path}: speech transcript is empty")
        print(f"{audio_path.name} ({len(audio) / SAMPLE_RATE:.2f}s; decode {elapsed:.2f}s): {text}")
        if audio_path == sample:
            found = segments(np.concatenate((audio, silence)))
            assert found, "Silero did not detect speech in benchmark.wav"
            segment_texts = [recognize(recognizer, s)[0] for s in found]
            assert any(segment_texts), "VAD-detected speech yielded no text"
            print(f"Silero: {len(found)} segment(s), nonempty ASR: {sum(bool(t) for t in segment_texts)}")
    print("PASS: offline recognition and VAD smoke test (not Android validation)")


if __name__ == "__main__":
    main()
