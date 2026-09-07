# Release

How to cut a release of the Purse Headless Checkout Android SDK. Replayable by any team member:
nothing below depends on a person's machine.

> For **SDK contributors**. Merchants integrating the published SDK start with
> [Getting started](../integration/getting-started.md).

## Overview

The release is driven from GitHub, exactly like [securefields-android](https://github.com/UpStreamPay/purse-securefields-android):

| Step | Trigger | What happens |
|---|---|---|
| 1 | Push to `main` | `auto-tag.yml`: conventional commits decide the next version; tag `v<version>` is pushed |
| 2 | Tag push (`v*`) | `staging.yml`: gates run, the four artifacts are signed and uploaded to the Central Portal as a **pending deployment**, a **draft GitHub release** gets the generated API reference attached |
| 3 | Manual workflow | `release.yml`: re-publishes from the latest tag with `automaticRelease = true`, promoting to **Maven Central**; the GitHub release is published |

Merging to `main` is all it takes to start a release. The pending deployment lets you inspect POMs
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
| `USP_GITHUB_ADMIN_ACCES_TOKEN` | auto-tag | Fine-grained PAT with **Contents: Read and Write** on this repository, so the pushed tag triggers `staging.yml` (`GITHUB_TOKEN` cannot trigger downstream workflows). The misspelling is intentional and must match. |
| `GPG_PRIVATE_KEY` | staging, release | The organisational signing key, armored (`purse-signing-key.asc`) |
| `GPG_PASSPHRASE` | staging, release | Its passphrase |
| `MAVEN_CENTRAL_USERNAME` | staging, release | Username of a Central Portal **user token** of a publisher on `eu.purse` |
| `MAVEN_CENTRAL_PASSWORD` | staging, release | Its password |

Both publish workflows start with a `check-secrets` job and skip with a warning (not a failure)
when any is missing, so a fork or a fresh clone of the repository cannot half-publish.

## Versioning

The version lives **only in the git tag**; no file carries it. Gradle reads `SDK_VERSION`, set by
the workflows from the tag; a local build is `0.0.0-SNAPSHOT`.

| Commit prefix | Bump | Example |
|---|---|---|
| `feat!:` / `BREAKING CHANGE:` footer | major | `1.2.3` → `2.0.0` |
| `feat:` | minor | `1.2.3` → `1.3.0` |
| `fix:`, `perf:`, `refactor:` | patch | `1.2.3` → `1.2.4` |
| `docs:`, `chore:`, `style:`, `test:`, `ci:`, `build:` | none | no release |

A change that `./gradlew apiCheck` flags as an API break must ship as `feat!:` (or with a
`BREAKING CHANGE:` footer): the release-manager preset is `conventionalcommits`, so the `!`
shorthand bumps the major. Renaming, removing or changing the signature of anything in the `*/api/*.api` dumps is such a
change; adding is not.

## Step 1 — auto-tag on push to main

`.github/workflows/auto-tag.yml` calls
`UpStreamPay/usp-reusable-workflows/.github/workflows/release-manager.yaml@reusable-v0`, which
reads the commits since the last `v*` tag, computes the bump and pushes the annotated tag with
`USP_GITHUB_ADMIN_ACCES_TOKEN`. `skip-version-file: true` because the version has no file.

## Step 2 — staging on tag push

`.github/workflows/staging.yml`:

1. `SDK_VERSION` from the tag name.
2. Gates: `apiCheck`, unit tests, the minified demo build, the 16 KB alignment audit.
3. `publishAllPublicationsToMavenCentralRepository` — signs and uploads the four artifacts with
   `automaticRelease = false`. The deployment waits under **Deployments** on
   [central.sonatype.com](https://central.sonatype.com).
4. `dokkaGenerate` (one reference for all four) → `api-reference-<version>.zip`, attached to a **draft** GitHub release whose
   notes carry the Maven coordinates and the auto-generated changelog.

## Step 3 — release to Maven Central

Once the staging deployment looks right: **Actions → Release to Maven Central → Run workflow**.
`release.yml` republishes from the latest tag with `-PautoRelease=true` (Central validates and
promotes, ~10 minutes) and publishes the draft GitHub release.

Confirm:

```bash
curl -s "https://central.sonatype.com/api/v1/publisher/published?namespace=eu.purse&name=headless-checkout-android" | python3 -m json.tool
```

Then check that a project with only `mavenCentral()` resolves
`eu.purse:headless-checkout-android:<version>` — the external-app rehearsal in
[ci.md](ci.md#what-is-not-automated).

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
