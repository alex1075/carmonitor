"""Tiny fake MQTT 3.1.1 broker for testing MiniMqtt: accepts one client, prints packets."""
import socket, sys

def rlen(c):
    m, v = 1, 0
    while True:
        d = c.recv(1)[0]
        v += (d & 127) * m
        if not d & 128:
            return v
        m *= 128

def recvn(c, n):
    b = b""
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x:
            raise EOFError
        b += x
    return b

def s16(b, i):
    n = int.from_bytes(b[i:i + 2], "big")
    return b[i + 2:i + 2 + n], i + 2 + n

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", int(sys.argv[1]))); srv.listen(1)
print("READY", flush=True)
c, _ = srv.accept()
try:
    while True:
        h = c.recv(1)
        if not h:
            break
        t = h[0]; body = recvn(c, rlen(c))
        if t == 0x10:
            proto, i = s16(body, 0); lvl, flags = body[i], body[i + 1]; ka = int.from_bytes(body[i + 2:i + 4], "big"); i += 4
            cid, i = s16(body, i)
            will = None
            if flags & 4:
                wt, i = s16(body, i); wm, i = s16(body, i); will = (wt.decode(), wm.decode(), bool(flags & 0x20))
            user = pw = None
            if flags & 0x80: user, i = s16(body, i)
            if flags & 0x40: pw, i = s16(body, i)
            print("CONNECT", proto.decode(), lvl, cid.decode(), "ka", ka, "will", will, "user", user, "pw", pw, flush=True)
            c.sendall(bytes([0x20, 2, 0, 0]))
        elif t & 0xF0 == 0x30:
            topic, i = s16(body, 0)
            print("PUBLISH retain=%d" % (t & 1), topic.decode(), len(body[i:]), body[i:i + 40].decode(), flush=True)
        elif t == 0xC0:
            print("PINGREQ", flush=True); c.sendall(bytes([0xD0, 0]))
        elif t == 0xE0:
            print("DISCONNECT", flush=True); break
except EOFError:
    pass
