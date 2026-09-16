#!/usr/bin/env bash
# Starts the APK download server on every exposed port and keeps it alive.
#
# The container is recycled without warning (a restart replaces PID 1 and every process with it),
# so the server is started detached and watched. The keepalive cannot survive the container being
# replaced — nothing in this image outlives a recycle — but it covers the ordinary case where the
# server dies while the container lives on, which is what makes a link silently stop answering.
#
# Usage: ./serve-all.sh

set -u

cd "$(dirname "$0")" || exit 1

# One server per exposed host, so either link works on its own.
PORTS="${PORTS:-12000 12001}"

for port in $PORTS; do
  if python3 - "$port" <<'PY'
import socket, sys
sock = socket.socket(); sock.settimeout(1)
try:
    sock.connect(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
  then
    echo "[serve-all] port $port already serving"
  else
    PORT="$port" setsid nohup python3 -u serve.py >> "server-$port.log" 2>&1 < /dev/null &
    echo "[serve-all] started port $port"
  fi
done

# A watcher per port, so a server that dies mid-session is restarted.
for port in $PORTS; do
  PORT="$port" setsid nohup ./serve-keepalive.sh >> "keepalive-$port.log" 2>&1 < /dev/null &
done

sleep 2
for port in $PORTS; do
  if curl -s --max-time 5 -o /dev/null -w "" "http://127.0.0.1:$port/ZLT-M90-Plus.apk"; then
    echo "[serve-all] port $port answers"
  else
    echo "[serve-all] port $port did NOT answer" >&2
  fi
done