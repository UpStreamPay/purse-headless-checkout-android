# Release

How to cut a release of the Purse Headless Checkout Android SDK. Replayable by any team member:
nothing below depends on a person's machine.

> For **SDK contributors**. Merchants integrating the published SDK start with
> [Getting started](../integration/getting-started.md).

## Overview

The release is driven from GitHub, exactly like [securefields-android](https://github.com/UpStreamPay/purse-securefields-android):

| Step | Trigger | What happens |
|---|---|---|
| 1 | Push to `main` | `release-please.yml`: conventional commits decide the next version; a **release PR** carries the version and the changelog |
| 2 | Release PR merged | release-please pushes tag `v<version>` and opens a **draft GitHub release** with the changelog |
| 3 | Tag push (`v*`), after a codeowner approves | `staging.yml`: gates run, the four artifacts are signed and uploaded to the Central Portal as a **pending deployment**, the draft release gets the Maven coordinates and the generated API reference |
| 4 | Manual workflow, after a codeowner approves | `release.yml`: re-publishes the latest tag on `main` with `automaticRelease = true`, promoting to **Maven Central**; the GitHub release is published |

Merging the release PR is all it takes to start a release. The pending deployment lets you inspect POMs
and artifacts before anything goes public. Pushes made only of non-releasable commits (`docs:`,
`chore:`, …) produce no tag and trigger nothing.

## What gets published

Four artifacts under the `eu.purse` group, all at the same version, from one Gradle invocation
(`publishAllPublicationsToMavenCentralRepository`). Each ships its AAR, a sources jar, a javadoc jar
built by Dokka from the KDoc, a POM (MIT license, developers, SCM; the partner POMs depend on the SDK
at the same version) and GPG signatures.

| Module | Artifact | Who depends on it |
|---|---|---|
| `:headless-checkout` | `eu.purse:headless-checkout-android` | every merchant |
| `:partner-googlepay` | `eu.purse:headless-checkout-googlepay` | merchants offering Google Pay |
| `:partner-klarna` | `eu.purse:headless-checkout-klarna` | merchants offering Klarna |
| `:partner-paypal` | `eu.purse:headless-checkout-paypal` | merchants offering PayPal |

`:app` (the demo) is never published. Coordinates, POM metadata and the module → artifact map live
in one place, the root `build.gradle.kts`.

## Prerequisites (one-time setup)

The Central Portal account, the `eu.purse` namespace and the organisational GPG key **already
exist**: they were created for securefields-android and are shared by every Purse Android SDK. See
its [release guide](https://github.com/UpStreamPay/purse-securefields-android/blob/main/docs/contributing/release.md)
for how they were made and the governance rules (two publishers on the namespace, key rotation).

What this repository needs is the same five GitHub secrets (**Settings → Secrets and variables →
Actions**), copied from securefields-android:

| Secret | Used by | Value |
|---|---|---|
| `TF_REPO_AUTOMATION_PAT` | release-please | Fine-grained PAT on this repository alone with **Contents**, **Pull requests** and **Issues: Read and Write**, so CI runs on the release PR and the tag triggers `staging.yml` (events raised by `GITHUB_TOKEN` start no workflow). Its account must be a repository admin: the `v*` tag ruleset lets only admins create tags. |
| `GPG_PRIVATE_KEY` | staging, release | The organisational signing key, armored (`purse-signing-key.asc`) |
| `GPG_PASSPHRASE` | staging, release | Its passphrase |
| `MAVEN_CENTRAL_USERNAME` | staging, release | Username of a Central Portal **user token** of a publisher on `eu.purse` |
| `MAVEN_CENTRAL_PASSWORD` | staging, release | Its password |

Both publish workflows check the four publishing secrets inside the gated job and **fail** when any
is missing. They used to skip with a warning, which left the run green having published nothing:
that is what happened to the first `v0.1.0` staging run.

## Release approval

`staging.yml` and `release.yml` run in the **`release` environment**. Each run waits until a member
of `@UpStreamPay/pci-dss` approves it under **Actions → the run → Review deployments**; GitHub emails
the reviewers. The person who started the run cannot approve it, and only `v*` tags and `main` may
deploy to the environment (PCI-DSS 6.5.1).

Two more guards keep unreviewed code out of a release:

- **Staging refuses a tag that is not on `main`.** Only code that went through a reviewed PR is
  signed and uploaded.
- **The manual release builds the latest tag on `main`**, whatever branch it is started from.

Around them, repository settings (applied once by an admin, see [ci.md](ci.md#repository-settings)):
the `main` ruleset (PR, one codeowner approval, stale approvals dismissed, strict checks, no bypass)
and the `v*` tag ruleset (only admins create, move or delete a release tag).

## Versioning

The version lives in the git tag. release-please also records it in `.release-please-manifest.json`
and `version.txt`, but Gradle reads only `SDK_VERSION`, set by the workflows from the tag; a local
build is `0.0.0-SNAPSHOT`.

| Commit prefix | Bump from 1.0.0 | While on 0.x |
|---|---|---|
| `feat!:` / `BREAKING CHANGE:` footer | major: `1.2.3` → `2.0.0` | minor: `0.1.0` → `0.2.0` |
| `feat:` | minor: `1.2.3` → `1.3.0` | minor: `0.1.0` → `0.2.0` |
| `fix:`, `perf:` | patch: `1.2.3` → `1.2.4` | patch: `0.1.0` → `0.1.1` |
| `docs:`, `chore:`, `style:`, `test:`, `ci:`, `build:`, `refactor:` | none | none |

`bump-minor-pre-major` in `release-please-config.json` keeps breaking changes on 0.x until the
SDK is declared stable; going to `1.0.0` is a `Release-As: 1.0.0` footer on any commit.

A change that `./gradlew apiCheck` flags as an API break must ship as `feat!:` (or with a
`BREAKING CHANGE:` footer), so the changelog lists it under breaking changes. Renaming, removing or changing the signature of anything in the `*/api/*.api` dumps is such a
change; adding is not.

## Step 1 — the release PR

On every push to `main`, `.github/workflows/release-please.yml` reads the commits since the last
release and keeps one PR open, `chore(main): release <version>`, that bumps the manifest and
prepends the changelog. It goes through CI and a codeowner's review like any other PR; nothing is
ever pushed to `main` directly, so the ruleset needs no bypass. Pushes made only of non-releasable
commits leave no PR.

## Step 2 — merge the release PR

release-please creates tag `v<version>` on the merge commit (`force-tag-creation`, since a draft
release would otherwise hold its tag back until published) and a **draft** GitHub release whose
notes are the changelog.

## Step 3 — staging on tag push

`.github/workflows/staging.yml`, once a codeowner approves the run:

1. The tag is on `main`, and the four publishing secrets are set; otherwise the run fails.
2. `SDK_VERSION` from the tag name.
3. Gates: `apiCheck`, unit tests, the minified demo build, the 16 KB alignment audit.
4. `publishAllPublicationsToMavenCentralRepository` — signs and uploads the four artifacts with
   `automaticRelease = false`. The deployment waits under **Deployments** on
   [central.sonatype.com](https://central.sonatype.com).
5. `dokkaGenerate` (one reference for all four) → `api-reference-<version>.zip`, attached to the
   draft GitHub release, whose notes get the Maven coordinates above the changelog.

## Step 4 — release to Maven Central

Once the staging deployment looks right: **Actions → Release to Maven Central → Run workflow**.
Once a codeowner approves the run, `release.yml` checks out the latest tag on `main`, republishes it
with `-PautoRelease=true` (Central validates and promotes, ~10 minutes) and publishes the draft
GitHub release.

Confirm:

```bash
curl -s "https://central.sonatype.com/api/v1/publisher/published?namespace=eu.purse&name=headless-checkout-android" | python3 -m json.tool
```

Then check that a project with only `mavenCentral()` resolves
`eu.purse:headless-checkout-android:<version>` — the external-app rehearsal in
[ci.md](ci.md#what-is-not-automated).

## Upcoming release

Every update of the open release PR runs `upcoming-release.yml`, which publishes
`<next-version>-SNAPSHOT` (unsigned) to GitHub Packages with the workflow's own token. It is private:
only accounts with read access to this repository can download it. Each build republishes the same
`-SNAPSHOT` version; a fixed version could not be overwritten.

Test the next release the way a merchant integrates it, before it is merged:

```kotlin
repositories {
    maven("https://maven.pkg.github.com/UpStreamPay/purse-headless-checkout-android") {
        credentials {
            username = "<github-username>"
            password = "<classic PAT with read:packages>"  // fine-grained tokens are not accepted
        }
        mavenContent { snapshotsOnly() }
    }
}
dependencies {
    implementation("eu.purse:headless-checkout-android:<next-version>-SNAPSHOT")
}
```

Gradle caches a snapshot for 24 hours: pass `--refresh-dependencies` to pick up a newer one.

A Slack message announces each build. One-time setup: an `upcoming-release` environment holding
`SLACK_WEBHOOK_URL` (an incoming webhook for the channel), deployment branches limited to
`release-please--branches--main--components--headless-checkout-android`.

## Local verification

```bash
./gradlew apiCheck testDebugUnitTest :app:assembleRelease verify16kbAlignment

# Publish every artifact to ~/.m2 (unsigned, 0.0.0-SNAPSHOT) and inspect a POM
./gradlew publishToMavenLocal
cat ~/.m2/repository/eu/purse/headless-checkout-android/0.0.0-SNAPSHOT/*.pom

# Dry-run with a real version and signature (nothing is uploaded)
export SDK_VERSION=1.2.3
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat purse-signing-key.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="…"
./gradlew publishToMavenLocal
ls ~/.m2/repository/eu/purse/headless-checkout-android/1.2.3/
# headless-checkout-android-1.2.3.aar, -sources.jar, -javadoc.jar, .pom, and .asc signatures

# The API reference merchants get
./gradlew dokkaGenerate && open build/dokka/html/index.html
```

## See also

- [CI and quality gates](ci.md) — what blocks a merge, how to refresh an API dump or a baseline
- [Distribution](../decisions/distribution.md) — why Maven Central, why one engine artifact plus optional partner artifacts
- [R8 / ProGuard](../decisions/r8-proguard.md) — what the consumer rules keep and why
- [16 KB alignment](../decisions/16kb-alignment.md) — the native-library audit CI repeats
