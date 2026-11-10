# CI and quality gates

`.github/workflows/ci.yml` runs on every pull request and every push to `main`. The `main` ruleset
requires its three jobs (and `Dependency review`, see [Dependencies](#dependencies)), so a merge is
blocked on any of:

| Job | Gate | Gradle task | Fails when |
|---|---|---|---|
| Style, static analysis, API surface | style | `ktlintCheck` | a `.kt`/`.kts` file breaks the [`.editorconfig`](../../.editorconfig) rules (Android Studio style, 140 columns) |
| | static analysis | `detekt` | a finding not in the module's `detekt-baseline.xml` (rules in [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml)) |
| | API break | `apiCheck` | a published module's public binary API differs from its committed `<module>/api/<module>.api` |
| Build, unit tests, lint | build | `assembleDebug` | the SDK or the demo fails to compile |
| | tests | `testDebugUnitTest` | a JVM unit test fails |
| | lint | `lint` | Android lint reports an error |
| Minified demo (R8) + 16 KB alignment | consumer path | `:app:assembleRelease` | R8 fails on the demo with the SDK's consumer rules (missing class, bad keep rule) |
| | native libs | `verify16kbAlignment` | a `.so` on the demo's release runtime classpath is not 16 KB page aligned |
| | publishable | `publishToMavenLocal` | a POM, sources jar or Dokka javadoc jar cannot be produced |

Everything runs the same way locally — no task depends on the machine:

```bash
./gradlew ktlintCheck detekt apiCheck assembleDebug testDebugUnitTest lint :app:assembleRelease verify16kbAlignment --continue
```

## Nothing from the developer's machine

- **JDK** — `gradle/gradle-daemon-jvm.properties` pins the daemon to Java 21 and the Foojay
  resolver downloads it; `JAVA_HOME` is irrelevant (CI sets up a JDK only so Gradle itself starts).
- **Android SDK** — `local.properties` is gitignored; CI uses the runner's `ANDROID_HOME`, and AGP
  fetches missing platform components itself.
- **Secrets** — the demo reads `ALFRED_BASE_URL` from the environment or a gitignored `.env`; the
  SDK needs none.
- **Versions** — plugins and libraries are pinned in `gradle/libs.versions.toml`, the wrapper in
  `gradle/wrapper/gradle-wrapper.properties`; Dependabot proposes updates weekly.

## When a gate fails on purpose

**API changed** (`apiCheck`). Run `./gradlew apiDump`, review the diff of `*/api/*.api`, commit it
with the change. A removal or signature change is a breaking change: the commit is `feat!:` (see
[release.md](release.md#versioning)). Kotlin `internal` declarations, `@InternalPurseApi` ones (the
seams the partner artifacts use, no compatibility promise) and Compose's generated singleton holders
are excluded from the dumps.

**Style** (`ktlintCheck`). `./gradlew ktlintFormat` fixes what can be fixed automatically. Rules
that fight the codebase's shape are disabled in `.editorconfig`, with the reason; add there rather
than suppressing inline.

**Static analysis** (`detekt`). Fix the finding. For a deliberate exception, prefer adjusting the
threshold in `config/detekt/detekt.yml` over the baseline; `./gradlew detektBaseline` regenerates
the baselines and should be rare — the committed ones capture the pre-existing findings at the
time the gate was introduced.

**R8** (`:app:assembleRelease`). A `Missing class` or a runtime crash on the minified demo means a
consumer rule is missing in the offending module's `consumer-rules.pro` — see
[r8-proguard.md](../decisions/r8-proguard.md). Never fix it in `app/proguard-rules.pro`: a
merchant's build would not get that fix.

**16 KB** (`verify16kbAlignment`). Bump the partner SDK to an aligned release; failing that, follow
[16kb-alignment.md](../decisions/16kb-alignment.md) (drop the method or document the limit).

## How the API check works

The `apiDump`/`apiCheck` tasks are declared in the root `build.gradle.kts` on top of kotlinx
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)'s
signature loader, because its Gradle plugin keys on the `kotlin-android` plugin that AGP's built-in
Kotlin does not apply. Same `.api` format, same filtering (Kotlin `internal` and `@InternalPurseApi`
excluded), per-module dumps under `<module>/api/`. The Kotlin Gradle plugin's own `abiValidation`
does not support Android libraries.

## Pull request titles

Merges into `main` are squash-only, so a pull request's **title** becomes the commit on `main`, and
release-please reads it to decide the next version and the changelog section. `PR title`
(`.github/workflows/pr-title.yml`, a required check) fails unless the title is a Conventional Commit:
`<type>(<optional scope>)<optional !>: <description>`, with type one of `feat`, `fix`, `perf`,
`revert`, `docs`, `refactor`, `test`, `ci`, `build`, `chore` or `style`. It reruns when the title is
edited. A title that would fail here — `Feat/monitoring`, a branch name — releases nothing.

## Release freeze

`Release freeze` (`.github/workflows/release-freeze.yml`) fails a pull request into `main` whose
title is not `fix:` or `revert:` while a release PR (`autorelease: pending`) is open, so only fixes
land during NRT (see [release.md](release.md#step-1--freeze-the-release-pr)). The release PR itself
passes. When the release PR opens, reopens or closes, the last run of the check on every other open PR is
re-run, so no verdict goes stale.

## Dependencies

`.github/workflows/dependencies.yml` submits the resolved Gradle dependency graph (GitHub cannot
read it from a manifest) on every push to `main` and every pull request. On a pull request,
`Dependency review` then fails when the change adds a dependency with a known **high** or
**critical** vulnerability. Dependabot alerts come from the graph submitted on `main`.

CodeQL runs through GitHub's default setup (Settings → Code security) for `java-kotlin` and
`actions`; the `main` ruleset blocks a merge on a high or critical code scanning alert.

## Workflow hygiene

- Every action is pinned by commit SHA with its version in a trailing comment; Dependabot's
  `github-actions` updates rewrite both, under a `ci:` prefix that keeps them out of the changelog.
- Every workflow declares `permissions:`, read-only unless a job needs more, and every job has a
  `timeout-minutes`.
- The Gradle wrapper jar is checked against Gradle's published checksums before a build uses it.

## Repository settings

Repository settings, not code — a repository admin applies them once. `.github/CODEOWNERS` makes
`@UpStreamPay/pci-dss` the owner of every file.

**`main: CI gates` ruleset** (Settings → Rules → Rulesets):

- no deletion, no force-push, no bypass
- pull request required, **1 approval**, **review from a code owner**, approvals dismissed on a new push
- status checks, **strict** (branch up to date with `main`): `Style, static analysis, API surface`,
  `Build, unit tests, lint`, `Minified demo (R8) + 16 KB alignment`, `Dependency review`,
  `Release freeze`
- code scanning: CodeQL, blocking on high or critical security alerts and on errors

**`release tags` ruleset** on `refs/tags/v*`: creation, update and deletion restricted, repository
admins bypass. release-please's token belongs to an admin, so it can create the tag; nobody else
can create, move or delete one.

**`release` environment**: required reviewers `@UpStreamPay/pci-dss`, self-review prevented,
deployments only from `v*` tags and `main`. It must exist **before** a workflow naming it runs,
otherwise GitHub creates it with no protection.

**Actions** (Settings → Actions → General): the default `GITHUB_TOKEN` is read-only and workflows
cannot approve pull requests.

## What is not automated

Two acceptance criteria of SDK-12219 need a device and sandbox credentials and are rehearsed by
hand at each release candidate:

- **A sandbox payment on the minified demo** — install `app-release.apk` (the artifact of the
  *Minified demo* job, signed with the debug key) and pay end to end against a sandbox session for
  a card, Google Pay, PayPal, Klarna and one redirection method. This is the only proof that the
  consumer rules are complete at runtime.
- **An external app integrates from the published documentation alone** — a fresh Android project
  outside this repository adds the Maven coordinates and reaches a sandbox payment following
  [Getting started](../integration/getting-started.md) only. Anything that had to be asked is a
  documentation bug.
