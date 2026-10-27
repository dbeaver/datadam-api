# DataDam API

This repository contains API for global DBeaver ecosystem.

## What is DataDam

- Team work with databases
- Team work with AI services
- Data sources connectivity over private networks

## Repository purpose
OSGI APIs and Service for DataDam integration
Used by DBeaver, dbvr, CloudBeaver and by DataDam itself

## Remote DAM API

[`apis/com.dbeaver.datadam.dam.remote.api`](apis/com.dbeaver.datadam.dam.remote.api/README.md) contains the Remote DAM
contracts, clients and transport adapters, together with the messaging implementation. Java packages are
`com.dbeaver.datadam.dam.remote.api.*` and `com.dbeaver.messaging.*`; messaging is not a separate Maven or OSGi module.

## SSO API

`apis/com.dbeaver.datadam.sso.api` exports the shared SSO contracts in
`com.dbeaver.datadam.sso.api.model`:

* `DDSsoAuthorizeRequest`: client, redirect URI, state, PKCE challenge, optional target resource URI and requested scopes.
* `DDSsoIdentity`: source-scoped subject, email, identity source and original authentication time.
* `DDSsoTokenRequest`: authorization code, PKCE verifier, client, redirect URI and optional resource URI.
* `DDSsoTokenResponse`: `access_token`, `token_type`, access lifetime (`expires_in`), granted `scope`,
  and an optional internal `id_token` for clients configured to receive identity assertions.
* `DDSsoAccessIdentity`: verified subject, source, client ID, audience and granted scopes.
* `DDSsoConfigResponse`: SSO issuer, obtained from the application's `/sso/config`.
* `DDSsoHandoffRequest`: confirmed identity, original authorization request and browser-binding `flow_id`.
* `DDSsoHandoffResponse`: opaque one-time `handoff` and lifetime in seconds (`expires_in`).

The module is an OSGi bundle and is also consumed as a Maven dependency by the standalone SSO server.
Token response JSON field names are declared with Gson `@SerializedName` annotations.
Handoff creation uses `POST /handoff` with HTTP Basic backend authentication and a JSON body. The nested
authorization fields use their Java camelCase names; `flow_id` and `expires_in` use explicit Gson mappings.
The identity's `authTime` uses ISO-8601 (`GsonUtils.InstantIsoAdapter`). Browser completion uses
`GET /handoff?handoff=...` and requires the proof cookie set by `/handoff/start`.

Token exchange uses `POST /token` with `application/x-www-form-urlencoded` fields `code`, `code_verifier`,
`client_id`, optional `redirect_uri` and `resource`, and mandatory `grant_type=authorization_code`.
If supplied, `redirect_uri` must exactly match the original callback. External MCP grants require `resource`.
Clients with a configured secret authenticate through HTTP Basic and may omit the body `client_id`;
form-encode each credential before joining them with `:` and Base64-encoding the result (RFC 6749 section 2.3.1).
Public clients supply `client_id` and use code and PKCE without credentials.
Every successful exchange returns an access JWT. Ordinary DataDam APIs use the fixed `DDSsoConstants.API_AUDIENCE`;
External MCP access uses its authorized resource URI as `aud`.
Account/Admin additionally use their
internal identity JWT to establish local browser sessions; this is not an OpenID Connect contract.
`DDSsoClient.exchangeCodeForTokens` returns the complete token response with an optional `id_token`, without JWT verification.
`DDSsoService.exchangeCodeForIdentity` requires an internal `id_token`, verifies its signature and identity claims,
and returns `DDSsoIdentity` for browser login.
`DDSsoTokenVerifier.verifyAccessToken` requires the expected audience explicitly. API authentication intersects
current database permissions with the permissions mapped from the token's individual or full-access scopes.
Controllers use ordinary permission checks. Identity tokens are never API credentials.
First-party clients request `account:full_access`, `dbeaver-storage:full_access` or `admin:full_access` as appropriate;
these scopes limit delegated access and never grant rights missing from the user's current database roles.
Failed exchanges return a JSON `error` (`invalid_request`, `unsupported_grant_type`, `invalid_client`,
`invalid_grant`, `invalid_target` or `server_error`).

Authorization uses `response_type=code`, space-separated `scope`, and S256 PKCE.
Ordinary API access uses the client's top-level `scopes` without any resource configuration or request parameter.
Desktop requests `mcp:connect` alongside `account:full_access` and `dbeaver-storage:full_access` in one authorization,
without `resource`. External MCP clients use `allowed-resources` with `mcp:invoke` and `require-resource: true`;
their requests require `resource` at both endpoints. The requirement is registration-specific, not inferred from a scope name.
During code exchange a supplied resource must match the saved grant.
SSO publishes RFC 8414 metadata at `/.well-known/oauth-authorization-server` (with the issuer path appended
to that suffix when applicable). Browser callbacks contain `iss` per RFC 9207: clients compare it exactly
to the issuer selected before authorization. Scopes follow RFC 6749 syntax, parsed by `DDSsoScopes`.

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

## MCP function API

`apis/com.dbeaver.datadam.mcp.api` exports `DDMcpFunctionService` for desktop tool discovery and execution.
`getFunctions()` returns descriptors with `id`, display `name`, optional `description` and `List<DDMcpFunctionParameter> parameters`.
Each parameter has `name`, `type` (`STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`), optional `description`, `required`,
optional `defaultValue` and optional `validValues`. Defaults and allowed values are strings interpreted according to `type`.
The MCP server builds each tool's JSON Schema `inputSchema` from these parameters.
`callFunction(DDMcpFunctionCall)` accepts `functionId` and `Map<String, Object> arguments`, and returns a JSON value
and an `error` flag; execution errors use `error=true`.
The MCP server maps object results to `structuredContent` and the flag to `isError`.
Function IDs are unique and match `[a-zA-Z0-9_.-]{1,128}`; display names are nonblank and parameter names are nonblank and unique.
Results use `JsonNull.INSTANCE` for JSON null, never a Java null.

Register the desktop implementation on its ready Remote DAM RPC endpoint:

```java
var codec = new DDRemoteDamGsonRpcCodec(new GsonBuilder()
    .serializeNulls()
    .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL)
    .create());
var registration = rpc.register(DDMcpFunctionService.SERVICE_ID, DDMcpFunctionService.class, implementation, codec);
```

The shared topic is `dbeaver.mcp.functions`. Retain the registration until the implementation is unavailable,
then close it. Both peers must use the renamed MCP contract; Java argument type names are part of RPC operation IDs.
Configure `serializeNulls()` and `ToNumberPolicy.BIG_DECIMAL` on both peers to preserve explicit null entries
and numeric precision in argument maps, including nested maps and lists. Gson's default `Object` number handling uses `Double`.

The desktop exposes only functions allowed for `MCP`/`ALL`, rechecks permissions and arguments on every call,
and enforces enabled connections and result limits. Database credentials remain on the desktop.
Remote DAM transports thrown exceptions as RPC diagnostics, not as reconstructed `DDMcpFunctionException` objects.
MCP returns a generic error for remote handler failures without exposing diagnostic stack traces.
Timeout or interruption does not guarantee remote cancellation or SQL rollback; calls are never replayed automatically.
