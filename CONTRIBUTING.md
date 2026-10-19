# Contributing

## Workflow

- `main` is protected. Every change goes through a pull request; nobody pushes
  to `main` directly.
- There is no CI build: the mod compiles against the game's own jar, which is
  proprietary and must never be uploaded or committed. Run
  `syx-multiplayer/test.sh` locally before opening a PR and say so in the PR.
- **Releases are cut locally with `syx-multiplayer/release.sh`** from an
  up-to-date `main`. It bumps the patch version (latest `vX.Y.Z` tag + 1;
  `--minor`/`--major`/an explicit version for bigger bumps), runs the headless
  tests, builds with that version baked in, tags, pushes the tag and creates
  the GitHub release with `SyxMultiplayer-X.Y.Z.zip` and notes generated from
  the merged PRs. `--dry-run` builds without tagging or publishing. Nothing
  built is ever committed; `dist/` and `out/` are gitignored.
- Keep commits focused; the generated release notes are built from PR titles,
  so make titles say what changed for players ("Sync hospital fetch orders",
  not "fixes").

## Rules the code must follow

Read `CLAUDE.md` (the technical map of the game internals and the port) and
`docs/HANDOFF4.md` before touching the sync engine. The non-negotiables:

- Nothing mutates simulation state outside a sub-step or a command.
- Nothing consumes the shared `RND` outside the per-sub-step/per-command reseed.
- No wall-clock threads touch sim state.
- `CommandType`, `Packets` and the `SyxSync` registration order are wire
  protocol: append only, and bump `MpVersion.PROTOCOL` on any wire change.
- Both ends of a session must run the identical jar; the version check at join
  enforces it.

## Local setup

You need your own copy of Songs of Syx (V71). The game ships its full Java
sources in `info/SongsOfSyx-sources.jar`; unzip them to `reference/syx-sources/`
(gitignored) and read them instead of guessing.

```sh
cd syx-multiplayer
./setup-tools.sh --jdk       # downloads ASM + a Temurin 21 JDK into ../tools (omit --jdk if you have JDK 21)
cp local.env.example local.env   # set SYX_GAME_DIR (and optional install dirs)
./test.sh                    # build, headless tests, install into your mods folder
```

- `./build.sh` builds and installs; `SYX_INSTALL=0 ./build.sh` only builds.
- `./package.sh` builds the distributable zip into `dist/`.
- `e2e/run-pair.sh` launches a host and a joiner side by side on one PC (Linux,
  needs `xdotool`; the joiner uses the gitignored `.mp-test-home/` as its data
  dir). `e2e/watch.sh`, `difftrace.sh`, `diffdesync.sh`, `diffload.sh` analyse
  the logs. Useful JVM flags are listed in `CLAUDE.md` ("Testing workflow").
- Machine-specific notes that must not be committed go in `CLAUDE.local.md`
  and `syx-multiplayer/local.env` (both gitignored).
