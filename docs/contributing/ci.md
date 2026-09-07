# CI and quality gates

`.github/workflows/ci.yml` runs on every pull request and every push to `main`. Branch protection
on `main` requires its three jobs, so a merge is blocked on any of:

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

## Branch protection

`main` must require the three CI jobs as status checks (and a pull request; direct pushes off).
It is a repository setting, not code — a repository admin sets it once, under **Settings → Rules →
Rulesets** (or **Branches → Branch protection rules**):

- Require a pull request before merging
- Require status checks to pass: `Style, static analysis, API surface`, `Build, unit tests, lint`,
  `Minified demo (R8) + 16 KB alignment`
- Require branches to be up to date before merging

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
