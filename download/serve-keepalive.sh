#!/usr/bin/env bash
# Keeps the APK download server alive for the duration of a session.
#
# Nothing in this environment supervises a foreground process, so the server dies with whatever
# terminal started it and the download link silently 404s. This watches the port and restarts the
# server when it is gone.
#
# Run detached so it outlives the shell that starts it:
#   setsid nohup ./serve-keepalive.sh > /dev/null 2>&1 < /dev/null &

set -u

cd "$(dirname "$0")" || exit 1

PORT="${PORT:-12000}"
CHECK_INTERVAL_SECONDS=5

is_listening() {
  # A listener is what makes the page reachable; a running process is not enough, since the
  # server can be up but wedged on a port it failed to bind.
  python3 - "$PORT" <<'PY'
import socket, sys
sock = socket.socket()
sock.settimeout(1)
try:
    sock.connect(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
}

while true; do
  if ! is_listening; then
    echo "[keepalive] port $PORT is down, restarting server at $(date -u +%H:%M:%S)" >> server.log
    setsid nohup python3 -u serve.py >> server.log 2>&1 < /dev/null &
  fi
  sleep "$CHECK_INTERVAL_SECONDS"
done