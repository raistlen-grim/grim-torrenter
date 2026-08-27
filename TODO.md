# GrimTorrenter — TODO

Running list of ideas/requests to come back to later. Not commitments, not scoped, not
scheduled - just a place to jot something down before it's forgotten. Add items freely;
nothing here gets acted on until it's explicitly picked up.

- Notification service (emails, or something else yet to be defined)
- Run a user-configured script automatically when a torrent completes
- Backend health/degraded-state surfaced in the UI - today things like the DHT node or peer
  server failing to bind at startup only show up in the server log (see design_docs/0058),
  so a user has no way to know service is degraded short of reading logs. Some kind of
  healthcheck/status the UI can show (e.g. in the header/footer chrome) so a failure like
  that is visible rather than silently degrading service. Might overlap with the
  notification-service idea above, might not - worth scoping separately when picked up.
