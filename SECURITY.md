# Security policy

## What counts
- Any way to make the app send a **non-read-only request** to a vehicle (a bypass of `core/RequestGuard.kt`), for example
  via a crafted signalset, overlay, profile or adapter response.
- Leaking MQTT credentials or vehicle data (VIN, location) beyond the configured broker.
- Crashes from crafted OBDb/overlay JSON that could be used to persist bad state.

## Reporting
Please **don't open a public issue**. Use GitHub's private reporting:
[Report a vulnerability](../../security/advisories/new). You should get a reply within a week.

## Supported versions
Only the latest release receives fixes.

## Notes for users
- The MQTT password and HA token are stored encrypted (AES-256-GCM, key held in the Android Keystore), and the app disables
  backups. Anyone with your unlocked phone and debugging access is still out of scope.
- Use **https** for the HA URL. Over plain http the token is sent in clear.
- Reach your MQTT broker over a VPN (Tailscale/NetBird/WireGuard). Don't expose port 1883 to the internet.
- The MQTT link is plain TCP, so the VPN provides the encryption.
