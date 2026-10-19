# Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
# SPDX-License-Identifier: GPL-3.0-only

"""Independent Unix JSON subscriber and process lifecycle regression.

Run: python3 src/tests/telemetry/headless.py BINARY
Uses only the public IPC contract and Python's standard library.
"""

import argparse
import json
import os
import re
from pathlib import Path
import signal
import socket
import struct
import subprocess
import tempfile
import threading
import time

KEY = "0123456789abcdef0123456789abcdef"
DEADLINE = 10


def wait_for(predicate):
    deadline = time.monotonic() + DEADLINE
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.02)
    raise AssertionError("process readiness deadline elapsed")


def reserve_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def exact(sock, count):
    result = bytearray()
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        assert chunk, "unexpected EOF"
        result.extend(chunk)
    return bytes(result)


def read_message(sock):
    length = struct.unpack("!I", exact(sock, 4))[0]
    assert 0 < length <= 65536
    return json.loads(exact(sock, length))


def next_kind(sock, kind):
    deadline = time.monotonic() + DEADLINE
    while time.monotonic() < deadline:
        message = read_message(sock)
        if message["type"] == kind:
            return message["data"]
    raise AssertionError(f"missing IPC message: {kind}")


def stop(process):
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(DEADLINE)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            raise AssertionError("service did not shut down within its deadline")


