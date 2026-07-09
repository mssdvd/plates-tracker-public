# Deploying with podman

One image serves the API and the built webapp (the Go binary's `WEB_DIR` mode). SQLite lives on
the `plates-data` volume. The only required config is `API_TOKEN`.

## Build

```sh
podman build -f deploy/Containerfile -t localhost/plates-tracker .   # from the repo root
```

## Run — pick one

**Quadlet (podman-native, systemd-managed — recommended):** see the header of
[`plates-tracker.container`](plates-tracker.container).

**Compose:**

```sh
cd deploy
echo 'API_TOKEN=change-me' > .env
podman-compose up -d --build
```

**Plain podman:**

```sh
podman run -d --name plates-tracker -p 8000:8000 \
  -e API_TOKEN=change-me -v plates-data:/data localhost/plates-tracker
```

Then open `http://<host>:8000`, paste the token, and point the phone app's Settings at the same
URL + token.

## Notes

- **TLS:** the container speaks plain HTTP. On a LAN or tailnet that's fine; anything else must
  sit behind an HTTPS reverse proxy (Caddy makes this a two-liner) — see the privacy notes in the
  top-level README.
- **Backup:** the database is one file — `podman volume export plates-data > plates-data.tar`.
- The server also runs fine uncontainered: `API_TOKEN=… WEB_DIR=webapp/dist go run ./server`.
