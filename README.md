# DataDam API

This repository contains API for global DBeaver ecosystem.

## What is DataDam

- Team work with databases
- Team work with AI services
- Data sources connectivity over private networks

## Repository purpose
OSGI APIs and Service for DataDam integration
Used by DBeaver, dbvr, CloudBeaver and by DataDam itself

## SSO API

`apis/com.dbeaver.datadam.sso.api` exports the shared SSO contracts in
`com.dbeaver.datadam.sso.api.model`:

* `DDSsoAuthorizeRequest`: client, redirect URI, state and PKCE challenge parameters.
* `DDSsoIdentity`: source-scoped subject, email, identity source and original authentication time.
* `DDSsoTokenRequest`: authorization code, PKCE verifier, client and redirect URI.
* `DDSsoTokenResponse`: signed identity token (`id_token`) and lifetime in seconds (`expires_in`).
* `DDSsoHandoffRequest`: confirmed identity, original authorization request and browser-binding `flow_id`.
* `DDSsoHandoffResponse`: opaque one-time `handoff` and lifetime in seconds (`expires_in`).

The module is an OSGi bundle and is also consumed as a Maven dependency by the standalone SSO server.
Token response JSON field names are declared with Gson `@SerializedName` annotations.
Handoff creation uses `POST /handoff` with HTTP Basic backend authentication and a JSON body. The nested
authorization fields use their Java camelCase names; `flow_id` and `expires_in` use explicit Gson mappings.
The identity's `authTime` uses ISO-8601 (`GsonUtils.InstantIsoAdapter`). Browser completion uses
`GET /handoff?handoff=...` and requires the proof cookie set by the initial SSO authorization request.

Token exchange uses `POST /token` with `application/x-www-form-urlencoded` fields `code`, `code_verifier`,
`client_id` and `redirect_uri`. If supplied, `grant_type` must equal `authorization_code`. Clients with a
configured secret authenticate through HTTP Basic; public clients use code and PKCE without credentials.
The response is `DDSsoTokenResponse` with an application-specific identity JWT and `expires_in` in seconds.
Failed exchanges return a JSON `error` (`invalid_request`, `unsupported_grant_type`, `invalid_client`,
`invalid_grant` or `server_error`).

Transport failures are logged with the endpoint, scheme/host/port, request phase, elapsed milliseconds,
connect/read timeouts (5/10 seconds), and a bounded exception cause chain. `write_request` and
`read_headers` can include TCP/TLS setup because `HttpURLConnection` connects lazily. Known TLS/IO
diagnostic phrases and TLS alerts are retained, but arbitrary exception messages, certificate details,
credentials, query parameters and request/response bodies are omitted. An unrecognized message is
represented by its exception type only. These diagnostics do not change the error returned to callers.

## License Manager API

`apis/com.dbeaver.datadam.lm.api` contains the external-license activation request/response DTOs and
the `DDExternalLicenseService` interface backed by `org.jkiss.utils.rest.RestClient`.
The client sends `POST /lmp/externalLicenseActivation` with `Content-Type: application/json`.
The JSON body wraps the `DDExternalLicenseActivation` object in an `activation`
field, as declared by `@RequestParameter("activation")` in the service interface.

```java
var activation = new DDExternalLicenseActivation(
    UUID.randomUUID().toString(), // eventId
    "cdata",                     // provider
    "user@example.com",          // email
    null,                        // externalLicenseId
    "25.0",                      // externalProductVersion
    "trial",                     // licenseType
    "cdata-salesforce",           // product: external product/driver
    "dbeaver-ue",                 // internalProduct
    "26.2.0",                    // internalProductVersion
    null,                        // lmLicenseId
    Instant.now().toString()      // activatedAt
);
var client = DDExternalLicenseService.create(URI.create("https://dbeaver.com/lmp"));
try {
    DDExternalLicenseActivationResponse response = client.recordActivation(activation);
} finally {
    RestClient.close(client);
}
```

Generate `eventId` and capture `activatedAt` once per successful activation; reuse
the same request on retries. A new activation of the same external license gets a
new event ID.

`DDExternalLicenseActivation` contains these fields, all of type `String`:

| Field | Meaning |
| --- | --- |
| `eventId` | Unique activation event ID |
| `provider` | External license vendor |
| `email` | Email associated with the activation |
| `externalLicenseId` | External license ID; nullable for `trial`, required for `purchased` |
| `externalProductVersion` | External product/driver version |
| `licenseType` | `trial` or `purchased` |
| `product` | External product/driver ID |
| `internalProduct` | DBeaver product used for activation, for example `dbeaver-ce` or `dbeaver-ue` |
| `internalProductVersion` | DBeaver product version |
| `lmLicenseId` | Optional LM license ID of the DBeaver product, nullable for either license type |
| `activatedAt` | ISO-8601 timestamp with an explicit offset, for example `2026-09-16T12:30:00Z` |

All fields except `externalLicenseId` and `lmLicenseId` are always required.
Supplied strings must be nonblank and have no surrounding whitespace. The client
throws `RpcException` on transport errors or rejected requests.

Successful requests return `DDExternalLicenseActivationResponse` with `success = true`
and `error = null`, including duplicate activations. HTTP `400`/`415`/`500` responses use
the same JSON model with `success = false` and `error` containing a stable `code`
(`INVALID_REQUEST` / `INTERNAL_ERROR`) and a display-only `message`. `RestClient`
throws `RpcException` for these HTTP errors; its message contains the server's JSON
body, which can be deserialized into `DDExternalLicenseActivationResponse`.
Clients must ignore unknown JSON fields and handle unknown error codes as failures.
Invalid fields, a missing `activation` object, or malformed JSON return HTTP `400`.
Missing or unsupported `Content-Type` returns HTTP `415`. Both use `INVALID_REQUEST`;
HTTP `500` uses `INTERNAL_ERROR`.

`DDExternalLicenseService` is also implemented by the LM controller. Transport
resources belong to the client proxy and are released through `RestClient.close(client)`;
the shared service interface has no lifecycle methods.
