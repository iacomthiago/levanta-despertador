"""Gera os sons do app (rodar uma vez; os .wav ficam versionados).

passaros.wav: canto de pássaros com um fundo de vento leve, feito só com
    matemática aqui mesmo (nenhuma gravação de terceiros). Toca em laço depois
    da música. Substituiu o bipe agudo em 21/11/2026: o bipe, mesmo com o teto
    de volume, acordava a casa e atrapalhava responder a missão.
chime.wav: dois toques suaves, usados na checagem "você continua de pé?".
"""
import math
import os
import random
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def write(name, samples, rate=RATE):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples))
    print(name, f"{len(samples) / rate:.2f}s", os.path.getsize(path), "bytes")


def silence(seconds):
    return [0.0] * int(RATE * seconds)


# ---------- canto de pássaros ----------

PASSAROS_RATE = 22050
PASSAROS_SEGUNDOS = 24.0
PASSAROS_PICO = 0.5  # -6 dBFS: bem abaixo do bipe antigo, que batia no teto


def passaros():
    rate = PASSAROS_RATE
    n = int(PASSAROS_SEGUNDOS * rate)
    out = [0.0] * n
    rnd = random.Random(2026)

    def nota(inicio, dur, f0, f1, amp, vibrato=0.0, vib_hz=0.0, trinado_hz=0.0, curva=1.0):
        """Um assobio de f0 até f1 Hz, com entrada e saída suaves."""
        i0 = int(inicio * rate)
        m = max(1, int(dur * rate))
        fase = 0.0
        for k in range(m):
            i = i0 + k
            if i >= n:
                break
            x = k / m
            f = f0 + (f1 - f0) * (x ** curva)
            if vibrato:
                f *= 1 + vibrato * math.sin(2 * math.pi * vib_hz * k / rate)
            fase += 2 * math.pi * f / rate
            env = math.sin(math.pi * x) ** 1.5
            if trinado_hz:
                env *= 0.5 - 0.5 * math.cos(2 * math.pi * trinado_hz * k / rate)
            s = math.sin(fase)
            if 2 * f < rate * 0.45:
                s += 0.12 * math.sin(2 * fase)
            out[i] += amp * env * s

    fim = PASSAROS_SEGUNDOS - 1.6  # nada canta perto do fim: o laço emenda sem corte

    # Assobio melodioso, o pássaro mais perto: frases de 3 a 5 notas.
    t = 0.6
    while t < fim - 1.5:
        for _ in range(rnd.randint(3, 5)):
            dur = rnd.uniform(0.14, 0.32)
            f0 = rnd.uniform(1700, 3000)
            f1 = f0 * rnd.uniform(0.85, 1.2)
            nota(t, dur, f0, f1, 0.5, vibrato=0.012, vib_hz=6.5)
            t += dur + rnd.uniform(0.05, 0.12)
        t += rnd.uniform(3.2, 5.5)

    # Chilreio: série de piados rápidos que descem.
    t = 2.3
    while t < fim - 1.0:
        alto = rnd.uniform(4800, 5600)
        for _ in range(rnd.randint(4, 8)):
            nota(t, 0.05, alto, alto * 0.55, 0.3, curva=0.7)
            t += rnd.uniform(0.08, 0.1)
        t += rnd.uniform(4.0, 7.0)

    # Trinado de um pássaro mais longe.
    t = 4.8
    while t < fim - 1.4:
        dur = rnd.uniform(0.8, 1.3)
        f0 = rnd.uniform(3700, 4200)
        nota(t, dur, f0, f0 * 1.06, 0.2, trinado_hz=rnd.uniform(20, 26))
        t += dur + rnd.uniform(5.0, 8.0)

    # Chamado de dois tons, "fiii-uu".
    t = 7.5
    while t < fim - 0.8:
        nota(t, 0.22, 3250, 3400, 0.32)
        nota(t + 0.3, 0.3, 2550, 2250, 0.32)
        t += rnd.uniform(6.0, 9.0)

    # Normaliza os cantos.
    pico = max(abs(s) for s in out) or 1.0
    out = [s * PASSAROS_PICO / pico for s in out]

    # Fundo de vento leve: ruído abafado que sobe e desce devagar.
    # Gera um pouco a mais e funde o final no começo, para o laço não estalar.
    extra = int(1.5 * rate)
    a = math.exp(-2 * math.pi * 700 / rate)
    y = 0.0
    vento = []
    for i in range(n + extra):
        y = a * y + (1 - a) * rnd.uniform(-1, 1)
        onda = 0.6 + 0.4 * math.sin(2 * math.pi * i / (n / 2))  # 2 ondas por laço
        vento.append(y * onda)
    for i in range(extra):
        p = i / extra
        vento[i] = vento[i] * p + vento[n + i] * (1 - p)
    pv = max(abs(v) for v in vento[:n]) or 1.0
    return [out[i] + 0.05 * vento[i] / pv for i in range(n)]


# ---------- checagem ----------


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
    write("passaros.wav", passaros(), rate=PASSAROS_RATE)
    write("chime.wav", chime())
