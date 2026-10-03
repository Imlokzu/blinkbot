# Public API tunnel

The phone uses `https://api-bot.waveio.me` and sends its revocable device token
with each authenticated API request. The existing `klodbot` Cloudflare Tunnel
connects that hostname to the same Virtual Bot process at `127.0.0.1:8100`.

## Required configuration

A successful QR response from the PC does not prove its hostname exists in DNS.
The public route needs both a proxied CNAME to the existing tunnel and an ingress
rule before the catch-all. The local configuration lives outside this repository,
in `~/.cloudflared/klodbot.yml`. Keep tunnel credentials outside the repository.

```yaml
# Merge this entry into the existing tunnel's ingress list before the catch-all.
ingress:
  - hostname: api-bot.waveio.me
    service: http://127.0.0.1:8100
  # Preserve the other hostnames and their existing origin settings here.
  - service: http_status:404
```

Preserve the API hostname in the HTTP Host header. The backend uses that host
to apply its mobile-only route allowlist and mandatory device authentication.
An existing web-dashboard rule may override its own Host header; do not copy
that override to the mobile API rule or make it a global origin setting.

The backend reserves `api-bot.waveio.me` by default. For a different deployment,
set `MOBILE_API_ORIGIN` to the matching HTTPS origin and issue fresh pairing QRs
with that origin. A browser-login interstitial is not an API response.

Validate configuration and routing before loading it into the tunnel service:

```sh
cloudflared tunnel --config ~/.cloudflared/klodbot.yml ingress validate
cloudflared tunnel --config ~/.cloudflared/klodbot.yml ingress rule \
  https://api-bot.waveio.me/api/mobile/capabilities
cloudflared tunnel route dns klodbot api-bot.waveio.me
```

The macOS service is `gui/<uid>/me.waveio.klodbot-tunnel`. For a live update,
start a temporary replica with the new configuration, wait for its connections,
restart the persistent service, verify its new connections, and stop the replica.
Keep the existing dashboard hostname and routes intact.

## Readiness and recovery

- DNS must resolve the hostname; `NXDOMAIN` means the phone cannot contact it.
- A normal HTTPS request without a device token to `/api/mobile/capabilities`
  returns HTTP 401 with `mobile_auth_required`. This verifies the public route
  reaches the correct authenticated backend; it does not verify a phone login.
- DNS resolvers can retain an earlier negative answer after a record is created.
  Compare the local result with an independent resolver before changing app code.
- Create a fresh QR in PC Settings / Devices after the route is ready. Pairing
  codes expire after five minutes and are single-use.

## Deployment check, 2026-10-03

The reported connection failure was traced to an absent DNS record and absent
API ingress rule. Both were added to the existing bot tunnel. Ingress validation
passed; public DNS returned Cloudflare addresses; HTTPS with normal certificate
validation reached the backend and returned the expected unauthenticated 401.
The persistent LaunchAgent was restarted with the updated configuration. Its
temporary rollout replica was stopped. No authentication policy was weakened.
The original local configuration was backed up next to the configuration file.

References: [DNS routing](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/dns/)
and [configuration and rollout](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/local-management/configuration-file/).
