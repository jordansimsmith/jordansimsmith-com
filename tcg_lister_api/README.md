# TCG lister scripts

This directory groups self-contained local scripts for managing TCG workflows.

## Scripts

- [List cards](scripts/list/README.md): analyze ManaBox scans and create or update FetchTCG listings.
- [Analyze pricing](scripts/pricing/README.md): compare active owned listings with exact New Zealand competition and report portfolio pricing signals.
- [Reprice listings](scripts/reprice/README.md): exhaustively apply the competitive pricing framework to active Fetch TCG listings.
- [Scan cards](scripts/scan/README.md): compare local MTG photo-recognition methods against a shared labelled dataset.

## Shared Fetch authentication

The authenticated scripts continue to accept a one-hour Firebase ID token through
`FETCHTCG_TOKEN`. Mint one immediately before a run from the longer-lived
`FETCHTCG_REFRESH_TOKEN`:

```shell
token="$(bazel run //tcg_lister_api:fetchtcg-mint-token)" &&
FETCHTCG_TOKEN="$token" bazel run //tcg_lister_api:fetchtcg-bulk-analyze -- \
  "/path/to/manabox-scan.csv"
unset token
```

Set `FETCHTCG_REFRESH_TOKEN` through a hidden prompt or local secret manager rather
than writing its value into a command, shell history, profile, or repository file.
The minter prints only the raw ID token when stdout is captured and refuses to print
it directly to an interactive terminal. It sends the refresh credential only to
Firebase's fixed HTTPS token endpoint, does not forward browser cookies, and does
not persist either credential.

Firebase documents this token exchange for non-browser REST clients and limits it
to 18,000 exchanges per project per minute. One exchange per local run is negligible.
If Fetch applies a browser-referrer or IP restriction to its public Firebase API key,
the minter fails without spoofing browser headers or bypassing that restriction.

The minted token is not renewed inside an already-running script. A run that reaches
another authenticated request after the token's one-hour lifetime stops and must be
reminted and resumed. Firebase may reject a refresh credential after account changes,
revocation, or API-key restriction changes. Its response can also contain a replacement
refresh token; the environment-only minter deliberately does not persist it, so a later
run must use a newly extracted credential if the original stops working.

Treat the refresh credential and any HAR containing it like an account password.
Delete diagnostic HAR files when they are no longer needed. If one has been shared,
change the Fetch password to revoke existing Firebase refresh sessions. This
authentication flow does not remove Fetch's restrictions on automated access.
