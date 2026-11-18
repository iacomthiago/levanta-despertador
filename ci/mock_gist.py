"""Imitação mínima da API de gists do GitHub, só para testar a sincronização do site.

Guarda tudo na memória e responde como o GitHub nas quatro chamadas que o
Disciplina usa (listar, criar, ler com ETag, gravar). Qualquer chave que comece
com "teste_" é aceita; as outras recebem 401.

Uso: python ci/mock_gist.py [porta]      (padrão 8766)
No site aberto em localhost: localStorage['disciplina.api'] = 'http://localhost:8766'
"""
import hashlib
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

GISTS = {}  # id -> {"id", "created_at", "description", "files": {nome: conteudo}}
CONTADOR = [0]


def etag_de(gist):
    return '"' + hashlib.sha1(json.dumps(gist, sort_keys=True).encode()).hexdigest() + '"'


def para_fora(gist):
    return {
        "id": gist["id"],
        "created_at": gist["created_at"],
        "description": gist["description"],
        "files": {nome: {"filename": nome, "content": texto, "truncated": False} for nome, texto in gist["files"].items()},
    }


class Pedido(BaseHTTPRequestHandler):
    def log_message(self, formato, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    def cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, If-None-Match, Accept")
        self.send_header("Access-Control-Expose-Headers", "ETag")

    def responder(self, status, corpo=None, etag=None):
        dados = b"" if corpo is None else json.dumps(corpo).encode()
        self.send_response(status)
        self.cors()
        if etag:
            self.send_header("ETag", etag)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)

    def autorizado(self):
        chave = self.headers.get("Authorization", "")
        if chave.startswith("Bearer teste_"):
            return True
        self.responder(401, {"message": "Bad credentials"})
        return False

    def corpo(self):
        tamanho = int(self.headers.get("Content-Length", "0") or 0)
        return json.loads(self.rfile.read(tamanho) or b"{}")

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.end_headers()

    def do_GET(self):
        if not self.autorizado():
            return
        caminho = urlparse(self.path).path
        if caminho == "/gists":
            self.responder(200, [para_fora(g) for g in GISTS.values()])
            return
        gist = GISTS.get(caminho.rsplit("/", 1)[-1])
        if not gist:
            self.responder(404, {"message": "Not Found"})
            return
        etag = etag_de(gist)
        if self.headers.get("If-None-Match") == etag:
            self.responder(304, None, etag)
            return
        self.responder(200, para_fora(gist), etag)

    def do_POST(self):
        if not self.autorizado():
            return
        dados = self.corpo()
        CONTADOR[0] += 1
        gist = {
            "id": "gist%04d" % CONTADOR[0],
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + ("%04d" % CONTADOR[0]),
            "description": dados.get("description", ""),
            "files": {nome: f.get("content", "") for nome, f in dados.get("files", {}).items()},
        }
        GISTS[gist["id"]] = gist
        self.responder(201, para_fora(gist), etag_de(gist))

    def do_PATCH(self):
        if not self.autorizado():
            return
        gist = GISTS.get(urlparse(self.path).path.rsplit("/", 1)[-1])
        if not gist:
            self.responder(404, {"message": "Not Found"})
            return
        for nome, f in self.corpo().get("files", {}).items():
            gist["files"][nome] = f.get("content", "")
        self.responder(200, para_fora(gist), etag_de(gist))


if __name__ == "__main__":
    porta = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
    print("API de mentira em http://localhost:%d" % porta)
    ThreadingHTTPServer(("127.0.0.1", porta), Pedido).serve_forever()
