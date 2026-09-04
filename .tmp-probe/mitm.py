#!/usr/bin/env python3
"""MITM 代理：127.0.0.1:2222 -> (HTTP CONNECT 代理) -> localhost.run:22
记录双向数据包的时间和长度（SSH 加密，只看时序/大小/方向）。"""
import socket, threading, sys, time

LOG = open(sys.argv[1] if len(sys.argv) > 1 else "/workspace/.tmp-probe/mitm.log", "ab", buffering=0)
T0 = time.time()
lock = threading.Lock()

def log(direction, data):
    with lock:
        LOG.write(("%8.3f %s len=%d %s\n" % (
            time.time() - T0, direction, len(data),
            data[:40].hex() if len(data) < 200000 else "...")).encode())

def pump(src, dst, tag):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                log(tag + "_EOF", b"")
                break
            log(tag, data)
            dst.sendall(data)
    except Exception as e:
        log(tag + "_ERR", str(e).encode())
    finally:
        try: dst.shutdown(socket.SHUT_WR)
        except Exception: pass

def handle(client):
    # 经 HTTP 代理建立到 localhost.run:22 的隧道
    up = socket.create_connection(("127.0.0.1", 18080))
    up.sendall(b"CONNECT localhost.run:22 HTTP/1.1\r\nHost: localhost.run:22\r\n\r\n")
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = up.recv(4096)
        if not chunk:
            client.close(); return
        resp += chunk
    if b" 200 " not in resp.split(b"\r\n")[0]:
        log("PROXY_FAIL", resp)
        client.close(); up.close(); return
    log("TUNNEL_UP", b"")
    t1 = threading.Thread(target=pump, args=(client, up, "C->S"), daemon=True)
    t2 = threading.Thread(target=pump, args=(up, client, "S->C"), daemon=True)
    t1.start(); t2.start(); t1.join(); t2.join()
    client.close(); up.close()

srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 2222))
srv.listen(5)
print("listening on 2222", flush=True)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
