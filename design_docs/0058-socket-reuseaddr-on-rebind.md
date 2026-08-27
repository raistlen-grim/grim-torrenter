# 0058 — SO_REUSEADDR on the DHT and peer-server sockets

**Status:** Accepted

## Decision

`DhtNode`'s UDP socket and `PeerServer`'s TCP socket now call `setReuseAddress(true)` before
binding, instead of using `new DatagramSocket(port)` / `new ServerSocket(port)` directly.

## Why

Reported symptom: intermittent `BindException: Address already in use` on both sockets at
startup, transient - by the time it was investigated moments later, nothing else was bound to
the port (`lsof`/`ss` showed it free, and only one Quarkus dev process was running). This is
the classic signature of OS-level socket lingering: closing a socket doesn't necessarily make
the OS immediately willing to hand the same port back out, even to the same process, for a
short window afterward - independent of how fast `TorrentEngine.shutdown()` → `DhtNode.close()`
/ `PeerServer.close()` return (both already close synchronously, so the fix isn't there).

The trigger in this project's actual workflow is Quarkus dev mode's live-reload: it tears down
the whole application context (firing `ShutdownEvent`, which `TorrentEngineLifecycle` already
wires to `TorrentEngine.shutdown()`) and immediately recreates it on every backend source
change - a much tighter close-then-rebind cycle than a normal process restart, exactly the
case `SO_REUSEADDR` exists for.

`new DatagramSocket(port)` / `new ServerSocket(port)` (the convenience constructors this code
used before) bind immediately inside the constructor, leaving no opportunity to call
`setReuseAddress(true)` first - it has no effect if called after bind. Both now use the
unbound-constructor + `setReuseAddress(true)` + explicit `bind()` pattern instead.

## Scope

Two constructors changed (`DhtNode(NodeId, int)`, `PeerServer`'s 4-arg constructor); the
sockets' actual behavior, `close()` handling, and every other constructor overload are
unaffected. No config/API surface change.

## Stability ([[0051-stability-as-a-standing-consideration]])

`SO_REUSEADDR` only affects whether a bind is *allowed* to succeed against a port in a
transitional OS state - it does not weaken the bind itself (a genuinely still-in-use port,
e.g. another real process actually listening, still fails to bind exactly as before) and
introduces no new resource, thread, or lock. Purely a startup-race fix.
