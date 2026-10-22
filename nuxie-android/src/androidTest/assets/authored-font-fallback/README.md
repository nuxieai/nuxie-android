# Ordinary authored text baseline

P038 / [UNIV-3375](https://universe.basis.dev/issue/UNIV-3375).

`ordinary-roboto.nux` was emitted by the publisher example at `be00893bb0`.
`ordinary-provenance.json` records its source and output hashes, font
declaration, strings and row positions. The scene contains ordinary Text
nodes, with no TextInput or platform editing overlay. Its primary font is the
Latin-only `ordinary-latin.ttf`, so every non-Latin row needs fallback.

The System font stays external. Its private source bytes are not present here.
`ordinary-latin.ttf` is the same Apache-licensed Roboto fixture as
`Tests/NuxieUnitTests/Fixtures/ordinary_font_fallback_latin.ttf`; its license is
in `Tests/NuxieUnitTests/Fixtures/LICENSE-ordinary-font-fallback.txt`.

The published-text regression observes the actual C fallback hook and compares
its selected PostScript names with CoreText. It also checks visible native ink.
The typed two-field form qualifies Japanese, Arabic, Hebrew, Hindi, Latin,
emoji and symbols after focus moves. Only iOS 27.0 currently checks the known
blank Apple emjc emoji row; other OS versions do not inherit that expectation.

`ordinary-roboto.nux`, `ordinary-provenance.json` and `ordinary-latin.ttf`
now sync from nuxie-ios into `fixtures/runtime/authored-font-fallback/`
(UNIV-5273). The published-text device test and the example's release
qualification read them there. This directory keeps `ordinary-latin.ttf` and
`inline_text_data_binding.riv` for the bound-text test, copied unchanged from
nuxie-ios origin/main `62f7e4717885fdcf708097064f230224092db2e2`
(16 October 2026). `inline_text_data_binding.riv` is copied from that revision's
`Tests/NuxieUnitTests/Fixtures/` and exercises the same bound ordinary symbols
as `NuxieNativeRuntimeTests.testSymbolsUseCoreTextFallbackAndDrawFromALatinPrimary`.
This Android-only test asset directory does not change the synced fixture contract.
