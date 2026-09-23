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
The client sends `POST /lmp/externalLicenseActivation` with `Content-Type: application/json`.
The JSON body wraps the `DDExternalLicenseActivation` object in an `activation`
field, as declared by `@RequestParameter("activation")` in the service interface.

```java
import com.dbeaver.datadam.lm.api.model.DDExternalLicenseActivation;
import com.dbeaver.datadam.lm.api.model.DDExternalLicenseActivationResponse;
import com.dbeaver.datadam.lm.api.service.DDExternalLicenseService;
import org.jkiss.utils.rest.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

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
