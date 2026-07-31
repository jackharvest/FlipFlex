#!/bin/bash
# Put the handset on the LAN Plex server over the USB cable.
#
# This exists because the phone's Wi-Fi is not dependable enough to test
# against. It associates, never gets a DHCP lease, and Android tears the
# connection down about eighteen seconds later with
# `CTRL-EVENT-DISCONNECTED reason=3 locally_generated=1` -- the phone
# deauthenticating itself, not the access point. It then falls back to LTE, at
# which point a 192.168.x server is simply unreachable and every test turns
# into a network diagnosis. See docs/phase2-playback.md.
#
# Two pieces:
#   - a TCP relay on this Mac, listening on 127.0.0.1:32400
#   - `adb reverse tcp:32400 tcp:32400`, so the phone's own localhost:32400
#     arrives here
# The app is then pointed at http://127.0.0.1:32400. Nothing else changes, and
# no cellular data is spent.
#
#   tools/usb-plex.sh <server-ip> [start|stop]
#
# `stop` puts the stored server URI back to whatever it was before, from the
# copy this script saves. Run it when you are finished, or the app will keep
# looking for a server down a cable that is no longer plugged in.
set -e

SERVER_IP=${1:?usage: usb-plex.sh <server-ip> [start|stop]}
MODE=${2:-start}
PKG=com.github.jackharvest.flipflex
PREFS=/data/data/$PKG/shared_prefs/flipflex.xml
SAVED=${TMPDIR:-/tmp}/flipflex-prefs-before-usb.xml
PIDFILE=${TMPDIR:-/tmp}/flipflex-usb-relay.pid

stop_relay() {
	[ -f "$PIDFILE" ] || return 0
	kill "$(cat "$PIDFILE")" 2>/dev/null || true
	rm -f "$PIDFILE"
}

if [ "$MODE" = stop ]; then
	stop_relay
	adb reverse --remove tcp:32400 2>/dev/null || true
	if [ -f "$SAVED" ]; then
		adb shell am force-stop $PKG
		adb push "$SAVED" /data/local/tmp/ff-restore.xml >/dev/null
		adb shell run-as $PKG cp /data/local/tmp/ff-restore.xml $PREFS
		adb shell rm -f /data/local/tmp/ff-restore.xml
		echo "restored the server URI from $SAVED"
	else
		echo "no saved prefs at $SAVED -- set the server by hand in Settings" >&2
	fi
	exit 0
fi

# Save the prefs before touching them. The stored server URI is a plex.direct
# hostname with an account-specific hash in it and is not reconstructable by
# hand, so losing it means re-picking the server on a phone with no touchscreen.
adb shell run-as $PKG cat $PREFS >"$SAVED"
echo "saved prefs to $SAVED"

stop_relay
python3 - "$SERVER_IP" <<'PY' &
import socket, sys, threading
target = (sys.argv[1], 32400)

def pump(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

def serve(client):
    try:
        up = socket.create_connection(target, timeout=10)
    except OSError:
        client.close()
        return
    threading.Thread(target=pump, args=(client, up), daemon=True).start()
    pump(up, client)

srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', 32400))
srv.listen(64)
while True:
    conn, _ = srv.accept()
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
PY
echo $! >"$PIDFILE"
sleep 1

adb reverse tcp:32400 tcp:32400

# sed on the stored URI rather than writing a whole prefs file, so the token,
# the profile and the hidden libraries all survive untouched.
adb shell am force-stop $PKG
sed 's|<string name="server_uri">[^<]*</string>|<string name="server_uri">http://127.0.0.1:32400</string>|' \
	"$SAVED" >"${SAVED}.usb"
adb push "${SAVED}.usb" /data/local/tmp/ff-usb.xml >/dev/null
adb shell run-as $PKG cp /data/local/tmp/ff-usb.xml $PREFS
adb shell rm -f /data/local/tmp/ff-usb.xml

echo "relay up: phone -> usb -> $SERVER_IP:32400"
echo "run 'tools/usb-plex.sh $SERVER_IP stop' when you are done"
