# MailMySMS

Android app (`com.antolabs.mailmysms`) that backs up SMS/MMS/RCS and call history by writing
directly into the user's own email account via IMAP `APPEND` — no SMTP relay, no server run by
the developer in the data path. This constraint is load-bearing: it's the basis of every privacy
claim in the app, the privacy policy, and the website. Don't write or accept copy that implies
anything more of a pipeline than this.

Companion website at `docs/` (deployed to mailmysms.app via GitHub Pages). Full product/feature
history lives in `.local-notes/EVOLUTION.md` and voice guidance in `.local-notes/PHILOSOPHY.md`
when that folder is present on the current machine — it's gitignored, so don't assume it exists
in a fresh clone or an isolated worktree.

## Before writing or editing any user-facing copy

App strings, the website, store listings, release notes — verify every technical or privacy
claim against the actual implementation before using it, even when the claim comes from an
earlier draft, a spec, or something that already reads well. This project has more than once
caught and corrected an overclaim from an external draft (a false "choose your backup folders"
claim, an unqualified RCS guarantee) by re-checking against the real code rather than trusting
the copy. Don't skip that check because a claim sounds plausible.

**Voice**: first person singular, unhurried, no "we"/team language. Built and maintained by one
person in spare time — stated plainly, not undersold. Feedback/support asks are easy to decline,
never guilt-framed. Prefer fewer UI elements and plainer language over anything that reads as
more corporate or more impressive than the app actually is.

## Release workflow

1. Commit and push the change to `main`.
2. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
3. `./gradlew assembleRelease assembleDebug`.
4. `gh release create vX.Y app/build/outputs/apk/release/app-release.apk --title "vX.Y" --notes "..."`.
5. Install the fresh debug APK on connected physical test devices: `adb install -r
   app/build/outputs/apk/debug/app-debug.apk`. Devices connect over wireless ADB debugging —
   ports are ephemeral and change each session, so ask for a current `IP:port` (and pairing code
   if re-pairing) rather than reusing one from a previous session.

## Website deployment (docs/ → mailmysms.app)

Deployed via `.github/workflows/pages.yml` using GitHub's Actions-based Pages deployment (not
the legacy branch-based builder). **Do not add a top-level `concurrency:` block to this
workflow** — it silently conflicts with the internal concurrency group `actions/deploy-pages`
already manages for the `github-pages` environment, producing a run with zero jobs and no
diagnostic output. If a Pages deploy ever fails silently with no logs, check for a reintroduced
`concurrency:` block before anything else.

`mailmysms.app` is the one canonical domain. `mailmysms.com` is registered defensively and
exists only as a 301 redirect to `mailmysms.app` — don't treat it as a second identity to
maintain content for.

Public contact addresses used in app code (`AppInfo.FEEDBACK_EMAIL`) and site copy are
`support@mailmysms.app` and `privacy@mailmysms.app` — never a raw personal email address.

## What needs a human in the loop

None of the following can be handed off through a file, by design — always check with the user
rather than assuming or trying to work around it:

- **Release signing**: `release-signing/` (keystore + properties) is gitignored and only exists
  on machines it's been deliberately placed on. No signing config, no release build.
- **GitHub auth**: `gh` CLI needs its own login; pushing changes to `.github/workflows/*.yml`
  additionally needs the `workflow` OAuth scope (`gh auth refresh -h github.com -s workflow`,
  run by the user — this is an interactive/browser step).
- **Wireless ADB debugging**: pairing and connect ports are ephemeral per session; ask for
  current values rather than reusing old ones.
- **Domain/DNS (Porkbun)** and **the project's Gmail account/App Password**: managed directly by
  the user in their respective dashboards; not something to script around.

## Verifying claims about live infrastructure

Don't assert that the site, DNS, a cert, or email delivery is working based on dashboard state
or a plausible-sounding explanation — check with direct evidence (`curl` without `-k`, `openssl
s_client`, `gh api` status fields, actual response bodies) before reporting something as
confirmed.
