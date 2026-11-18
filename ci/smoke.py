#!/usr/bin/env python3
"""Teste de ponta a ponta num emulador Android (roda no GitHub Actions).

Instala o APK, dispara o alarme de teste com a tela apagada e cumpre o ciclo
inteiro: questões -> contas -> frase -> espera -> checagem -> bom dia. Depois
confere a recaída e a troca da música pelo bipe. Em seguida usa as telas do
Disciplina: metas de estudo com resumo digitado, diário, missão simples com o
alarme que para com um toque, treino, ideias e a experiência somada. Por fim,
os avisos de hora de dormir. Salva capturas de tela em ci-out/ e falha se o
app travar ou alguma etapa não aparecer.

O relógio do emulador é acertado para 10:00 logo no começo: assim nenhuma
missão com horário toca no meio do teste e o bônus de estudo é previsível.

A missão surpresa (falar ou memória) fica desligada aqui: o emulador não fala
nem enxerga a sequência que acende.

Uso: python3 ci/smoke.py caminho/para/levanta.apk
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.iacomthiago.levanta"
OUT = "ci-out"
FRASE = "Eu levanto agora e cumpro o que prometi".split()
with open("app/src/main/assets/questoes.json", encoding="utf-8") as _f:
    QUESTOES = json.load(_f)

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
                    "cls": n.get("class", ""),
                    "cx": (x1 + x2) // 2,
                    "cy": (y1 + y2) // 2,
                    "x1": x1,
                    "x2": x2,
                    "y1": y1,
                    "y2": y2,
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


def achar_contendo(nos, trecho):
    """Como achar(), mas basta o texto conter o trecho (linhas clicáveis juntam os textos de dentro)."""
    candidatos = [n for n in nos if (trecho in n["text"] or trecho in n["desc"]) and n["enabled"]]
    return candidatos[-1] if candidatos else None


def tocar(no):
    sh(f"input tap {no['cx']} {no['cy']}")


def rolar(nos, para_baixo=True):
    """Arrasta a tela devagar, na metade de cima (longe do teclado), para não dar impulso.

    O dedo vai rente à borda esquerda do conteúdo: no meio da tela fica o
    relógio de escolher a hora, que puxaria o ponteiro em vez de rolar.
    """
    altura = max((n["y2"] for n in nos), default=800)
    x = min((n["x1"] for n in nos if n["text"] or n["desc"]), default=100) + 30
    de, ate = (int(altura * 0.46), int(altura * 0.16)) if para_baixo else (int(altura * 0.16), int(altura * 0.46))
    sh(f"input swipe {x} {de} {x} {ate} 700")
    time.sleep(0.8)


def procurar(rotulo, contendo=False, voltas=9):
    """Procura um elemento rolando a tela: primeiro para baixo, depois de volta para cima."""
    ultimos = []
    for para_baixo in (True, False):
        anterior = None
        for _ in range(voltas):
            nos = dump()
            ultimos = textos(nos)
            no = achar_contendo(nos, rotulo) if contendo else achar(nos, rotulo)
            if no and no["y2"] - no["y1"] >= 24:
                return no
            assinatura = [(n["text"], n["cy"]) for n in nos]
            if assinatura == anterior:
                break  # chegou ao fim da tela
            anterior = assinatura
            rolar(nos, para_baixo)
    problemas.append(f"Não achei rolando: {rotulo!r}. Na tela: {ultimos}")
    log(problemas[-1])
    return None


def tocar_rolando(rotulo, contendo=False):
    no = procurar(rotulo, contendo)
    if no:
        tocar(no)
        time.sleep(1.5)
    return no is not None


def fechar_teclado():
    """Se o teclado da tela estiver aberto, fecha (o voltar, nesse caso, só fecha o teclado)."""
    if "mInputShown=true" in sh("dumpsys input_method | grep -E 'mInputShown=' | head -3"):
        sh("input keyevent 4")
        time.sleep(1.0)


def tocar_campo():
    """Toca no campo de texto da tela para poder digitar."""
    for _ in range(5):
        campos = [n for n in dump() if n["cls"].endswith("EditText")]
        if campos:
            tocar(campos[-1])
            time.sleep(1.0)
            return True
        time.sleep(1)
    problemas.append(f"Não achei campo de texto. Na tela: {textos(dump())}")
    log(problemas[-1])
    return False


def digitar(texto):
    sh("input text '" + texto.replace(" ", "%s") + "'", timeout=180)
    time.sleep(0.8)


def ler_total():
    """Lê o total de experiência no cartão do personagem, no alto da tela inicial."""
    for _ in range(8):
        nos = dump()
        for t in textos(nos):
            m = re.fullmatch(r"Total: (\d+) XP", t)
            if m:
                return int(m.group(1))
        rolar(nos, para_baixo=False)
    problemas.append(f"Não achei o total de XP na tela inicial. Na tela: {textos(dump())}")
    log(problemas[-1])
    return None


def conferir_ganho(antes, esperados, o_que):
    """Confere quanto a experiência subiu. A missão da semana pode somar 150 de bônus junto."""
    depois = ler_total()
    if antes is None or depois is None:
        return depois
    ganho = depois - antes
    log(f"XP depois de {o_que}: {antes} -> {depois}")
    if ganho not in esperados and ganho - 150 not in esperados:
        problemas.append(f"XP depois de {o_que}: era {antes}, ficou {depois}; esperava +{esperados}")
    return depois


def acertar_relogio(hhmm, segundos="00"):
    dia = sh("date +%m%d").strip()
    ano = sh("date +%Y").strip()
    sh(f"date {dia}{hhmm}{ano}.{segundos}")
    log("relógio do emulador: " + sh("date").strip())


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
    capturou_questao = False
    for _ in range(70):
        nos = dump()
        ts = textos(nos)
        continuar = achar(nos, "Continuar")
        if continuar:
            tocar(continuar)
            time.sleep(1.0)
            continue
        questao = next((q for q in QUESTOES if q["texto"] in ts), None)
        if questao:
            if not capturou_questao:
                shot(f"{prefixo}-questoes")
                capturou_questao = True
            rotulo = "Certo" if questao["certo"] else "Errado"
            botao = achar(nos, rotulo)
            if botao:
                log(f"questão {questao['id']}: {rotulo}")
                tocar(botao)
            time.sleep(1.0)
            continue
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
    problemas.append(f"As missões não terminaram em 70 passos. Na tela: {textos(dump())}")
    return False


def voltar_para_o_app():
    """Traz o app de volta para a frente, na tela em que ele estava."""
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)


def escrever_resumo():
    """Digita o resumo aos poucos, até passar do mínimo de palavras.

    O emulador engole teclas quando o texto vem comprido de uma vez só, então
    quem diz quando parar é o próprio campo (o contador da tela pode estar
    escondido atrás do teclado).
    """
    for _ in range(16):
        campos = [n for n in dump() if n["cls"].endswith("EditText")]
        if campos and len(campos[-1]["text"].split()) >= 24:
            return True
        digitar("estudei o artigo e entendi ")
        time.sleep(1.0)
    problemas.append(f"O resumo não chegou ao mínimo de palavras. Na tela: {textos(dump())}")
    log(problemas[-1])
    return False


def estudar():
    """Escreve duas metas e conclui cada uma com um resumo digitado (o emulador não fala)."""
    if not tocar_rolando("Escreva as metas de hoje", contendo=True):
        return False
    if not esperar("Nenhuma ainda", 15):
        return False
    shot("15-estudo-vazio")
    if not tocar_campo():
        return False
    digitar("Ler artigo 37")
    sh("input keyevent 66")
    digitar("Resolver dez questoes")
    fechar_teclado()
    if not tocar_rolando("Adicionar metas"):
        return False
    if not esperar("0 de 2", 15):
        return False
    shot("16-estudo-metas")

    for feitas in (1, 2):
        if not tocar_rolando("Concluir meta"):
            return False
        if not esperar("META", 15):
            return False
        if not tocar_campo():
            return False
        if not escrever_resumo():
            return False
        fechar_teclado()
        if feitas == 1:
            shot("17-resumo-da-meta")
        if not tocar_rolando("Guardar e concluir (+30 XP)"):
            return False
        if not esperar(f"{feitas} de 2", 15):
            return False
    if not esperar("Dia de estudo fechado.", 10):
        return False
    shot("18-estudo-fechado")

    if not tocar_rolando("Diário de estudo"):
        return False
    if not esperar("Copiar o diário inteiro", 15):
        return False
    shot("19-diario")
    sh("input keyevent 4")  # volta para o estudo
    time.sleep(1.5)
    sh("input keyevent 4")  # volta para a tela inicial
    time.sleep(1.5)
    return True


def missao_simples():
    """Cria uma missão simples, faz o alarme dela tocar e conclui com um toque na tela do alarme."""
    if not tocar_rolando("Nova missão"):
        return False
    if not tocar_em("Simples", 15):
        return False
    if not tocar_rolando("Salvar missão"):
        return False
    time.sleep(1.5)
    # Na lista de todas as missões, tocar na linha abre a edição, onde fica o teste do alarme.
    if not tocar_rolando("Simples · Todos os dias", contendo=True):
        return False
    if not tocar_rolando("Testar o alarme desta missão (toca em 5 segundos)"):
        return False
    time.sleep(10)
    topo = sh("dumpsys activity activities | grep -E 'ResumedActivity'").strip()
    som = sh("dumpsys audio | grep -i 'USAGE_ALARM' | grep -i 'state:started' | head -3").strip()
    log(f"missão tocando. topo: {topo} | áudio: {som}")
    shot("20-missao-tocando")
    if "AlarmActivity" not in topo:
        problemas.append("A tela do alarme da missão não abriu sozinha")
    if not som:
        problemas.append("O alarme da missão não está tocando")
    if not esperar("HORA DA MISSÃO", 15):
        return False
    if not tocar_em("Concluir agora (+10 XP)", 15):
        return False
    time.sleep(3)
    som = sh("dumpsys audio | grep -i 'USAGE_ALARM' | grep -i 'state:started' | head -3").strip()
    if som:
        problemas.append(f"O alarme da missão continuou tocando depois do toque: {som!r}")
    return True


def treinar():
    """Zera o tempo mínimo do treino, começa (abre o navegador), volta e conclui."""
    if not tocar_rolando("TREINO", contendo=True):
        return False
    if not esperar("Pronto para começar?", 15):
        return False
    shot("21-treino")
    if not tocar_rolando("Editar esta missão"):
        return False
    if not procurar("Mínimo para concluir"):
        return False
    for _ in range(8):
        nos = dump()
        rotulo = achar(nos, "Mínimo para concluir")
        if not rotulo:
            break
        menos = [n for n in nos if n["text"] == "−" and n["enabled"] and abs(n["cy"] - rotulo["cy"]) < 70]
        if not menos:
            break
        tocar(menos[0])
        time.sleep(0.5)
    if "0%" not in textos(dump()):
        problemas.append(f"Não consegui zerar o mínimo do treino. Na tela: {textos(dump())}")
        return False
    if not tocar_rolando("Salvar missão"):
        return False
    time.sleep(1.5)
    if not tocar_rolando("TREINO", contendo=True):
        return False
    if not tocar_em("Começar treino", 15):
        return False
    time.sleep(5)
    topo = sh("dumpsys activity activities | grep -E 'ResumedActivity'").strip()
    log(f"depois de começar o treino, no topo: {topo}")
    voltar_para_o_app()
    # O navegador do emulador fica fechado para não abrir avisos por cima do teste.
    m = re.search(r" ([\w.]+)/", topo)
    if m and m.group(1) != PKG and re.search(r"chrome|browser|webview", m.group(1), re.I):
        sh(f"am force-stop {m.group(1)}")
    time.sleep(2)
    if not achar(dump(), "Já pode concluir."):
        # O app pode voltar na tela inicial; a missão em andamento continua na lista de hoje.
        log("o app não voltou na tela do treino; abrindo pela lista de hoje")
        if not tocar_rolando("TREINO", contendo=True):
            return False
    if not esperar("Já pode concluir.", 15):
        return False
    if not tocar_em("Concluí (+40 XP)", 15):
        return False
    if not esperar("Treino concluído hoje.", 15):
        return False
    shot("22-treino-concluido")
    sh("input keyevent 4")
    time.sleep(1.5)
    return True


def anotar_ideia():
    if not tocar_rolando("Ideias para depois"):
        return False
    if not tocar_campo():
        return False
    digitar("Ideia de teste")
    fechar_teclado()
    if not tocar_rolando("Guardar ideia"):
        return False
    if not esperar("GUARDADAS", 15):
        return False
    shot("23-ideias")
    sh("input keyevent 4")
    time.sleep(1.5)
    return True


def conferir_disciplina():
    """Usa as telas novas e confere a experiência somada a cada missão."""
    tocar_em("Fechar", 10)
    voltar_para_o_app()
    total = ler_total()
    if total is None:
        return False

    if not estudar():
        return False
    # Duas metas de 30; fechadas antes das 14 h, mais 50 de bônus.
    total = conferir_ganho(total, (110, 60), "o estudo")

    if not missao_simples():
        return False
    total = conferir_ganho(total, (10,), "a missão simples")

    if not treinar():
        return False
    total = conferir_ganho(total, (40,), "o treino")
    shot("24-inicio-com-progresso")

    return anotar_ideia()


def avisos_de_dormir():
    return sh("dumpsys notification --noredact | grep -E 'android.title=.*Hora de dormir' | head -5").strip()


def conferir_hora_de_dormir():
    """Adianta o relógio do emulador e confere os dois avisos de dormir."""
    # 21:58:00. Com a missão de levantar padrão das 06:30 e 8 h de sono, deitar é às 22:30.
    acertar_relogio("2158")
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    if not tocar_rolando("Nova missão"):
        return
    # Ainda não há missão de levantar, então a tela já abre nesse tipo.
    if not tocar_rolando("Salvar missão"):
        return
    time.sleep(2)
    procurar("deite até 22:30", contendo=True)
    shot("25-hora-de-dormir")

    fim = time.time() + 170
    achou = ""
    while time.time() < fim and "em 30 minutos" not in achou:
        time.sleep(5)
        achou = avisos_de_dormir()
    log(f"aviso de meia hora antes: {achou!r} (relógio {sh('date +%H:%M:%S').strip()})")
    if "em 30 minutos" not in achou:
        problemas.append(f"O aviso de dormir de meia hora antes não apareceu: {achou!r}")

    acertar_relogio("2229", "40")
    fim = time.time() + 90
    achou = ""
    while time.time() < fim and "(Hora de dormir)" not in achou:
        time.sleep(5)
        achou = avisos_de_dormir()
    log(f"aviso da hora de deitar: {achou!r} (relógio {sh('date +%H:%M:%S').strip()})")
    if "(Hora de dormir)" not in achou:
        problemas.append(f"O aviso da hora de dormir não apareceu: {achou!r}")


def main():
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)

    # Relógio fixo desde o começo (precisa de root, que o emulador de teste permite).
    adb("root")
    time.sleep(5)
    adb("wait-for-device")
    sh("settings put global auto_time 0")
    acertar_relogio("1000")

    r = adb("install", "-r", "-g", apk, timeout=600)
    log("install: " + (r.stdout + r.stderr).decode("utf-8", "replace").strip())
    sh("logcat -c")
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    sh("input keyevent 224")  # acorda a tela
    sh("wm dismiss-keyguard")

    log(sh(f"am start -W -n {PKG}/.MainActivity --ez sem_surpresa true").strip())
    time.sleep(5)
    shot("01-inicio")
    log("tela inicial: " + str(textos(dump())))

    # Telas secundárias, só para conferir o visual.
    if tocar_em("Ajustes"):
        time.sleep(1.5)
        shot("02-ajustes")
        sh("input keyevent 4")
        time.sleep(1.5)
    if tocar_rolando("Nova missão"):
        time.sleep(1.5)
        shot("03-nova-missao")
        sh("input keyevent 4")
        time.sleep(1.5)

    # Alarme de teste com a tela apagada: ele precisa acender e abrir sozinho.
    if not tocar_rolando("Testar agora (toca em 5 segundos)"):
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
    elif "CONTENT_TYPE_MUSIC" not in som:
        problemas.append(f"O alarme não começou pela música de abertura: {som!r}")

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
    if not tocar_rolando("Testar agora (toca em 5 segundos)"):
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

    # Terceira rodada: sem missão cumprida, a música tem que virar bipe em 60 s.
    tocar_em("Fechar", 10)
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    if not tocar_rolando("Testar agora (toca em 5 segundos)"):
        return
    time.sleep(14)
    tocando = "dumpsys audio | grep -i 'USAGE_ALARM' | grep -i 'state:started' | head -3"
    som = sh(tocando).strip()
    log(f"áudio no começo: {som}")
    if "CONTENT_TYPE_MUSIC" not in som:
        problemas.append(f"A terceira rodada não começou pela música: {som!r}")
    log("esperando a música virar bipe (60 s depois do toque)")
    time.sleep(60)
    som = sh(tocando).strip()
    log(f"áudio depois de 60 s: {som}")
    if "CONTENT_TYPE_SONIFICATION" not in som:
        problemas.append(f"A música não virou bipe depois de 60 s: {som!r}")
    shot("13-bipe")
    if tocar_em("Começar missão", 20) and cumprir_missoes("14"):
        if esperar("Você continua de pé?", 50):
            tocar_em("Estou de pé")
            esperar("Você levantou.", 20)

    if not conferir_disciplina():
        shot("24-disciplina-falha")
        return

    conferir_hora_de_dormir()


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
