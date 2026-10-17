# Little Nest project instructions

## Delivery preference

The maintainer wants completed changes delivered to GitHub without having to
request a separate commit, push or release each time.

- After implementing and verifying a requested change, commit the task's changes
  and push to the current upstream branch. Do not include unrelated user work.
- For changes to the Android app, also publish a new stable GitHub release with
  the same-key signed APK and portable checksum for Obtainium. Increment both
  Android version fields, write release notes, wait for successful GitHub checks,
  and follow [the release procedure](../wiki/Releases.md).
- Documentation-only changes need a commit and push, but not a new APK release.
- Verify the public latest-release API, downloadable APK, checksum and original
  signing certificate before reporting an app update as delivered.
- If validation, credentials, signing material or publication is blocked, report
  the blocker explicitly. Never describe local-only work as published.
- An explicit request to keep work local, pause publishing or use a draft
  overrides this default. Never force-push, rewrite published history or replace
  an existing release's APK without explicit approval.

## Private data and compatibility

- Never commit or upload private exports, databases, signing keys/passwords,
  OAuth credentials or diagnostics containing personal records. Only curated
  synthetic-data screenshots belong in the repository.
- Preserve existing import identity seeds and storage keys; they prevent
  duplicate imports and keep backups/sync compatible.
- See [development and verification](../wiki/Development.md) and
  [planning semantics and limitations](../wiki/Care-Plan.md).
