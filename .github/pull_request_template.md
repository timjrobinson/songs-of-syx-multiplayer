## What

<!-- One or two sentences: what changes for players or developers. -->

## Why

<!-- The problem or the gap this closes. Link the issue if there is one. -->

## Checklist

- [ ] `syx-multiplayer/test.sh` passes locally (headless suite)
- [ ] Wire protocol untouched — or `MpVersion.PROTOCOL` bumped and `CommandType` / `Packets` / `SyxSync` only appended to
- [ ] Determinism rules in `CLAUDE.md` respected (no RND outside the sub-step/command reseed, no wall-clock threads touching sim state, `Multiplayer.ticking` around sim execution)
- [ ] Docs updated if behaviour changed (`README.md`, `docs/sync-coverage.md`, `syx-multiplayer/mod/README.txt`)
- [ ] Play-tested with two instances (`e2e/run-pair.sh`) if the change touches sync or the tick loop

Merging this PR into `main` publishes a release with the patch version bumped automatically. Add `[skip release]` to the merge commit message if this should not ship on its own.
