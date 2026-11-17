#!/usr/bin/env python3
"""Teste de ponta a ponta num emulador Android (roda no GitHub Actions).

Instala o APK, dispara o alarme de teste com a tela apagada e cumpre o ciclo
inteiro: contas -> frase -> espera -> checagem -> bom dia. Salva capturas de
tela em ci-out/ e falha se o app travar ou alguma etapa não aparecer.

Uso: python3 ci/smoke.py caminho/para/levanta.apk
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.iacomthiago.levanta"
OUT = "ci-out"
FRASE = "Eu levanto agora e cumpro o que prometi".split()

problemas = []


def adb(*args, timeout=120):
    return subprocess.run(["adb", *args], capture_output=True, timeout=timeout)


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout).stdout.decode("utf-8", "replace")


def log(msg):
    print(f"[smoke {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def shot(nome):
    dados = adb("exec-out", "screencap", "-p").stdout
    with open(os.path.join(OUT, nome + ".png"), "wb") as f:
        f.write(dados)
    log(f"captura {nome}.png ({len(dados)} bytes)")


def dump():
    """Lista os elementos visíveis: texto, descrição, centro e se está habilitado."""
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml")
        bruto = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
        try:
            raiz = ET.fromstring(bruto)
        except ET.ParseError:
            time.sleep(1)
            continue
        nos = []
        for n in raiz.iter("node"):
            m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 <= x1 or y2 <= y1:
                continue
            nos.append(
                {
                    "text": n.get("text", ""),
                    "desc": n.get("content-desc", ""),
                    "cx": (x1 + x2) // 2,
                    "cy": (y1 + y2) // 2,
                    "enabled": n.get("enabled") == "true",
                }
            )
        return nos
    return []


def textos(nos):
    return [n["text"] for n in nos if n["text"]] + [n["desc"] for n in nos if n["desc"]]


def achar(nos, rotulo):
    candidatos = [n for n in nos if (n["text"] == rotulo or n["desc"] == rotulo) and n["enabled"]]
    return candidatos[-1] if candidatos else None


def tocar(no):
    sh(f"input tap {no['cx']} {no['cy']}")


def esperar(rotulo, limite=30):
    """Espera um elemento com esse texto aparecer; devolve o elemento ou None."""
    fim = time.time() + limite
    ultimos = []
    while time.time() < fim:
        nos = dump()
        ultimos = textos(nos)
        no = achar(nos, rotulo)
        if no:
            return no
        time.sleep(1)
    problemas.append(f"Não apareceu: {rotulo!r}. Na tela: {ultimos}")
    log(problemas[-1])
    return None


def tocar_em(rotulo, limite=30):
    no = esperar(rotulo, limite)
    if no:
        tocar(no)
        time.sleep(1.2)
    return no is not None


def resolver_conta(texto):
    p = texto.split()
    a, b = int(p[0]), int(p[2])
    valor = a * b if p[1] == "×" else a + b
    if len(p) == 5:
        valor += int(p[4])
    return valor


def cumprir_missoes(prefixo):
    """Resolve as contas e monta a frase até a tela de espera aparecer."""
    capturou_conta = False
    for _ in range(30):
        nos = dump()
        ts = textos(nos)
        conta = next((t for t in ts if re.fullmatch(r"\d+ [+×] \d+( \+ \d+)?", t)), None)
        if conta:
            if not capturou_conta:
                shot(f"{prefixo}-contas")
                capturou_conta = True
            resposta = resolver_conta(conta)
            log(f"conta {conta} = {resposta}")
            for digito in str(resposta):
                tecla = achar(nos, digito)
                if not tecla:
                    problemas.append(f"Tecla {digito} não encontrada")
                    return False
                tocar(tecla)
                time.sleep(0.25)
            ok = achar(nos, "OK")
            if ok:
                tocar(ok)
            time.sleep(1.0)
            continue
        if "Monte seu compromisso" in ts:
            shot(f"{prefixo}-frase")
            for palavra in FRASE:
                if not tocar_em(palavra, 15):
                    return False
            time.sleep(1.5)
            continue
        if "Não volte para a cama." in ts or "Você levantou." in ts:
            return True
        time.sleep(1)
    problemas.append("As missões não terminaram em 30 passos")
    return False


def main():
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)

    r = adb("install", "-r", "-g", apk, timeout=600)
    log("install: " + (r.stdout + r.stderr).decode("utf-8", "replace").strip())
    sh("logcat -c")
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    sh("input keyevent 224")  # acorda a tela
    sh("wm dismiss-keyguard")

    log(sh(f"am start -W -n {PKG}/.MainActivity").strip())
    time.sleep(5)
    shot("01-inicio")
    log("tela inicial: " + str(textos(dump())))

    # Telas secundárias, só para conferir o visual.
    if tocar_em("Ajustes"):
        time.sleep(1.5)
        shot("02-ajustes")
        sh("input keyevent 4")
        time.sleep(1.5)
    if tocar_em("Novo alarme"):
        time.sleep(1.5)
        shot("03-novo-alarme")
        sh("input keyevent 4")
        time.sleep(1.5)

    # Alarme de teste com a tela apagada: ele precisa acender e abrir sozinho.
    if not tocar_em("Testar agora (toca em 5 segundos)"):
        return
    sh("input keyevent 223")  # apaga a tela
    time.sleep(12)

    energia = sh("dumpsys power | grep -E 'mWakefulness='").strip()
    topo = sh("dumpsys activity activities | grep -E 'ResumedActivity'").strip()
    som = sh("dumpsys audio | grep -i 'USAGE_ALARM' | head -5").strip()
    log(f"energia: {energia}")
    log(f"atividade no topo: {topo}")
    log(f"áudio de alarme: {som}")
    shot("04-tocando")

    if "AlarmActivity" not in topo:
        problemas.append("A tela do alarme não abriu sozinha com a tela apagada")
        sh("input keyevent 224")
        sh(f"am start -n {PKG}/.MainActivity")
        time.sleep(3)
    if "Awake" not in energia:
        problemas.append("O alarme não acendeu a tela")
        sh("input keyevent 224")
        time.sleep(2)
    if "started" not in som:
        problemas.append(f"Não há som de alarme tocando: {som!r}")

    if not tocar_em("Começar missão"):
        return
    if not cumprir_missoes("05"):
        shot("05-falha")
        return
    shot("06-espera")

    # No teste a checagem chega em 20 segundos.
    if not esperar("Você continua de pé?", 50):
        shot("07-falha")
        return
    shot("07-checagem")
    if not tocar_em("Estou de pé"):
        return
    if esperar("Você levantou.", 20):
        shot("08-bom-dia")
    tocar_em("Fechar", 10)

    # Segunda rodada: ignorar a checagem tem que trazer o alarme de volta.
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    if not tocar_em("Testar agora (toca em 5 segundos)"):
        return
    time.sleep(9)
    if not tocar_em("Começar missão", 20):
        return
    if not cumprir_missoes("09"):
        return
    log("ignorando a checagem de propósito (20 s de espera + 45 s de prazo)")
    if esperar("VOCÊ NÃO CONFIRMOU", 100):
        shot("10-recaida")
        if tocar_em("Começar missão") and cumprir_missoes("11"):
            if esperar("Você continua de pé?", 50):
                tocar_em("Estou de pé")
                if esperar("Você levantou.", 20):
                    shot("12-bom-dia-com-recaida")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        problemas.append(f"Erro no script de teste: {e!r}")
    finally:
        registro = adb("logcat", "-d", "-v", "time").stdout.decode("utf-8", "replace")
        with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as f:
            f.write(registro)
        travou = [linha for linha in registro.splitlines() if "FATAL EXCEPTION" in linha]
        if travou and PKG in registro[registro.find("FATAL EXCEPTION"):][:4000]:
            problemas.append("O app travou (FATAL EXCEPTION no logcat)")
        with open(os.path.join(OUT, "resultado.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(problemas) if problemas else "OK")
        if problemas:
            print("\nPROBLEMAS:")
            for p in problemas:
                print(" -", p)
            sys.exit(1)
        print("\nTudo certo.")
