#!/bin/sh
# Container entrypoint: runs GrimTorrenter as PUID:PGID instead of root, so the files it
# creates on the mounted volumes belong to a real user on the host. See design_docs/0085.
set -e

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

case "$PUID$PGID" in
  *[!0-9]*)
    echo "grimtorrenter: PUID and PGID must be numeric (got PUID='$PUID' PGID='$PGID')" >&2
    exit 1
    ;;
esac

# Started with `docker run --user ...` (or on a platform that forbids root): we can't switch
# user, and don't need to - just run as whoever we already are.
if [ "$(id -u)" != "0" ]; then
  exec java -jar /app/quarkus-run.jar "$@"
fi

mkdir -p /app/config /app/downloads /app/watch

# The config directory is this app's own (settings, password hash, per-torrent state, event
# log), so it is safe to take ownership of all of it - and necessary when it was first written
# by an earlier, root-running version of this image.
chown -R "$PUID:$PGID" /app/config

# Downloads and the watch folder are often shared with other software, so their ownership is
# never changed here. Say so plainly if this user can't write to them, rather than leaving it
# to surface later as a per-torrent I/O error.
for dir in /app/downloads /app/watch; do
  if ! su-exec "$PUID:$PGID" test -w "$dir"; then
    echo "grimtorrenter: WARNING - $dir is not writable by PUID=$PUID PGID=$PGID." >&2
    echo "grimtorrenter:   Fix the ownership on the host (chown -R $PUID:$PGID <host path>)," >&2
    echo "grimtorrenter:   or set PUID/PGID to the user that owns it." >&2
  fi
done

# HOME: a numeric uid with no passwd entry has none, and the JVM wants somewhere to call home.
export HOME=/app/config
exec su-exec "$PUID:$PGID" java -jar /app/quarkus-run.jar "$@"
