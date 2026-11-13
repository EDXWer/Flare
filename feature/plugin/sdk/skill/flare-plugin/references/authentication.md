# Detection and authentication recipes

These recipes describe the supported interaction kinds. Website paths and
helpers in sketches are adaptation points, not universal endpoints. Use the
[GitHub template](../assets/github-template/plugin.js) for a complete token-login
implementation and [Pixelfed](../examples/pixelfed/plugin.js) for complete OAuth.
Never accept a submitted token/password as proof of identity without verifying
it against the website.

## Detection

Declare `platform.detector: { "priority": 0 }` only when implementing
`detector.detect(request, context)`. Inspect a documented endpoint on the
candidate origin. Return `match: "none"` when evidence does not match;
`"exact"` for the intended software and `"compatible"` only for known compatible
behavior. Return the canonical HTTPS origin and optional software/instance data:

```js
// Result after verifying the website's software identity.
const detected = {
  match: "exact",
  canonicalOrigin: context.origin,
  software: "ExampleSoftware",
  instance: { domain: context.origin.slice("https://".length), title: "Example" },
};
```

Do not claim every reachable host. Detection has no credential access and may
request only the candidate origin. Omit the declaration and method if explicit
platform selection is sufficient, as in the template.

## State transitions

The method key is the manifest method **ID**, not necessarily its interaction
name. `id: "token"` registers `login.token.begin`, for example.

| Interaction | Calls | Allowed outcome |
| --- | --- | --- |
| OAuth | begin → browser callback → resume | begin: externalBrowser or success; resume: success |
| Password | begin(values) | success |
| CredentialImport | begin(values) | success |
| Form | begin(values) | success |
| WebCookie | begin → repeated check(snapshot) | begin: webCookie; check: pending or success |
| QrCode (API v2) | begin → repeated poll(pendingPayload) | begin returns qrCode. Poll returns qrPending, qrExpired or success. |

`pending` is not a general multi-step form mechanism. A form is submitted once;
include all fields required to complete that submission. Expected login errors
use `flare.error`; do not return an invented `error` transition.

All successful methods return:

```js
// profile and credential come from a verified remote login.
const success = {
  type: "success",
  value: {
    accountId: profile.key.id,
    origin: context.origin,
    credential,
    profile,
    capabilities: { "flare.datasource.timeline/v1": ["page"] },
  },
};
```