def main():
    assert os.name == "posix", "this regression uses Unix sockets"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    current = str(args.binary.resolve())
    processes = []
    ipc = None
    with tempfile.TemporaryDirectory(prefix="n3-", dir="/tmp") as tmp:
        env = dict(os.environ, TMPDIR=tmp, TOKIO_WORKER_THREADS="2")
        telemetry_directory = Path(tmp) / f"nowhere-telemetry-{os.getuid()}"
        try:
            logs = {}

            def spawn(binary, url):
                index = len(processes)
                stdout = Path(tmp) / f"stdout-{index}"
                stderr = Path(tmp) / f"stderr-{index}"
                with stdout.open("wb") as output, stderr.open("wb") as errors:
                    process = subprocess.Popen([binary, url], env=env, stdin=subprocess.DEVNULL,
                                               stdout=output, stderr=errors)
                logs[process.pid] = (stdout, stderr)
                processes.append(process)
                return process

            port = reserve_port()
            portal = spawn(current, f"portal://{KEY}@127.0.0.1:{port}")
            records = wait_for(lambda: list(telemetry_directory.glob("*.json")))
            assert telemetry_directory.stat().st_mode & 0o777 == 0o700
            record = json.loads(records[0].read_text())
            assert record["protocol"] == "nowhere.telemetry"
            assert record["instance"]["pid"] == portal.pid
            assert records[0].stat().st_mode & 0o777 == 0o600
            assert Path(record["endpoint"]).parent == telemetry_directory
            assert Path(record["endpoint"]).stat().st_mode & 0o777 == 0o600
            ipc = socket.socket(socket.AF_UNIX)
            ipc.settimeout(DEADLINE)
            ipc.connect(record["endpoint"])
            hello = next_kind(ipc, "hello")
            assert hello["instance"]["contract"] == record["protocol"]
            assert hello["instance"]["pid"] == portal.pid
            assert hello["instance"]["role"] == "portal"
            assert KEY not in json.dumps(hello)
            snapshot = next_kind(ipc, "snapshot")
            assert set(snapshot) == {"tcp_upload_bytes", "tcp_download_bytes", "udp_upload_bytes", "udp_download_bytes", "tcp_active", "udp_active"}
            assert set(hello) == {"instance"}
            assert set(hello["instance"]) == {"contract", "id", "role", "pid", "version", "endpoint"}

            ipc.close()
            ipc = socket.socket(socket.AF_UNIX)
            ipc.settimeout(DEADLINE)
            ipc.connect(record["endpoint"])
            assert next_kind(ipc, "hello")["instance"]["id"] == hello["instance"]["id"]
            assert read_message(ipc)["type"] == "snapshot"

            pin = subprocess.check_output([current, "fingerprint",
                f"nowhere://{KEY}@127.0.0.1:{port}?net=tcp&morph=1"], env=env, timeout=DEADLINE,
                stderr=subprocess.PIPE).decode().strip()
            assert len(pin) == 64
            ingress = reserve_port()
            vector = spawn(current, f"vector://{KEY}@127.0.0.1:{port}"
                           f"?in=127.0.0.1:{ingress}&pin={pin}")
            wait_for(lambda: len(list(telemetry_directory.glob("*.json"))) == 2)
            target = socket.socket()
            target.bind(("127.0.0.1", 0))
            target.listen(1)
            target.settimeout(DEADLINE)
            payload = b"nw3-headless-known-bytes" * 128
            echo_errors = []

            def echo():
                try:
                    connection, _ = target.accept()
                    with connection:
                        connection.settimeout(DEADLINE)
                        while True:
                            data = connection.recv(65536)
                            if not data:
                                break
                            connection.sendall(data)
                except Exception as error:
                    echo_errors.append(error)

            worker = threading.Thread(target=echo, daemon=True)
            worker.start()
            with wait_for(lambda: connect_ready(ingress)) as flow:
                flow.settimeout(DEADLINE)
                flow.sendall(b"\x05\x01\x00")
                assert exact(flow, 2) == b"\x05\x00"
                flow.sendall(b"\x05\x01\x00\x01\x7f\x00\x00\x01"
                             + struct.pack("!H", target.getsockname()[1]))
                reply = exact(flow, 10)
                assert reply[:4] == b"\x05\x00\x00\x01"
                flow.sendall(payload)
                flow.shutdown(socket.SHUT_WR)
                assert exact(flow, len(payload)) == payload
                assert flow.recv(1) == b""
            worker.join(DEADLINE)
            target.close()
            assert not worker.is_alive() and not echo_errors
            deadline = time.monotonic() + DEADLINE
            while time.monotonic() < deadline:
                snapshot = next_kind(ipc, "snapshot")
                if snapshot["tcp_active"] == 0 and snapshot["tcp_upload_bytes"] == len(payload):
                    break
            assert snapshot["tcp_active"] == 0
            assert snapshot["tcp_upload_bytes"] == len(payload)
            assert snapshot["tcp_download_bytes"] == len(payload)
            vector_record = next(json.loads(p.read_text()) for p in telemetry_directory.glob("*.json")
                                 if json.loads(p.read_text())["instance"]["pid"] == vector.pid)
            with socket.socket(socket.AF_UNIX) as observer:
                observer.settimeout(DEADLINE)
                observer.connect(vector_record["endpoint"])
                assert next_kind(observer, "hello")["instance"]["role"] == "vector"
                deadline = time.monotonic() + DEADLINE
                while time.monotonic() < deadline:
                    observed = next_kind(observer, "snapshot")
                    if observed["tcp_upload_bytes"] == len(payload) and observed["tcp_active"] == 0:
                        break
                assert observed["tcp_upload_bytes"] == len(payload), observed
                assert observed["tcp_download_bytes"] == len(payload)

                with socket.create_connection(("127.0.0.1", ingress), timeout=DEADLINE) as invalid:
                    invalid.sendall(b"\x04\x01\x00")
                    invalid.shutdown(socket.SHUT_WR)
                    try:
                        invalid.recv(1024)
                    except ConnectionResetError:
                        pass
            ipc.sendall(b"ignored client input")
            assert read_message(ipc)["type"] == "snapshot"
            ipc.close()
            ipc = socket.socket(socket.AF_UNIX)
            ipc.settimeout(DEADLINE)
            ipc.connect(record["endpoint"])
            next_kind(ipc, "hello")
            assert read_message(ipc)["type"] == "snapshot"
            status = subprocess.check_output([current, "status"], env=env, timeout=DEADLINE,
                                             stderr=subprocess.PIPE).decode()
            assert f"PORTAL · PID {portal.pid}" in status
            assert f"VECTOR · PID {vector.pid}" in status
            assert KEY not in status
            help_output = subprocess.check_output([current], env=env, timeout=DEADLINE,
                                                  stderr=subprocess.PIPE).decode()
            assert "USAGE" in help_output and "nowhere unknown-command" not in help_output
            invalid = subprocess.run([current, "unknown-command"], env=env, timeout=DEADLINE,
                                     capture_output=True)
            assert invalid.returncode == 1 and b"unknown command" in invalid.stderr
            assert b"terminal" not in invalid.stderr
            ipc.close()
            ipc = None
            stop(portal)
            with socket.create_connection(("127.0.0.1", ingress), timeout=DEADLINE) as control:
                control.sendall(b"\x05\x01\x00")
                assert exact(control, 2) == b"\x05\x00"
                control.sendall(b"\x05\x03\x00\x01" + bytes(6))
                reply = exact(control, 10)
                assert reply[:4] == b"\x05\x00\x00\x01"
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as datagram:
                    datagram.bind(("127.0.0.1", 0))
                    datagram.sendto(b"\x00\x00\x00\x01\x7f\x00\x00\x01\x00\x35x",
                                    (socket.inet_ntoa(reply[4:8]), struct.unpack("!H", reply[8:])[0]))
                    wait_for(lambda: "connection refused" in logs[vector.pid][1].read_text().lower())
            udp_errors = [line for line in logs[vector.pid][1].read_text().splitlines()
                          if "udp setup failed:" in line]
            assert len(udp_errors) == 1 and "flow transport failed" in udp_errors[0]
            assert re.match(r"^\S+ udp setup failed: flow transport failed:", udp_errors[0])
            assert "target 127.0.0.1:53" not in udp_errors[0]
            stop(vector)
            assert portal.returncode == 0 and vector.returncode == 0
            for process, role in [(portal, "Portal"), (vector, "Vector")]:
                stdout, stderr = (path.read_text() for path in logs[process.pid])
                assert f"{role.lower()} starting:" in stdout and f"{role.lower()} shutdown complete" in stdout
                assert KEY not in stdout + stderr
                assert "authentication enabled" not in stdout
                for line in (stdout + stderr).splitlines():
                    assert re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z ", line), line
                    assert "exchange starting" not in line and "transfer complete" not in line
            assert "tls certificate fingerprint:" in logs[portal.pid][0].read_text()
            assert "socks" in logs[vector.pid][1].read_text().lower()
            assert not list(telemetry_directory.glob("*.json"))
            assert not list(telemetry_directory.glob("*.sock"))
            idle = subprocess.check_output([current, "status"], env=env, timeout=DEADLINE).decode()
            assert "IDLE" in idle
            print(json.dumps({"headless": "passed", "passive_reconnection": "passed",
                              "extra_input_ignored": "passed",
                              "raw_stderr_and_stdout": "passed",
                              "udp_original_error_chain": "passed",
                              "known_logical_bytes_each_direction": len(payload),
                              "cleanup": "passed"}))
        finally:
            if ipc is not None:
                ipc.close()
            for process in reversed(processes):
                stop(process)


def connect_ready(port):
    try:
        return socket.create_connection(("127.0.0.1", port), timeout=0.2)
    except OSError:
        return None


if __name__ == "__main__":
    main()
