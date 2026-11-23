#!/usr/bin/env python3
"""군대 기상나팔(alarm_reveille.wav) 생성 스크립트.

한국 군대에서 쓰는 기상나팔(미 육군 Reveille) 선율을 뷰글 음역(C4·E4·G4·C5·E5·G5)만으로
연주한 44.1kHz / 16bit / 모노 WAV를 만든다. 나팔 특유의 밝은 배음과 살짝 올라붙는
어택(피치 스쿱), 긴 음의 비브라토를 넣어 '힘찬 나팔 소리'로 들리게 했다.

출력: app/src/main/res/raw/alarm_reveille.wav
"""
import os
import struct
import wave

import numpy as np

SR = 44100
BPM = 112.0
BEAT = 60.0 / BPM

# (음이름, 길이(박)) — 전부 뷰글 하모닉(도·미·솔)만 쓴다.
MELODY = [
    ("G4", 0.5), ("C5", 0.5), ("E5", 0.5), ("C5", 0.5),
    ("G4", 0.5), ("C5", 0.5), ("E5", 1.5),
    ("E5", 0.5), ("E5", 0.5), ("E5", 0.5), ("E5", 0.5),
    ("C5", 0.5), ("E5", 0.5), ("G5", 1.5),
    ("E5", 0.5), ("C5", 0.5), ("G4", 1.0),
    ("G4", 0.5), ("C5", 0.5), ("E5", 0.5), ("C5", 0.5),
    ("E5", 0.5), ("C5", 0.5), ("G4", 2.0),
]

FREQ = {
    "C4": 261.63,
    "E4": 329.63,
    "G4": 392.00,
    "C5": 523.25,
    "E5": 659.25,
    "G5": 783.99,
}

# 나팔 배음 세기 (1배음 기준). 2~6배음을 살려 밝고 금속성 나게 한다.
HARMONICS = [1.0, 0.62, 0.38, 0.22, 0.12, 0.07, 0.04]


def note_samples(name, dur, sr=SR):
    n = int(round(dur * sr))
    t = np.arange(n) / sr
    f0 = FREQ[name]

    # 어택에서 살짝 아래에서 올라붙는 뷰글 특유의 스쿱 (약 -35센트 → 0)
    scoop_len = min(0.035, dur * 0.25)
    cents = -35.0 * np.exp(-t / (scoop_len / 2.2))
    pitch = f0 * (2.0 ** (cents / 1200.0))

    # 긴 음은 후반부에 비브라토
    vib_depth = 0.0 if dur < 0.75 else 0.0032
    vib = 1.0 + vib_depth * np.sin(2 * np.pi * 5.4 * t) * np.clip((t - 0.18) / 0.35, 0.0, 1.0)
    phase = 2 * np.pi * np.cumsum(pitch * vib) / sr

    wave_ = np.zeros(n)
    for i, amp in enumerate(HARMONICS, start=1):
        wave_ += amp * np.sin(i * phase)
    wave_ /= sum(HARMONICS)

    # 엔벨로프: 8ms 어택, 서스테인, 45ms 릴리스 + 살짝 크레셴도
    env = np.ones(n)
    atk = max(1, int(0.008 * sr))
    rel = max(1, int(0.045 * sr))
    env[:atk] = np.linspace(0.0, 1.0, atk) ** 1.4
    env[-rel:] = np.linspace(1.0, 0.0, rel) ** 1.6
    swell = 0.92 + 0.08 * np.clip(t / max(dur, 1e-6), 0.0, 1.0)
    wave_ *= env * swell

    # 음 사이 아주 짧은 숨(스타카토 느낌)
    gap = int(0.012 * sr)
    if gap < n:
        wave_[-gap:] *= np.linspace(1.0, 0.25, gap)
    return wave_


def build():
    total = int(round(sum(d for _, d in MELODY) * BEAT * SR)) + int(0.45 * SR)
    buf = np.zeros(total)

    pos = int(0.06 * SR)
    for name, beats in MELODY:
        seg = note_samples(name, beats * BEAT)
        buf[pos:pos + len(seg)] += seg
        pos += len(seg)

    # 짧은 홀 리버브 (60ms / 118ms 두 갈래)
    for delay_ms, gain in ((58, 0.16), (121, 0.09)):
        d = int(delay_ms * SR / 1000)
        buf[d:] += buf[:-d] * gain

    # 살짝 하이패스 느낌으로 저역 럼블 제거
    b = np.copy(buf)
    for _ in range(2):
        b[1:] = b[1:] - 0.28 * b[:-1]
    buf = b

    # 페이드 인/아웃 + 피크 정규화
    fade = int(0.006 * SR)
    buf[:fade] *= np.linspace(0.0, 1.0, fade)
    buf[-fade:] *= np.linspace(1.0, 0.0, fade)
    peak = float(np.max(np.abs(buf))) or 1.0
    buf = buf / peak * 0.89
    return buf


def write_wav(path, data):
    pcm = np.clip(data, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return os.path.getsize(path)


if __name__ == "__main__":
    out = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "res", "raw", "alarm_reveille.wav",
    )
    data = build()
    size = write_wav(out, data)
    print("wrote", out, size, "bytes,", round(len(data) / SR, 2), "s")
