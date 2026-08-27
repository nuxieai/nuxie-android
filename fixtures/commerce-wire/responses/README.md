# Worker response handshake

The parent `nuxie-dev` conformance suite (`worker-tests`) replays every request
in `../requests/` against the real TypeScript and Rust ingest workers. It owns
and commits the resulting response fixtures in this directory; the Android
fixture generator does not create or delete them.

While [`PENDING`](PENDING) exists, the Android response conformance test skips
with the named assumption that parent-worker responses are pending. The parent
repository's response-landing commit deletes `PENDING`. From that point on,
the test requires both `<case>.ts.json` and `<case>.rs.json` for every request
case and fails if any lane response is missing.

Every response fixture has this shape:

```json
{
  "name": "purchase-one-time.ts",
  "request": "purchase-one-time",
  "endpoint": "/purchase",
  "statusCode": 200,
  "body": { "success": true },
  "bodyText": "{\"success\":true}"
}
```

- `request` names one committed request fixture and `endpoint` must match it.
- `bodyText` is the worker's exact UTF-8 response text. For JSON responses,
  `body` is that text parsed as JSON. For an empty or non-JSON non-2xx
  response, omit `body` or set it to `null`; status-based error mapping does
  not parse the real response body either.
- Successful `/purchase` fixtures are parsed through `PurchaseResponse`.
- Successful `/entitled` fixtures are parsed through `FeatureCheckResult`.
- Non-2xx fixtures are checked through the endpoint's real rejection mapping,
  including permanent-versus-retryable `/purchase` errors.

`CommerceWireFixtureTest.everyCommittedWorkerResponseParsesThroughTheSdkResponsePath`
checks the complete request × lane matrix before parsing every response. A
separate in-memory test keeps the success and error scaffolding executable
while `PENDING` exists.
