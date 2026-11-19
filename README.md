# LAN Peer Chat

A terminal-based peer-to-peer chat and file sharing app for computers on the same local area network (LAN). The computers can use Wi-Fi, Ethernet, or a phone hotspot. No central chat server or internet connection is required.

## Requirements

- Python 3.10 or later (standard library only)
- Two computers on the same LAN
- TCP port 5001 and UDP port 5000 allowed by the receiving computer's firewall

## Chat between two computers

Copy this folder to both computers. Open a terminal in the folder and run the app with a different name on each computer:

```bash
python3 run.py --name Alice
```

```bash
python3 run.py --name Bob
```

On Windows, use `py run.py --name Alice` if `python3` is unavailable. Once a peer appears, that peer is selected automatically if it is the only one. Type a message and press Enter. If several peers appear, type `/peers` and `/select NUMBER` to choose one.

If automatic discovery does not work, type `/connect IP` on one computer, replacing `IP` with the other computer's LAN IPv4 address (for example, `/connect 192.168.1.5`). Find the address with `ip -4 addr` on Linux, `ipconfig` on Windows, or `ifconfig` on macOS. Use `/connect IP:PORT` when the other app listens on a nondefault TCP port.

Type `/help` to see every command.

## Send a file

Select a peer, then type `/file PATH`, for example:

```text
/file /home/me/Documents/report.pdf
```

The receiver sees a numbered offer. They type `/accept NUMBER` to save it in `received_files`, or `/reject NUMBER` to decline it. `/accept NUMBER PATH` saves it to a chosen path. Existing files are not overwritten; the app adds a number to the new filename. Received files are verified with SHA-256 before being moved into place.

## Try two copies on one computer

Open two terminals in this folder:

```bash
python3 run.py --name Alice --port 5001 --no-discovery
```

```bash
python3 run.py --name Bob --port 5002 --no-discovery
```

In either terminal, type `/connect 127.0.0.1:PORT` using the other terminal's port. Both peers will then appear in each other's lists.

## Java version

The `java/` folder contains a Java port with the same commands and the same wire protocol, so Java and Python peers can chat and exchange files with each other. It needs Java 21 or later and uses only the JDK at runtime.

Build and run with Maven (this also runs the tests):

```bash
cd java
mvn package
java -jar target/lanpeer.jar --name Alice
```

Or without Maven:

```bash
cd java
javac -d out $(find src/main/java -name "*.java")
java -cp out lanpeer.Main --name Alice
```

On Windows PowerShell, compile with `javac -d out (Get-ChildItem -Recurse src\main\java -Filter *.java).FullName`.

| Java class | Role |
| --- | --- |
| `lanpeer.Main` | Entry point and argument handling |
| `lanpeer.cli.TerminalApp` | Terminal commands; one UI thread fed by an event queue |
| `lanpeer.net.PeerNode` | TCP listener, UDP discovery, chat and file transfer |
| `lanpeer.net.PeerEvent` | Sealed set of typed events the node reports |
| `lanpeer.net.FileOffer` | An incoming offer the user accepts or rejects |
| `lanpeer.net.Connection` | Length-prefixed JSON framing over a socket |
| `lanpeer.json.Json` | Small dependency-free JSON reader and writer |

Differences from the Python version: an offer that times out (after 120 seconds) is removed from the list with a notice, and incoming filenames containing characters Windows forbids (such as `:`) or reserved names (such as `CON`) are refused.

## How it works

| Feature | Network/OS mechanism |
| --- | --- |
| Peer discovery | UDP broadcast on port 5000 |
| Manual connection | TCP hello request to the entered IP and port |
| Chat | One TCP connection per message, with an acknowledgement |
| File sharing | TCP offer, accept/reject, chunked bytes, SHA-256 integrity check |
| Terminal input | Background input thread and an event queue |

TCP messages use a 4-byte length followed by UTF-8 JSON. File bytes follow an accepted offer, followed by a 64-character SHA-256 digest. The receiver writes to a temporary file and moves it into place only after the checksum matches.

This class project is intended for a trusted LAN. Messages and files are **not encrypted**, so do not use it for private data on an untrusted network. Some guest Wi-Fi networks isolate devices and prevent direct connections; use a hotspot or a network that permits devices to reach each other.
