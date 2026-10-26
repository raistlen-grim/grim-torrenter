# Security Policy

## Reporting a vulnerability

Please **don't report security issues in public GitHub issues**, pull requests or discussions.

Instead, report them privately through GitHub: go to the repository's **Security** tab and click
**Report a vulnerability**. Only the maintainer can see the report, and it stays private until a
fix is ready and an advisory is published.

Helpful things to include:

- what the issue is and what an attacker could do with it
- steps to reproduce, or a proof of concept
- the version affected (shown at the bottom of the web UI, or the image tag you're running)

## What's in scope

Anything in this repository or the published `ghcr.io/raistlen-grim/grim-torrenter` image, for
example:

- bypassing or weakening the web UI's authentication
- the web UI or REST API exposing files or data it shouldn't
- files written outside the download, config or watch directories (path traversal from torrent
  metadata, the watch folder, or the API)
- a malicious peer, tracker or `.torrent` file causing crashes, runaway memory/CPU/disk use, or
  code execution
- traffic escaping a configured proxy

Problems in third-party dependencies are welcome too, if they affect GrimTorrenter.

## Supported versions

Only the **latest release** gets security fixes. Please check that the issue still happens on the
newest image before reporting.

## Response

This is a one-person project maintained in spare time, so responses are best effort. I'll aim to
acknowledge a report within a week and keep you updated as a fix progresses. Credit is given in
the advisory unless you'd rather stay anonymous.
