# Worker response handshake

The parent `nuxie-dev` conformance suite (`worker-tests`) replays every request
in `../requests/` against the real TypeScript and Rust ingest workers. It owns
and commits the resulting response fixtures in this directory; the Android
fixture generator does not create or delete them.

Response fixtures may be nested by worker, but every `*.json` file must have
this shape:

```json
{
  "name": "typescript/purchase-one-time",
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
globs every response file automatically. While this directory contains no
JSON files, that test skips with the named assumption "Parent-worker commerce
response fixtures have not been committed yet." A separate in-memory test
keeps the success and error scaffolding executable until real responses land.
