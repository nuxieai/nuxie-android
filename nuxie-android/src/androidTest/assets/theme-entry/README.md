# Device appearance fixture

UNIV-5153 / UNIV-3502. These three files are copied unchanged from
`nuxie-ios` main `5d2f592bf6a3f29a7cdd406ab5c7fd77e2fe9773`,
`Tests/NuxieUnitTests/Fixtures/theme-entry/`:

- `theme.riv`
- `generate-theme.py`
- `theme-provenance.json`

The fixture and provenance were introduced by iOS commit `11bd1950`, used by
the appearance implementation in `5f22c595`, and retained through `a0ae5435`.
The generator is included for reproducibility. The provenance records the
runtime wire source and scene SHA-256. No fonts or external assets are needed.

The `theme` artboard has an `env.colorScheme` enum (`light`, `dark`) and a
global `Theme` with authored `light` and `dark` instances. The rectangle at
(100,100) binds to `Theme.surface`: light is ARGB `0xFFF2F2F7`, dark is
`0xFF1C1C1E`, and an unbound rectangle is green `0xFF00FF00`.

`ExperienceColorSchemeDeviceTest` checks the fixture hash, the first mounted
frame in both appearances, live configuration changes and retained-instance
reuse, and the runtime schema default. This Android-only test asset directory
does not change the synced `fixtures/` contract or its manifest.
