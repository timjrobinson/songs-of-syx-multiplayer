# Contributing

## Workflow

- `main` is protected. Every change goes through a pull request; nobody pushes
  to `main` directly.
- CI (`.github/workflows/ci.yml`) builds the mod against the game jar and runs
  the headless test suite on every PR. Merge only when it is green.
- **Merging to `main` publishes a release**: `.github/workflows/release.yml`
  runs the tests again, bumps the patch version (latest `vX.Y.Z` tag + 1),
  tags the merge commit, and attaches `SyxMultiplayer-X.Y.Z.zip` with
  auto-generated notes. Put `[skip release]` in the merge commit message to
  merge without shipping. To bump minor/major, tag the merge commit yourself
  (`git tag v0.4.0 && git push --tags`) and the next merge continues from there.
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

## CI secrets (repository admins)

The game is proprietary, so CI downloads `SongsOfSyx.jar` at build time from a
URL you provide. In the repository settings under *Secrets and variables >
Actions* add:

- `SYX_GAME_JAR_URL` (required): a direct download URL for the **V71.44**
  `SongsOfSyx.jar` (~36 MB). Any private location works: a release asset in a
  private GitHub repo, an object-storage pre-signed URL, a self-hosted file.
- `SYX_GAME_JAR_TOKEN` (optional): sent as `Authorization: Bearer <token>`
  (with `Accept: application/octet-stream`), e.g. a fine-grained GitHub token
  that can read the private repo holding the jar. The asset URL for a private
  GitHub release is `https://api.github.com/repos/<owner>/<repo>/releases/assets/<asset id>`.

Without the secret, pull requests from forks skip the build with a warning
(forks never receive secrets) and the push to `main` fails before releasing.
Never commit the game jar or anything derived from it.
