Jira: <!-- SDK-XXXXX — every PR links the ticket that records the reason, the security impact and the approval (PCI-DSS 6.5.1) -->

## What and why

<!-- What changes, and what problem it solves. -->

## Security impact

<!-- Delete the ones that do not apply. -->

- [ ] None: this change does not touch payment data, network calls, WebViews, redirects or the release pipeline.
- [ ] This change touches one of them, and the effect is described above.

- [ ] No real card number, CVV, token, API key or merchant secret appears in the diff, the tests,
      the commit messages, or this description.

## Checks

- [ ] `./gradlew ktlintCheck detekt apiCheck testDebugUnitTest lint` passes
- [ ] Commit subjects follow Conventional Commits and read for an integrator: they become the changelog
- [ ] Public API changes are reflected in the integration docs