`accountId` equals `profile.key.id`; the profile host equals the selected origin's
host, including a non-default port. The Host checks origin, account ownership,
and relogin identity. At least one displayable, declared capability must be
negotiated with its required operation set. Login can return `composeConfig`
using the [documented merge rules](manifest.md#compose-defaults). Preserve only credentials needed for later requests;
avoid retaining a password if the server issues a reusable token instead.

## Credential import

Use `interaction: "CredentialImport"` and a `Secret` field. The template has the
full manifest, `/api/me` verification, typed errors, and successful identity.
Adapt its token acquisition instructions and payload to the actual website.
Login contexts have no credential store access: return the verified payload;
Flare persists it. Do not call `flare.credential.replace` during login.

## Password

Manifest method fragment:

```json
{
  "id": "password",
  "interaction": "Password",
  "title": "Sign in",
  "fields": [
    { "id": "username", "type": "Username", "label": "Username" },
    { "id": "password", "type": "Password", "label": "Password" }
  ]
}
```

Service sketch (the three helpers are website-specific implementations):

```js
const passwordService = {
  async begin(request, context) {
    const token = await exchangePassword(context.origin, request.values.username, request.values.password);
    const profile = await verifiedProfile(context.origin, token);
    return loginSuccess(context, profile, { accessToken: token });
  },
};
```

`exchangePassword` sends structured form/JSON/basic authentication as specified
by the website and checks the HTTP response. `verifiedProfile` maps the actual
authenticated identity. `loginSuccess` constructs the success shape above.
Register this as `login.password`. Report credential rejection with a typed
AuthenticationRequired or Validation error appropriate to that API.

## Form

Use a form for a different fixed collection of fields, such as username + OTP:

```json
{
  "id": "otp",
  "interaction": "Form",
  "title": "Sign in with a code",
  "fields": [
    { "id": "username", "type": "Username", "label": "Username" },
    { "id": "code", "type": "Otp", "label": "One-time code" }
  ]
}
```

```js
const otpService = {
  async begin(request, context) {
    const credential = await verifyOneTimeCode(context.origin, request.values);
    const profile = await verifiedProfile(context.origin, credential.accessToken);
    return loginSuccess(context, profile, credential);
  },
};
```

Implement those website-specific helpers and register as `login.otp`. If the
website requires a preceding “send code” step, explain how the user obtains the
code outside this form or choose a supported browser flow. Do not invent dynamic
field updates, an extra resume call, or a pending-form transition.

## OAuth

Declare `{ "id": "oauth", "interaction": "OAuth", "title": "Authorize" }`
without fields or Cookie config. Implement `login.oauth.begin` and `.resume`.
Add exact `authOrigins` for a separate authorization/token origin when needed;
this does not expand later account HTTP access. Use `httpOrigins` for additional
HTTP destinations during login or later calls. An authorization page or Cookie
probe still needs `authOrigins` when outside the selected origin; an HTTP-only
grant cannot open that origin as an authorization page or read its browser Cookies.

1. `begin` receives Host-owned `flowId`, `state`, `redirectUri`, and selected
   origin. Use the supplied state and redirect URI. Register a client only if
   the provider supports it, otherwise use the provider-approved configuration.
2. Return an approved HTTPS browser URL and JSON `pendingPayload` containing
   only the bounded data needed to resume (for example client ID and PKCE
   verifier). The begin runtime closes; globals do not carry the flow forward.
3. `resume` receives Host-validated callback parameters and persisted pending
   payload. Handle the provider's denial/error response. Exchange the code,
   verify the resulting account, and return success.

```js
// begin result fragment; build authorizationUrl from the provider's contract.
const browserTransition = {
  type: "externalBrowser",
  url: authorizationUrl,
  pendingPayload: { clientId, verifier },
};
```

The Host owns callback validation, expiry, persistence, replay protection, and
relogin identity. It rejects a flow if the running package changed. The plugin
still implements the provider's token exchange and PKCE requirements; SHA-256
returns hex, so see [crypto encoding](runtime-http.md#credentials-crypto-and-locale).
Never hardcode a callback URI from a sample. See Pixelfed for dynamic client
registration, state propagation, code exchange, and profile verification.

## WebCookie

Manifest method fragment:

```json
{
  "id": "browser",
  "interaction": "WebCookie",
  "title": "Sign in in the browser",
  "cookie": {
    "startUrl": "$accountOrigin/login",
    "probes": [{
      "url": "$accountOrigin/api/me",
      "cookies": [{ "name": "session", "required": true }]
    }]
  }
}
```

Service sketch; `fetchVerifiedCookieProfile`, `mapProfile`, and `loginSuccess`
must be implemented for the selected site's profile API and result shape:

```js
const browserService = {
  async begin(request, context) {
    return { type: "webCookie", startUrl: context.origin + "/login" };
  },
  async check(request, context) {
    const probeUrl = context.origin + "/api/me";
    const cookies = request.cookies.cookies
      .filter(cookie => cookie.sourceUrl === probeUrl)
      .map(({ name, value }) => ({ name, value }));
    const profile = await fetchVerifiedCookieProfile(probeUrl, cookies);
    if (!profile) return { type: "pending" };
    return loginSuccess(context, mapProfile(profile, context), { cookies });
  },
};
```

Register as `login.browser`. The returned start URL must exactly match the
resolved manifest start URL. The Host collects Cookies only for declared probes
and filters domain/path/HTTPS scope. `sourceUrl` identifies the probe: do not
combine Cookies from distinct origins merely because their names match. Use
structured `cookies` in the verification request, and retain only the values
appropriate for later account-origin requests. Required missing Cookies delay
checking; a present Cookie still needs server-side identity verification.

Return pending only for a verified “not authenticated yet” state. Map rate limits,
network failures, and malformed responses to their actual typed errors instead
of silently polling forever. The Host prevents overlapping checks and owns the
session's disposal; the plugin supplies no lifecycle hook.

### API v2 Cookie state

Declare `apiVersion: 2`. Each Cookie entry may include `attributes` with domain,
hostOnly, path, expiresAt (epoch milliseconds), session, secure, httpOnly, and
sameSite (`strict`/`lax`/`none`). Only reliable native fields are supplied. Android
CookieManager exposes values without these attributes; Apple/Desktop expose
some metadata but do not reliably know hostOnly. Missing expiry does not imply
a session Cookie. Same-name values remain separate and more specific native
Apple values are listed first; do not reduce them to a name-keyed map. API v1
keeps its original first-value selection.

`check` can return `{type:"pending", pendingPayload: state}` with up to 64 KiB
opaque JSON. The next check receives `request.pendingPayload`, surviving JS
runtime eviction. Return the full payload every time it must survive: omission
or null clears it. Prefer the plugin's rotated state over a repeated stale
browser snapshot, and explicitly merge genuinely new login candidates.

The flow has a fixed 15-minute lifetime from begin. Pending state is memory-only;
close, expiry, or package retirement clears it. A verified success remains
available for retrying a failed local account save within this lifetime. The
Host does not repeat the remote exchange for that retry. It clears the result
once the account is saved. Return final state explicitly in `success.credential`;
the Host never merges pending state into the account for you.

## QR login (API v2)

Install QR login plugins on a Flare build that supports the `QrCode` interaction.
Earlier API v2 builds reject this interaction during installation.

Declare `{ "id": "scan", "interaction": "QrCode", "title": "Scan to sign in" }`.
The optional `description` appears next to the code and can name the app the user
should scan with. Implement `login.scan.begin` and `login.scan.poll`.

- `begin` requests a challenge from the service and returns `type: "qrCode"`,
  `content`, `expiresInMillis` and `pendingPayload`. Pass the service's QR text
  (up to 1,024 UTF-8 bytes) and remaining lifetime (up to 15 minutes). The Host
  renders the text as a QR code. Keep the challenge identifier and other data
  needed by `poll` in `pendingPayload` (up to 64 KiB of JSON).
- `poll` receives `methodId`, `origin`, `flowId` and the latest `pendingPayload`.
  Return `{ type: "qrPending", scanned: false }` while waiting for a scan and
  `scanned: true` while waiting for confirmation. Each response reflects the
  current status, including when the service returns to waiting for a scan.
  A non-null `pendingPayload` replaces the full payload for the next poll.
- Return `{ type: "qrExpired" }` when the service expires the challenge. On
  approval, verify the account against the service and return the usual
  `{ type: "success", value: ... }` with the credentials the Host should save.

Flare waits two seconds before each poll by default. A challenge can specify
`pollIntervalMillis` from 1,000 to 30,000. The Host runs one poll at a time and
ends the flow on cancellation, dismissal or expiry. Generating another code
calls `begin` with a fresh `flowId`. A verified result can be saved again if a
local save fails during the same login panel session. Keep polling state in
`pendingPayload`, which is scoped to this attempt and held in memory.

Use the login HTTP permissions for challenge and verification requests. API v2
exposes `Set-Cookie` and JSON response bodies so the plugin can save both a
session cookie and a refresh token when the service returns them.

## Account capability renegotiation

Declare this optional operation to enable newly supported features for existing
accounts without opening a login flow (runtime APIs 1 and 2):

```json
"flare.datasource.account-capabilities/v1": { "operations": { "negotiate": {} } }
```

Implement `capabilities.accountCapabilities.negotiate(request, context)` in the
social registration. `request.previous` contains the saved `capabilities`,
`composeConfig` and `composeConfigV3`. Read the existing credential with
`flare.credential.read()`, query the provider as needed, and return a complete
`AccountCapabilitiesV1` result with the same three fields. The manifest remains
the upper bound; include complete operation sets and a displayable capability.
Omitted capabilities are disabled and omitted configs fall back to manifest
defaults. Return current account/instance restrictions, not a delta.

The Host runs this callback in the background when an account is opened with a
new package hash, including accounts saved before this hook existed. Successful
logins already record that hash and need no extra negotiation. It validates and
atomically replaces only the capability snapshot, preserves credentials and
session metadata, then recreates the datasource so the UI sees new features.
Calls use an isolated temporary runtime and are serialized for the same account. Concurrent credential changes discard
stale results; logout, account replacement and plugin retirement reject late saves.

The callback is read-only: do not perform writes or call credential replacement.
It may be retried, starting after 5 seconds and backing off to 5 minutes; provider retry-after hints can delay it further.
Network/provider failures retain the previous capabilities; even an
`AuthenticationRequired` result here does not launch login. Invalid capability
results are rejected. A later app launch or package update can retry them.
Normal account methods remain responsible for refreshing tokens.

For example, returning newly supported compose/v2 Quote, compose/v3 or
account-http enables them without login. Cross-plugin account HTTP still needs
the extension's separate user grant. Plugins without this declaration continue
using their saved negotiation and existing additive upgrade rules. Changing
`credentialSchemaVersion` still requires relogin; this callback does not reinterpret opaque credentials.

## Refresh and relogin

Refresh access tokens within an account method using the website's documented
flow and the account's allowed origin. After successful verification, atomically
replace the full credential payload, retaining required fields. Retry the
original operation only when safe (especially for writes); avoid unbounded loops.
An invalid/expired credential that cannot be refreshed should raise
AuthenticationRequired, which the account adapter routes to relogin. The Host
requires the same account identity on relogin; schema changes are described in
[publishing](publishing.md#updates-and-compatibility).

API v2 supports [revision-checked credential replacement](runtime-http.md#credentials-crypto-and-locale).
It protects local state, not remote single-flight or request idempotency. See
[native retry limits](runtime-http.md#http-request-and-response) before designing
one-time login exchanges or writes.
