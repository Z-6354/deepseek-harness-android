# Runtime architecture deployment — 2026-10-07

## Deployed target

The APP's origin is `https://dsh.wannian.fun/`, served by the Ubuntu host
the production server. Its installed official DSH is `0.2.1-alpha.1`. The actual
WebServer/Connection interfaces and compiled UiWorkspaceService were copied into
an isolated contract snapshot and probed before deployment. The custom backend
now selects `npm:0.2.1-alpha.1` explicitly; this is an operator attestation,
not a runtime binary proof. Official core and model/chat configuration were not modified.

| Artifact | Installed version | Client SHA-256 |
| --- | --- | --- |
| hanaccount | 2.4.2 | `d22ee4f2b4ca12138cadf079bef4c012a2c17ce9f50a0f31565a93d1cf218ec8` |
| hanui | 0.2.9 | `12c84d3620e30b0d792e679e7adaed33878d26777e5b7815ee914051d38d62ae` |

The existing symlinks were preserved: hanaccount points to
`/home/ubuntu/.dsh/plugins/dsh-local-hanaccount-2.4.1`; hanui points to
`/data/dsh-mobile-hanui`. The directory name does not determine the installed
package version. Both generated bundles were updated and the module revision changed.

The debug APK was newly installed on the existing MuMu device
`127.0.0.1:16384`, package `com.labteto.dshmobile.debug`. There was no existing APP
package to upgrade and no release signing key available. This does not constitute
a release-signed upgrade on the user's physical phone. No application was uninstalled.

## Rollout and rollback

The target nginx vhost was briefly placed in maintenance at its five `dsh_web`
proxy seats. The service was stopped, current auth data backed up, custom plugin
files and the explicit frontend profile updated, and the service restarted.
The external proxy was restored only after the actual guard was ready and
anonymous protected static requests returned 401. The nginx file was restored
byte-for-byte; its SHA-256 is
`3cd9cc12103ed4def8ff913910603263c27196a7bb62e3199b0018f43e1c674f`.

Restricted backups remain on that server under
`/home/ubuntu/dsh-runtime-deploy-20261007/`:

- `before-correct-runtime.tar.gz`: plugins, profile files and nginx;
  SHA-256 `34498cc8d0301e6cd0bc6f0b022cbfe86bdee3f3c4b1342894e7f501be5e1adc`.
- `before-correct-auth-data.tar.gz`: stopped-service auth data;
  SHA-256 `b9000760ad056c903a455c49fccd2dc8e74a629b9edc5ed763af80b9b5888d0a`.
- `hanui-before-0.2.9.tar.gz`: the intermediate UI before the lifecycle correction.
- `nginx-dsh.before`: the original vhost, retained with restricted permissions.

Rollback must close this vhost, stop the service, restore the appropriate custom
plugin/profile files to their existing targets, start and verify the guard, then
restore nginx. Auth state must be reconciled with the current state rather than
blindly replacing it and losing later logins or revocations.

An initial target identification error updated the separate `<另一台服务器>`
VPS service. It was fully rolled back to hanaccount 2.3.11 and hanui 0.2.5,
the original nginx file and original status contract. One temporarily migrated
row had only its added authVersion/scope fields retired; current rows and times
were retained. Its stopped-state and pre-rollback backups remain restricted on
that server. No target change was retained there.

## Authentication and compatibility evidence

The correct target already had ten versioned login records, so the conservative
legacy migration script was not run there. Existing login remained valid; the
new scope was minted and persisted by the normal authenticated `/auth/me` path.
No session timestamps were modified by a deployment migration.

Actual device cookies were reused in memory, with no values printed or saved.
The actual backend matrix was:

| Cookies | Gate authenticated | Native authenticated | resumeScope present |
| --- | --- | --- | --- |
| Neither | false | false | false |
| Gate only | true | false | false |
| Native only | false | true | false |
| Both | true | true | true |

Gate-only entry performs its normal native-cookie restoration redirect. Both
identities return official HTML; anonymous entry returns the login page.
Anonymous `/assets/` and `/plugins/` requests returned 401. The isolated probe
against the installed real Cordis/WebServer/Connection also passed all four
HTTP/static/WS combinations, logout replay rejection and closed guard disposal.
The production service is active and reports `auth_ready` for the alpha1 adapter.
The existing optional-loader import failure limitation remains; `deploymentReady`
is intentionally still false. The maintenance gate protected this rollout.

The installed alpha1 frontend uses UiWorkspace as the mainView owner, not the
older ISessions open/clear interface. The versioned private adapter calls the
audited clearMain method without modifying private selection/ref fields. It waits
for both catalogs, cancels the official asynchronous initial navigation, coalesces
retain/release notifications, and borrows the retained history binding. Unknown
frontend profiles reject the private path. The actual compiled workspace probe
passed synchronous old selection and late asynchronous blank restoration cases.

The first production UI attempt exposed a real Cordis lifecycle error: returning
a nested inject Fiber from apply is an invalid effect. UI 0.2.9 returns its disposer
instead. The permanent `scripts/probe-client-cordis.mjs` now executes the generated
Auth/UI bundles against installed Cordis and verifies a single startup mount,
shared auth visibility, dependency removal and disposal. This was a lifecycle
error, not an auth service isolation defect.

## Device behavior and measured limits

The first new scope had no target, as expected. A normal click on an existing
nonempty conversation established the scoped target without creating a chat or
sending a message. Subsequent force-stop cold starts restored that target; the
public session-owned composer probe verified history, active body and the input,
then retired the startup surface. The final view had one input and no startup or
preview dialog. The official preview dialog was observed during initial setup.

| Cold sample | Native begin → inputReady | Plugin start → inputReady |
| --- | --- | --- |
| 1 | 20,049 ms | 4,478.5 ms |
| 2 | 20,800 ms | 4,506.7 ms |
| 3 | 18,689 ms | 4,051.9 ms |

These are separate relative clocks. The plugin figures are not total launch time.
There is no P95 claim, no continuous home-flash measurement and no completed
historical-image RPC/bytes measurement. The image slow-loading issue has not been
proved fixed. The APP's whole cold start remains slow.

Hot background/foreground returned the existing task: Performance timeOrigin was
unchanged, diagnostic count stayed at ten, the active conversation remained and
no new document/load was observed. Device screenshots and bounded traces are in
ignored `artifacts/deploy-20261007/`.

Static cache evidence shows one stable site partition and persisted batch files.
All three batch metadata files have JavaScript MIME, public immutable cache
headers, Vary Accept-Encoding and no Set-Cookie. Batch URL digests/revisions were
stable between the latter probes. Smaller batches replayed locally. The largest
11.29 MB batch still incurred a long load and did not consistently appear in native
interception logs: one sample reported 5,556,597 transferred bytes and 12.64 s in
Resource Timing. A read-only Network probe observed GET Script requests, 200
JavaScript responses, the same cache headers and no service-worker responses.
The exact interception/replay gap remains unverified; no cache algorithm or
unrelated nginx compression configuration was changed to hide it.

## Validation

- hanui 0.2.9: 94/94 Node tests, generated-bundle check, syntax check and pack dry-run.
- hanaccount 2.4.2: 142 passing tests and six explicitly skipped live tests, generated
  bundle/syntax checks and pack dry-run.
- Installed official alpha1 backend and actual compiled UiWorkspace probes: pass.
- Actual installed Cordis plus generated Auth/UI bundle lifecycle probe: pass.
- Existing Android evidence remains: 56 unit tests, debug build and one targeted
  MuMu BrowserRuntime instrumentation test. Android source was not changed during deployment.
- No commit, push, official-core modification, chat send or destructive login reset.
