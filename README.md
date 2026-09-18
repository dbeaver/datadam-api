# DataDam API

This repository contains API for global DBeaver ecosystem.

## What is DataDam

- Team work with databases
- Team work with AI services
- Data sources connectivity over private networks

## Repository purpose
OSGI APIs and Service for DataDam integration
Used by DBeaver, dbvr, CloudBeaver and by DataDam itself

## License Manager API

`apis/com.dbeaver.datadam.lm.api` contains the external-license activation request/response DTOs and
the `DDExternalLicenseService` interface backed by `org.jkiss.utils.rest.RestClient`.
The client sends the DTO directly as JSON to `POST /lmp/externalLicenseActivation`.

```java
var client = DDExternalLicenseService.create(URI.create("https://dbeaver.com/lmp"));
try {
    DDExternalLicenseActivationResponse response = client.recordActivation(new DDExternalLicenseActivation(
        eventId, "cdata", email, null, driverVersion, "trial", product, activatedAt
    ));
} finally {
    RestClient.close(client);
}
```

Generate `eventId` once per activation and reuse it on retries. `externalProductId`
may be `null` for `trial`; it is required and nonblank for `purchased`.
All other fields are required. `activatedAt` is an ISO-8601 timestamp with an
explicit offset (for example, `2026-09-16T12:30:00Z`). The client throws
`RpcException` on transport errors or rejected requests.

Successful requests return `DDExternalLicenseActivationResponse` with `success = true`
and `error = null`, including duplicate activations. HTTP `400`/`500` responses use
the same JSON model with `success = false` and `error` containing a stable `code`
(`INVALID_REQUEST` / `INTERNAL_ERROR`) and a display-only `message`. `RestClient`
throws `RpcException` for these HTTP errors; its message contains the server's JSON
body, which can be deserialized into `DDExternalLicenseActivationResponse`.
Clients must ignore unknown JSON fields and handle unknown error codes as failures.

`DDExternalLicenseService` is also implemented by the LM controller. Transport
resources belong to the client proxy and are released through `RestClient.close(client)`;
the shared service interface has no lifecycle methods.
