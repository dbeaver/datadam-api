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

## AI function API

`apis/com.dbeaver.datadam.ai.api` is the shared contract for remotely available desktop AI functions.
It is an OSGi bundle usable from DBeaver and a Maven dependency usable from DataDam Account.
It depends on shared annotations and Gson, not on Spring, desktop UI or the DBeaver AI registry.

The desktop implements `com.dbeaver.datadam.ai.api.service.DDAiFunctionService`:

```java
List<DDAiFunctionDescriptor> getFunctions() throws DDAiFunctionException;
DDAiFunctionResult callFunction(DDAiFunctionCall call) throws DDAiFunctionException;
```

Both methods are synchronous. The transport supplies a proxy bound to an authorized desktop,
dispatches the call and correlates its response. User/account identity, desktop/connection IDs,
request IDs, topics, deadlines and WebSocket envelopes are not service arguments. Implementations
must not construct JSON-RPC replies or manage transport listeners.

### DTOs

| DTO | Fields | Meaning |
| --- | --- | --- |
| `DDAiFunctionDescriptor` | `id`, `name`, nullable `description`, `inputSchema` | Stable callable ID, display name, AI description and full JSON Schema object for arguments. |
| `DDAiFunctionCall` | `functionId`, `arguments` | The exact descriptor ID and an object containing named arguments. Use an empty object when there are none. |
| `DDAiFunctionResult` | `value`, `error` | JSON result value and an execution-failure flag. |
| `DDAiFunctionErrorCode` | `FUNCTION_NOT_FOUND`, `INVALID_ARGUMENTS`, `ACCESS_DENIED`, `INTERNAL_ERROR` | Service-level errors represented by `DDAiFunctionException`. |

Function IDs must be unique within a desktop and use 1–128 ASCII letters, digits, underscores,
dots or hyphens. Use the registry's full function ID (including its toolbox prefix) where applicable;
do not use the human-readable name or legacy ID as the callable identifier. Names must be nonblank.
`inputSchema` must declare `"type":"object"` and can contain nested schemas, required properties,
enums and defaults. There is no separate parameter-description DTO: the JSON Schema preserves
these definitions without duplicating the schema language. `getFunctions()` returns the complete
list; this version does not paginate.

Example descriptor and invocation payloads (not transport envelopes):

```json
{
  "id": "database_listTableNames",
  "name": "List tables",
  "description": "List tables in a selected schema",
  "inputSchema": {
    "type": "object",
    "properties": {"schema": {"type": "string"}},
    "required": ["schema"]
  }
}
```

```json
{"functionId":"database_listTableNames","arguments":{"schema":"public"}}
```

`value` supports strings, objects, arrays, numbers, booleans and explicit JSON null (`JsonNull.INSTANCE`);
it must not be a Java null. For example, `{"value":{"tables":["orders"]},"error":false}` is a structured
success, and `{"value":"SQL execution failed","error":true}` is a tool execution failure.
Convert desktop-specific objects to JSON values; never transfer callbacks, UI objects or Java throwables.

`DDAiFunctionException(code, message, data)` represents rejection of the service call, such as an unknown
function or invalid arguments. `data` is an optional JSON object. The transport must preserve these
explicit error fields rather than serializing Java exception internals. Messages and data are exposed
to the AI caller. Database execution failures are returned as `DDAiFunctionResult` with `error=true`.

JSON serialization must preserve explicit nulls and numeric precision. When using Gson, use
`new GsonBuilder().serializeNulls().create()` and retain `JsonObject`/`JsonElement` values instead of
round-tripping through `Map<String, Object>` with double-valued numbers. Omit unknown optional fields
or ignore them when reading; never silently drop argument fields whose value is JSON null.

### Desktop responsibilities

Return only functions with an allowed remote purpose (`MCP` or `ALL` in the current DBeaver registry),
subject to local permissions and context. Recheck eligibility and arguments in `callFunction()`;
a caller can request an ID without first fetching the list. Do not use a denylist of UI functions.
Only explicitly enabled database connections may be accessed. Keep existing AI restrictions and
result limits. The API carries neither database credentials nor connection configurations.

### Account integration and outstanding transport work

Account maps descriptor `id` to MCP tool `name`, display `name` to `title`, and forwards the schema.
String results become text directly; other JSON values become JSON text. Object values are also
returned as MCP `structuredContent`; `error` maps to `isError`. Service exceptions map to JSON-RPC
errors: unknown function/invalid arguments → `-32602`, access denied → `-32001`, internal error → `-32603`.

Account's `DDMcpClientProvider` is the integration seam for the future External DAM service proxy.
The desktop team can implement this shared interface and test against stubs before that proxy is ready.
There is no production External DAM adapter yet. The transport wire format, routing identity and
remote cancellation API still need agreement. A thread interruption or an MCP timeout does not by
itself guarantee cancellation or rollback of a remote SQL operation. Never automatically replay SQL
when delivery or execution outcome is unknown.
