# Font converter host-smoke fixture

These Base64 resources are byte-for-byte copies of the production-generated
`font-converter` fixture in `nuxieai/nuxie-dev` at commit
`4d14dac1223670c1fe5fa6226c1b4452ea5678bb`, staged under
`tests/e2e/ios/GeneratedEditorFixtures/font-converter`.

Reproduce the source corpus from that revision at the parent repository root:

```sh
pnpm run editor:ios-artifact:prepare
pnpm run editor:ios-artifact:test:producer
node tests/e2e/ios/scripts/stage-editor-native-ui-fixtures.mjs \
  output/editor-ios-production-artifact \
  tests/e2e/ios/GeneratedEditorFixtures
```

The pinned corpus entry has source head sequence `1804`, snapshot key
`publish-snapshots/editor-ios-production-font-converter/snapshot-1804.json`,
Experience version `editor-ios-production-font-converter`, and build
`editor-ios-production-font-converter-build`.

| Resource | Decoded SHA-256 |
| --- | --- |
| `font-converter.release-entry.json.base64` | `505dd2baa6cd34f6cff2cb216f7b1454f6ef9bb48bc575fe61b194443862db6a` |
| `font-converter.riv.base64` | `e36fe9c2a6107c1eba10d216d1e17143af959d560bcf7c2566b4e894b1544d40` |
| `font-converter.ttf.base64` | `b481b059ee94961c7b18585a596935aaa7cc44b68879c096d2cd06922e0431b1` |

The release entry supplies `Copy` / `vmi.copy.default` / `label = "a"`. The
live smoke renders it with and without the entry so skipping presentation data
application changes the frame.
