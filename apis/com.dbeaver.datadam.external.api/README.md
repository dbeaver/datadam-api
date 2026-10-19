# External DAM API

This module contains the Desktop WebSocket contracts and a backend-only client for the External DAM internal GraphQL API.
Neither the models nor the WebSocket protocol depend on the HTTP client, Spring, or Eclipse runtime services.

## Internal GraphQL contract

The backend client posts JSON requests to `/internal/graphql` on the configured External DAM service root URL.
The service implementation must expose the following contract; adding the client alone does not deploy the endpoint.

```graphql
scalar JSON

type Query {
    externalDamConnections(accountId: ID!): [ExternalDamConnection!]!
}

type Mutation {
    sendExternalDamMessage(accountId: ID!, connectionId: ID!, message: ExternalDamMessageInput!): Boolean!
}

type ExternalDamConnection {
    deploymentId: String!
    applicationRunId: String!
    connectionId: ID!
    product: String!
    version: String!
}

input ExternalDamMessageInput {
    topic: String!
    type: String!
    payload: JSON!
}
```

- `accountId` is a UUID and is mandatory for both operations. Connection lookup and sending must use the account and connection ID together.
- Connection queries return an immutable client-side snapshot of currently ready sockets, not persisted DAM installations or history.
- Concurrent sockets can share a Deployment ID and application run ID. Consumers must not deduplicate sockets using either ID.
- Product and version are Desktop-supplied presentation metadata, not authenticated identity or authorization claims.
- Message payloads must be JSON objects. They are passed through GraphQL variables, not interpolated into query text or encoded as JSON strings.
- A send result of `true` means the server completed its WebSocket write. It does not confirm that Desktop received or processed the event.
- A result of `false` means that no ready connection exists for that account and connection ID. This also covers a connection belonging to another account.
- A connection closing after lookup, queue rejection, an invalid message, or a write failure produces a GraphQL error rather than a successful send.
- HTTP errors, GraphQL errors (including errors with partial data), and malformed responses become `DDExternalDamException`.
- The client does not retry mutations or queue messages offline. After a timeout or network failure, delivery may be unknown; callers must not blindly replay non-idempotent events.
- Connections live in one External DAM process. Until owner-aware routing exists, calls must reach the process owning the socket; a missing result from another replica does not prove global offline status.

## Authentication and configuration

`DDExternalDamClient` takes a service root URL and the External DAM service secret. It never logs in or acquires a Desktop token.
The URL must use HTTP or HTTPS, have no credentials, query, fragment, or non-root path, and use a valid port.
HTTP is supported for trusted private service networks; use HTTPS across untrusted networks.

The secret must contain 32–512 printable, non-space ASCII characters. It is sent only in the `X-DD-Service-Token` header,
never in the URL or GraphQL variables. Redirects are disabled to avoid forwarding credentials to another endpoint.
Connection establishment uses the shared REST client's 5-second default connect timeout; responses have a 10-second deadline.
HTTP error bodies, GraphQL error details, and malformed response contents are not copied into client exceptions.

The service secret authenticates a trusted backend, not an end user. A backend exposing these operations to users must
derive `accountId` from its authorized account context rather than trusting an arbitrary account ID supplied by the caller.
The server must validate the service secret before executing either GraphQL operation and fail closed when its secret
is missing or invalid. A browser session or Desktop JWT must not grant access to this endpoint.
The secret belongs to backend runtime configuration only and must never be distributed to Desktop or browser clients.
It is separate from the Account backchannel secret used to verify Desktop JWTs.

```java
var client = new DDExternalDamClient(serviceUrl, serviceSecret);
var connections = client.getConnections(accountId);
boolean written = client.sendMessage(accountId, connectionId, message);
```

Use one configured client per service endpoint instead of constructing one per request. No deployment-specific URL or secret is packaged here.
