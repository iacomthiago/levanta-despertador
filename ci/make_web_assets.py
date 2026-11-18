"""Gera os ícones do site (web/icon-*.png) e copia a imagem do personagem.

O desenho é o mesmo do ícone do app (ic_launcher_foreground.xml e
ic_launcher_background.xml): três divisas subindo sobre o degradê roxo.
Só usa a biblioteca padrão do Python.

Uso: python ci/make_web_assets.py
"""
import os
import shutil
import struct
import zlib

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(RAIZ, "web")

# Divisas no quadro de 108 do ícone do app, de baixo para cima, com a cor de cada uma.
DIVISAS = [
    ((35, 76), (54, 62), (73, 76), (0xA7, 0x7B, 0xFF)),
    ((35, 62), (54, 48), (73, 62), (0xD6, 0xC4, 0xFF)),
    ((35, 48), (54, 34), (73, 48), (0xF2, 0xF3, 0xF8)),
]
ESPESSURA = 6.5
COR_INICIO = (0x7B, 0x2D, 0xFF)  # alto à esquerda
COR_FIM = (0x2A, 0x0A, 0x78)  # embaixo à direita


def longo(caminho):
    """No Windows, caminhos com mais de 260 letras só abrem com este prefixo."""
    caminho = os.path.abspath(caminho)
    if os.name == "nt" and not caminho.startswith("\\\\?\\"):
        return "\\\\?\\" + caminho
    return caminho


def no_quadro(ponto):
    """Aplica o grupo do vetor (escala 0,9 em torno de 54,55 e sobe 1) e recorta a área visível (18 a 90)."""
    x = 54 + 0.9 * (ponto[0] - 54)
    y = 55 + 0.9 * (ponto[1] - 55) - 1
    return ((x - 18) / 72.0, (y - 18) / 72.0)


def distancia(px, py, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    cx, cy = ax + t * dx, ay + t * dy
    return ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5


def desenhar(lado):
    tracos = []
    for a, b, c, cor in DIVISAS:
        pa, pb, pc = (tuple(v * lado for v in no_quadro(p)) for p in (a, b, c))
        tracos.append((pa, pb, pc, cor))
    meia = ESPESSURA * 0.9 / 72.0 * lado / 2.0
    linhas = []
    for y in range(lado):
        linha = bytearray([0])
        for x in range(lado):
            f = (x + y) / (2.0 * (lado - 1))
            r, g, b = (COR_INICIO[i] + (COR_FIM[i] - COR_INICIO[i]) * f for i in range(3))
            for pa, pb, pc, cor in tracos:
                d = min(distancia(x + 0.5, y + 0.5, pa, pb), distancia(x + 0.5, y + 0.5, pb, pc))
                cobre = max(0.0, min(1.0, meia - d + 0.5))
                if cobre > 0:
                    r, g, b = (v + (cor[i] - v) * cobre for i, v in enumerate((r, g, b)))
            linha += bytes((int(r + 0.5), int(g + 0.5), int(b + 0.5)))
        linhas.append(bytes(linha))
    return b"".join(linhas)


def bloco(tipo, dados):
    return struct.pack(">I", len(dados)) + tipo + dados + struct.pack(">I", zlib.crc32(tipo + dados) & 0xFFFFFFFF)


def salvar_png(caminho, lado):
    cru = desenhar(lado)
    png = b"\x89PNG\r\n\x1a\n"
    png += bloco(b"IHDR", struct.pack(">IIBBBBB", lado, lado, 8, 2, 0, 0, 0))
    png += bloco(b"IDAT", zlib.compress(cru, 9))
    png += bloco(b"IEND", b"")
    with open(longo(caminho), "wb") as arquivo:
        arquivo.write(png)


def main():
    for lado in (180, 192, 512):
        salvar_png(os.path.join(WEB, "icon-%d.png" % lado), lado)
        print("icon-%d.png" % lado)
    origem = os.path.join(RAIZ, "app", "src", "main", "res", "drawable-nodpi", "carro.webp")
    shutil.copyfile(longo(origem), longo(os.path.join(WEB, "carro.webp")))
    print("carro.webp")


if __name__ == "__main__":
    main()
