# GrimTorrenter — TODO

Running list of ideas/requests to come back to later. Not commitments, not scoped, not
scheduled - just a place to jot something down before it's forgotten. Add items freely;
nothing here gets acted on until it's explicitly picked up.

- Notification service (emails, or something else yet to be defined)
- Watch folder — auto-add `.torrent` files dropped into a monitored directory. Should extend
  [[0055-library-events]]'s `ADDED` event with an add-source (manual upload vs. watch folder vs.
  magnet) and record a failed auto-add (malformed file, duplicate, permissions error) as its own
  event, since an unattended failure would otherwise be invisible.
