"""Direct TCP messaging/file transfer and UDP peer discovery."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import struct
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Callable


MAX_HEADER = 64 * 1024
MAX_MESSAGE = 10_000
CHUNK_SIZE = 64 * 1024
DISCOVERY_INTERVAL = 3.0
PEER_TIMEOUT = 15.0


def read_exact(sock: socket.socket, count: int) -> bytes:
    parts = bytearray()
    while len(parts) < count:
        part = sock.recv(count - len(parts))
        if not part:
            raise EOFError("Connection closed before all data arrived")
        parts.extend(part)
    return bytes(parts)


def send_frame(sock: socket.socket, data: dict) -> None:
    payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
    if len(payload) > MAX_HEADER:
        raise ValueError("Protocol message is too large")
    sock.sendall(struct.pack("!I", len(payload)) + payload)


def read_frame(sock: socket.socket) -> dict:
    length = struct.unpack("!I", read_exact(sock, 4))[0]
    if length > MAX_HEADER:
        raise ValueError("Protocol message is too large")
    value = json.loads(read_exact(sock, length).decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Invalid protocol message")
    return value


@dataclass
class Peer:
    peer_id: str
    name: str
    host: str
    port: int
    last_seen: float
    manual: bool = False


class PeerNode:
    def __init__(
        self,
        name: str,
        port: int = 5001,
        discovery_port: int = 5000,
        on_event: Callable[[str, dict], None] | None = None,
        discovery: bool = True,
    ) -> None:
        self.name = name.strip()[:40] or socket.gethostname()
        self.peer_id = uuid.uuid4().hex
        self.port = port
        self.discovery_port = discovery_port
        self.discovery = discovery
        self.on_event = on_event or (lambda _kind, _data: None)
        self.peers: dict[str, Peer] = {}
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        self.server: socket.socket | None = None
        self.discovery_socket: socket.socket | None = None

    def _emit(self, kind: str, **data: object) -> None:
        self.on_event(kind, data)

    def _identity(self, kind: str, **fields: object) -> dict:
        return {
            "type": kind,
            "peer_id": self.peer_id,
            "name": self.name,
            "port": self.port,
            **fields,
        }

    def start(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind(("0.0.0.0", self.port))
            server.listen(20)
            server.settimeout(0.5)
        except Exception:
            server.close()
            raise
        self.server = server
        self.port = server.getsockname()[1]
        threading.Thread(target=self._accept_loop, daemon=True).start()
        if self.discovery:
            threading.Thread(target=self._discovery_loop, daemon=True).start()

    def stop(self) -> None:
        self.stopping.set()
        if self.server:
            self.server.close()
        if self.discovery_socket:
            self.discovery_socket.close()

    def list_peers(self) -> list[Peer]:
        with self.lock:
            return sorted(self.peers.values(), key=lambda peer: (peer.name.lower(), peer.host))

    def _remember(self, peer_id: str, name: str, host: str, port: int, manual: bool = False) -> None:
        # A peer can be reachable at several addresses (127.0.0.1 via /connect, its LAN address via
        # discovery). Keep the address sticky: manual contact sets it, discovery only moves it for
        # peers never added manually, and address-only updates stay silent so the list doesn't flap.
        if not peer_id or peer_id == self.peer_id or not (1 <= port <= 65535):
            return
        now = time.monotonic()
        changed = False
        with self.lock:
            old = self.peers.get(peer_id)
            if old is None:
                self.peers[peer_id] = Peer(peer_id, name[:40], host, port, now, manual)
                changed = True
            else:
                changed = (old.name, old.port) != (name[:40], port)
                old.name = name[:40]
                if manual or not old.manual:
                    old.host = host
                old.port = port
                old.last_seen = now
                old.manual |= manual
        if changed:
            self._emit("peers_changed")

    def _remember_header(self, header: dict, host: str, manual: bool = False) -> None:
        try:
            self._remember(str(header["peer_id"]), str(header["name"]), host, int(header["port"]), manual)
        except (KeyError, TypeError, ValueError):
            raise ValueError("Invalid peer identity") from None

    def _accept_loop(self) -> None:
        assert self.server is not None
        while not self.stopping.is_set():
            try:
                conn, address = self.server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self._handle_connection, args=(conn, address[0]), daemon=True).start()

    def _handle_connection(self, conn: socket.socket, host: str) -> None:
        with conn:
            conn.settimeout(30)
            try:
                header = read_frame(conn)
                self._remember_header(header, host, manual=header.get("type") == "hello")
                kind = header.get("type")
                if kind == "hello":
                    send_frame(conn, self._identity("hello"))
                elif kind == "chat":
                    text = header.get("text")
                    if not isinstance(text, str) or not (0 < len(text) <= MAX_MESSAGE):
                        raise ValueError("Invalid chat message")
                    self._emit("chat_received", peer_id=header["peer_id"], name=header["name"], text=text)
                    send_frame(conn, {"type": "ack"})
                elif kind == "file_offer":
                    self._receive_file(conn, header)
                else:
                    raise ValueError("Unknown protocol message")
            except (OSError, EOFError, ValueError, UnicodeError, json.JSONDecodeError) as exc:
                if not self.stopping.is_set():
                    self._emit("error", message=f"Incoming connection from {host}: {exc}")

    def _discovery_loop(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            sock.bind(("0.0.0.0", self.discovery_port))
            sock.settimeout(0.5)
            self.discovery_socket = sock
            next_announce = 0.0
            while not self.stopping.is_set():
                now = time.monotonic()
                if now >= next_announce:
                    payload = json.dumps(self._identity("announce")).encode("utf-8")
                    try:
                        sock.sendto(payload, ("255.255.255.255", self.discovery_port))
                    except OSError as exc:
                        self._emit("error", message=f"Peer discovery: {exc}")
                    next_announce = now + DISCOVERY_INTERVAL
                    self._expire_peers(now)
                try:
                    packet, address = sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    break
                try:
                    announcement = json.loads(packet.decode("utf-8"))
                    if isinstance(announcement, dict) and announcement.get("type") == "announce":
                        self._remember_header(announcement, address[0])
                except (ValueError, UnicodeError, json.JSONDecodeError):
                    continue
        except OSError as exc:
            if not self.stopping.is_set():
                self._emit("error", message=f"Peer discovery unavailable: {exc}. Use Add by IP.")
        finally:
            sock.close()
            self.discovery_socket = None

    def _expire_peers(self, now: float) -> None:
        with self.lock:
            stale = [key for key, peer in self.peers.items() if not peer.manual and now - peer.last_seen > PEER_TIMEOUT]
            for key in stale:
                del self.peers[key]
        if stale:
            self._emit("peers_changed")

    def _run_async(self, action: Callable, *args: object) -> None:
        threading.Thread(target=action, args=args, daemon=True).start()

    def _target(self, peer_id: str) -> Peer:
        with self.lock:
            peer = self.peers.get(peer_id)
        if peer is None:
            raise ValueError("Select an available peer first")
        return peer

    def add_by_ip(self, host: str, port: int) -> None:
        self._run_async(self._add_by_ip, host, port)

    def _add_by_ip(self, host: str, port: int) -> None:
        try:
            with socket.create_connection((host, port), timeout=10) as conn:
                conn.settimeout(10)
                send_frame(conn, self._identity("hello"))
                reply = read_frame(conn)
                if reply.get("type") != "hello":
                    raise ValueError("That address did not answer as a peer")
                self._remember(str(reply["peer_id"]), str(reply["name"]), conn.getpeername()[0], int(reply["port"]), manual=True)
        except (OSError, ValueError, EOFError, KeyError) as exc:
            self._emit("error", message=f"Cannot add {host}:{port}: {exc}")

    def send_chat(self, peer_id: str, text: str) -> None:
        if not (0 < len(text) <= MAX_MESSAGE):
            self._emit("error", message=f"Messages must be 1 to {MAX_MESSAGE} characters")
            return
        self._run_async(self._send_chat, peer_id, text)

    def _send_chat(self, peer_id: str, text: str) -> None:
        try:
            peer = self._target(peer_id)
            with socket.create_connection((peer.host, peer.port), timeout=10) as conn:
                conn.settimeout(30)
                send_frame(conn, self._identity("chat", text=text))
                if read_frame(conn).get("type") != "ack":
                    raise ValueError("Peer did not acknowledge message")
            self._emit("chat_sent", peer_id=peer_id, name=peer.name, text=text)
        except (OSError, ValueError, EOFError) as exc:
            self._emit("error", message=f"Message failed: {exc}")

    def send_file(self, peer_id: str, path: str) -> None:
        self._run_async(self._send_file, peer_id, path)

    def _send_file(self, peer_id: str, path: str) -> None:
        try:
            peer = self._target(peer_id)
            source = Path(path)
            size = source.stat().st_size
            transfer_id = uuid.uuid4().hex
            with socket.create_connection((peer.host, peer.port), timeout=10) as conn:
                conn.settimeout(130)  # The receiver may take time to choose a save location.
                send_frame(conn, self._identity("file_offer", filename=source.name, size=size, transfer_id=transfer_id))
                response = read_frame(conn)
                if response.get("type") != "file_reply":
                    raise ValueError("Unexpected response to file offer")
                if not response.get("accepted"):
                    self._emit("file_rejected", name=source.name, peer=peer.name)
                    return
                conn.settimeout(30)
                digest = hashlib.sha256()
                sent = 0
                last_update = 0.0
                with source.open("rb") as file:
                    while sent < size:
                        chunk = file.read(min(CHUNK_SIZE, size - sent))
                        if not chunk:
                            raise EOFError("Source file changed during transfer")
                        conn.sendall(chunk)
                        digest.update(chunk)
                        sent += len(chunk)
                        now = time.monotonic()
                        if now - last_update > 0.1 or sent == size:
                            self._emit("file_progress", transfer_id=transfer_id, name=source.name, done=sent, total=size, direction="Sending")
                            last_update = now
                conn.sendall(digest.hexdigest().encode("ascii"))
                result = read_frame(conn)
                if result.get("type") != "file_done" or not result.get("ok"):
                    raise ValueError(str(result.get("reason", "Peer could not save the file")))
                self._emit("file_sent", name=source.name, peer=peer.name, size=size)
        except (OSError, ValueError, EOFError) as exc:
            self._emit("error", message=f"File transfer failed: {exc}")

    def _receive_file(self, conn: socket.socket, offer: dict) -> None:
        try:
            size = offer["size"]
            raw_name = offer["filename"]
            if not isinstance(size, int) or size < 0 or not isinstance(raw_name, str):
                raise ValueError("Invalid file offer")
            filename = Path(raw_name.replace("\\", "/")).name
            if not filename or filename in {".", ".."} or len(filename) > 255:
                raise ValueError("Invalid filename")
        except (KeyError, ValueError) as exc:
            send_frame(conn, {"type": "file_reply", "accepted": False})
            raise ValueError("Invalid file offer") from exc

        answer: queue.Queue[str | None] = queue.Queue(maxsize=1)
        self._emit("file_offer", name=filename, size=size, sender=str(offer["name"]), answer=answer)
        try:
            chosen = answer.get(timeout=120)
        except queue.Empty:
            chosen = None
        if not chosen:
            send_frame(conn, {"type": "file_reply", "accepted": False})
            return

        destination = Path(chosen)
        temporary = destination.with_name(destination.name + f".part-{uuid.uuid4().hex[:8]}")
        try:
            with temporary.open("xb") as file:
                send_frame(conn, {"type": "file_reply", "accepted": True})
                digest = hashlib.sha256()
                remaining = size
                last_update = 0.0
                while remaining:
                    chunk = conn.recv(min(CHUNK_SIZE, remaining))
                    if not chunk:
                        raise EOFError("Connection closed during file transfer")
                    file.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                    now = time.monotonic()
                    if now - last_update > 0.1 or remaining == 0:
                        self._emit("file_progress", transfer_id=str(offer.get("transfer_id", "")), name=filename, done=size - remaining, total=size, direction="Receiving")
                        last_update = now
            expected = read_exact(conn, 64).decode("ascii")
            if digest.hexdigest() != expected:
                raise ValueError("SHA-256 checksum mismatch")
            os.replace(temporary, destination)
            send_frame(conn, {"type": "file_done", "ok": True})
            self._emit("file_received", name=filename, sender=str(offer["name"]), path=str(destination), size=size)
        except (OSError, EOFError, ValueError, UnicodeError) as exc:
            temporary.unlink(missing_ok=True)
            try:
                send_frame(conn, {"type": "file_done", "ok": False, "reason": str(exc)})
            except OSError:
                pass
            raise
