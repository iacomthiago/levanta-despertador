"""Gera os dois sons do app (rodar uma vez; os .wav ficam versionados).

alarm.wav: bipes agudos e insistentes, feitos para acordar.
chime.wav: dois toques suaves, usados na checagem "você continua de pé?".
"""
import math
import os
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples))
    print(name, f"{len(samples) / RATE:.2f}s", os.path.getsize(path), "bytes")


def tone(freqs, seconds, attack=0.004, release=0.01, shape=1.0):
    n = int(RATE * seconds)
    out = []
    for i in range(n):
        t = i / RATE
        s = sum(math.sin(2 * math.pi * f * t) for f in freqs) / len(freqs)
        # "shape" > 1 achata a onda e deixa o timbre mais áspero e alto
        s = math.tanh(s * shape) / math.tanh(shape)
        env = min(1.0, t / attack, (seconds - t) / release)
        out.append(s * env)
    return out


def silence(seconds):
    return [0.0] * int(RATE * seconds)


def alarm():
    beep = tone([1960, 2940], 0.085, shape=3.0)
    gap = silence(0.07)
    burst = (beep + gap) * 4
    return [s * 0.95 for s in burst + silence(0.38)]


def chime():
    def bell(freq, seconds):
        n = int(RATE * seconds)
        return [
            (math.sin(2 * math.pi * freq * i / RATE) + 0.35 * math.sin(2 * math.pi * freq * 2 * i / RATE))
            / 1.35
            * math.exp(-3.2 * i / RATE)
            * min(1.0, i / (RATE * 0.005))
            for i in range(n)
        ]

    a = bell(784, 0.55)
    b = bell(1046.5, 1.1)
    return [s * 0.8 for s in a + b + silence(1.1)]


if __name__ == "__main__":
    write("alarm.wav", alarm())
    write("chime.wav", chime())
