# Required environment variables

What each service needs in production to accept identity-service access tokens. Since the
13 Nov 2026 auth change, identity-service signs RS256 access tokens with `JWT_PRIVATE_KEY`; every
service that verifies them needs the matching public key, and every service that polls the
token-revocation feed needs identity's URL and the shared internal secret.

**Source of truth:** set these exactly as `api-gateway` gets them in `PRD/compose.yml` (same
`${VAR}` references or the same `env_file`). Never write their values into compose files, code,
logs or tickets.

## helpdesk-service

| Variable | Required | Value | If missing |
|---|---|---|---|
| `JWT_PUBLIC_KEY` | yes | Same as api-gateway | Startup fails (since this release) |
| `INTERNAL_SERVICE_SECRET` | yes | Same as identity-service and api-gateway | Startup fails |
| `IDENTITY_SERVICE_URL` | yes | `http://identity-service:8081` in Compose | Startup fails |
| `SPRING_PROFILES_ACTIVE` | yes | `prod` | Defaults to `dev`, which honours role names in tokens without a `permissions` claim and turns fail-closed revocation off |
| `JWT_PRIVATE_KEY` | no | Same as identity-service | Only needed to mint tokens (integration tests); verification is unaffected |
| `HELPDESK_DB_URL`, `HELPDESK_DB_USERNAME`, `HELPDESK_DB_PASSWORD` | yes (prod profile) | | Startup fails |

`JWT_SECRET` is no longer read: helpdesk-service signs and verifies RS256 only, like the other
services. It can be removed from the service's environment.

Before this release, a missing `JWT_PUBLIC_KEY` / `INTERNAL_SERVICE_SECRET` / `IDENTITY_SERVICE_URL`
let the service start "healthy" and answer every request with 401 `Invalid or expired token`.
Now:

- the service refuses to start and names the missing variables (`RequiredSecurityConfigValidator`;
  only the `dev` and `test` profiles switch the check off);
- `/actuator/health` is DOWN until the log line `Token revocations loaded` appears
  (`tokenRevocation` health component);
- every rejected token is logged at WARN as `Rejected bearer token: reason=<code>` with one of
  `expired`, `bad_signature`, `bad_claims`, `revoked`, `revocations_not_loaded`. The token is never
  logged.

| Log reason | Usual cause |
|---|---|
| `bad_signature` on every request | `JWT_PUBLIC_KEY` differs from the gateway's |
| `revocations_not_loaded` on every request | Revocation poll failing: check the `Token revocation poll ...` WARN lines (wrong `IDENTITY_SERVICE_URL`, or HTTP 401/403 = wrong `INTERNAL_SERVICE_SECRET`) |
| `expired` | Normal for stale sessions; the frontend refreshes |
| `revoked` | User signed out, was deactivated or offboarded |

## Other services that verify identity tokens

These services verify identity's RS256 tokens and/or poll the revocation feed in code (as of this
change), with dev-only fallbacks (`localhost:8081`, the dev public key) baked into their
`application.yml`. Each must get the same three variables in `PRD/compose.yml`, or it fails the same
way helpdesk did:

| Service (repo) | `JWT_PUBLIC_KEY` | `INTERNAL_SERVICE_SECRET` | `IDENTITY_SERVICE_URL` |
|---|---|---|---|
| api-gateway (`f_verse_api`) | yes | yes | yes |
| asset-management (`AssetsManager`) | yes | yes | yes |
| approval-service (`f_verse_approval`) | yes | yes | yes |
| b2b backend (`f_verse_b2b`) | yes | yes | yes |
| forms-service (`f_verse_forms`) | yes | yes | yes |
| notifications-service (`f_verse_notifications`) | yes | yes | yes |
| recruitment-service (`f_verse_recruitment`) | yes | yes | yes |
| identity-service (`f_verse_identity`) | yes, plus `JWT_PRIVATE_KEY` | yes | n/a |

Not covered here, needs separate attention:

- **org-service (`f_verse_org`)** still verifies only HS256 tokens signed with `JWT_SECRET`. It
  cannot verify identity's RS256 tokens whatever the environment, so callers come through as
  anonymous. This needs a code change, not configuration.
- **crm-service (`f_verse_crm`)** was not available when this was written. Its symptom
  (requests anonymous, then denied by `@PreAuthorize`) matches org-service's, so check its token
  verification code as well as its environment.

## Deploy-time check

Until the deploy pipeline enforces it, run this on the host before `docker compose up` (it prints
variable names only, never values):

```sh
docker compose -f PRD/compose.yml config --format json \
  | jq -r '.services["helpdesk-service"].environment // {} | keys[]' \
  | sort > /tmp/helpdesk-env-names
for v in JWT_PUBLIC_KEY INTERNAL_SERVICE_SECRET IDENTITY_SERVICE_URL SPRING_PROFILES_ACTIVE; do
  grep -qx "$v" /tmp/helpdesk-env-names || { echo "helpdesk-service is missing $v"; exit 1; }
done
```

`docker compose config` resolves `${VAR}` references, so an unset variable shows up as an empty
string rather than missing. The service's own startup check catches that case.
