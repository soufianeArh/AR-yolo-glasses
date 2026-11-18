"""
Step 8.7: a bad network on demand - a TCP proxy that throttles and delays.

The Android emulator's own network shaping (adb emu network speed/delay) proved unreliable for the
emulator -> WSL route, and the WSL kernel has no `netem`. So: put this proxy between the app and the
Java scheduler and point the app at it.

  app (emulator) --> proxy :50052 --(rate limit + delay)--> scheduler :50051

  ~/ar-venv/bin/python tools/slow_proxy.py --up-kbit 100 --delay-ms 300
  adb shell am start -n com.soufiane.ar.glasses/.MainActivity --ei port 50052

Both directions get the delay; --up-kbit limits app -> scheduler (frames), --down-kbit the answers.
Settings can change while running: write "up_kbit delay_ms" into /tmp/slow_proxy.conf (checked every s),
e.g. `echo "0 0" > /tmp/slow_proxy.conf` = no limit (good network again).
"""
import argparse
import asyncio
import os
import time

CONF = "/tmp/slow_proxy.conf"
state = {"up_kbit": 0.0, "down_kbit": 0.0, "delay_ms": 0.0}


async def pump(reader, writer, direction):
    """Copies one direction in small chunks: each chunk waits for the delay and the rate limit."""
    sent_at = time.monotonic()
    try:
        while True:
            data = await reader.read(4096)
            if not data:
                break
            if state["delay_ms"] > 0:
                await asyncio.sleep(state["delay_ms"] / 1000)
            kbit = state["up_kbit"] if direction == "up" else state["down_kbit"]
            if kbit > 0:                                    # time this chunk needs on the slow link
                need = len(data) * 8 / (kbit * 1000)
                sent_at = max(sent_at, time.monotonic()) + need
                await asyncio.sleep(max(0.0, sent_at - time.monotonic()))
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def handle(client_r, client_w, target):
    host, port = target
    try:
        server_r, server_w = await asyncio.open_connection(host, port)
    except OSError as e:
        print(f"proxy: scheduler {host}:{port} unreachable: {e}", flush=True)
        client_w.close()
        return
    peer = client_w.get_extra_info("peername")
    print(f"proxy: connection from {peer}", flush=True)
    await asyncio.gather(pump(client_r, server_w, "up"), pump(server_r, client_w, "down"))
    print(f"proxy: connection from {peer} closed", flush=True)


async def watch_conf():
    last = None
    while True:
        try:
            m = os.path.getmtime(CONF)
            if m != last:
                last = m
                up, delay = open(CONF).read().split()[:2]
                state["up_kbit"], state["delay_ms"] = float(up), float(delay)
                print(f"proxy: now up {state['up_kbit']:g} kbit/s (0 = no limit), delay {state['delay_ms']:g} ms", flush=True)
        except (OSError, ValueError):
            pass
        await asyncio.sleep(1)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=50052)
    ap.add_argument("--target", default="127.0.0.1:50051")
    ap.add_argument("--up-kbit", type=float, default=100, help="app -> scheduler; 0 = no limit")
    ap.add_argument("--down-kbit", type=float, default=0, help="scheduler -> app; 0 = no limit")
    ap.add_argument("--delay-ms", type=float, default=300)
    a = ap.parse_args()
    state.update(up_kbit=a.up_kbit, down_kbit=a.down_kbit, delay_ms=a.delay_ms)
    with open(CONF, "w") as f:
        f.write(f"{a.up_kbit} {a.delay_ms}\n")
    host, port = a.target.rsplit(":", 1)
    srv = await asyncio.start_server(lambda r, w: handle(r, w, (host, int(port))), "0.0.0.0", a.listen)
    print(f"proxy: listening on :{a.listen} -> {a.target}, up {a.up_kbit:g} kbit/s, "
          f"down {a.down_kbit:g} kbit/s, delay {a.delay_ms:g} ms", flush=True)
    asyncio.create_task(watch_conf())
    async with srv:
        await srv.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
