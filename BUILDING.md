# Build and support

## Build

Use Python 3.8+, JDK 21, Android SDK platform 36 and build-tools 37.0.0. The
build uses installed tools and runs no downloads or device commands. Set:

```text
JAVA_HOME=<JDK directory>
ANDROID_HOME=<Android SDK directory>
XPOSED_API_JAR=<api-82.jar>
BUILD_DIR=<output directory outside source>
```

Download the compile-only [Xposed API 82 JAR](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar)
separately. Its required SHA-256 is
`f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`.
The additional runtime deoptimization API is resolved reflectively.

Run `python3 -B build.py`. It runs the Python and JVM tests, compiles the module,
and produces `MotoAppClone-unsigned.apk` and a `.sha256` sidecar. No credentials
are generated. Output defaults to `~/.cache/motoappclone/build`; `BUILD_TOOLS`
can explicitly select another compatible tools directory or host wrappers.
Tests and the Xposed API are excluded from the APK.

## Compatibility contracts

There is no device fingerprint or firmware-hash allowlist. The module requires
Motorola's native clone capability and the named framework/service interfaces
used by its hooks. Hook parameter and return types and required field types are
checked; an incompatible installation rolls back its process-local hooks and
logs the failure. Late system-server injection is refused.

- Allocation reads native clone and shared-user bounds from the framework.
  Existing IDs and serials are preserved; occupied and removing IDs are skipped.
- Actual CLONE type and main-user parent determine identity. Work, private and
  secondary profiles are not granted clone privileges by numeric resemblance.
- Deleted IDs remain quarantined until reboot to prevent reuse while processes
  retain their storage groups. Native removal-history bounds come from the service.
- The quota resource is matched by name, not a numeric resource ID. Security
  Center's DEX classes are discovered for deoptimization rather than maintained
  as a firmware-specific list of obfuscated names. This is one-time process-start
  work; its overhead still needs device measurement.
- Framework integration points use named service classes. Optional caller classes
  absent from a platform are skipped; required hook contracts are not. This is
  not a complete discovery of every possible firmware's inlined clone checks.
- Boot-time cleanup suppression is limited to actual clones within the native
  profile-start operation. Explicit removals outside that operation are unchanged.
- Provider retries retain the original Binder caller and permission/package checks.
  Owner package-code fallback requires the caller's own UID. No access-denial
  retry, Binder identity clearing or blanket per-user permission grant is added.

Only scoped processes load app-local hooks. Enable the module for the relevant
user as required by the framework; scope recommendations do not automatically
provision future clones. The system-server hooks are shared by all users.

Host checks exercise allocation exhaustion, alternate platform ranges, identity,
quarantine, storage groups, hook contracts/rollback, deoptimization API variants,
boot cleanup, provider publication and permission refusals. They are not Android
runtime tests. Before relying on a firmware, check creation, launch, reboot
retention, explicit removal, unchanged main-user/work-profile data, notifications,
input methods and biometrics using disposable profiles.

An optional offline declaration audit accepts legally obtained APK/JAR files
from any build, records their hashes and checks the required DEX declarations:

```sh
python3 -B tools/audit_firmware.py <artifact-directory> > firmware-audit.json
```

Include framework/services JARs and the launcher APK with their dependency JARs.
Missing members fail the audit; matching declarations do not certify semantics,
ART inlining coverage or runtime safety. `FIRMWARE_DIR` enables the same audit
during a build. No proprietary binaries are included or required by public CI.

## Signing and releases

Supply an existing permanent keystore outside source/cache:

```text
SIGNING_KEYSTORE=<existing keystore>
SIGNING_KEY_ALIAS=<alias>
SIGNING_STORE_PASSWORD=<password supplied privately in environment>
SIGNING_KEY_PASSWORD=<password supplied privately in environment>
SIGNING_CERT_SHA256=<64-hex-digit certificate fingerprint>
```

Run `python3 -B build.py --release`. Missing credentials/keys or an invalid pin
are refused. The build verifies the APK signature, its single signer and pin,
and alignment before writing `MotoAppClone.apk` and its checksum. Preserve the
key for future updates; never include it, its base64 encoding or passwords in
source, logs or release assets.

For compatibility, setting `SIGNING_KEYSTORE` without `--release` still signs
with the supplied key but does not enforce a certificate pin. With no signing
environment the default is unsigned. Use `--release` for distribution.

GitHub's read-only `android.yml` builds unsigned artifacts for main pushes, pull
requests and manual dispatches. `release.yml` signs only this repository's main
branch, using a main-restricted `release` environment:

| Name | Environment type |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Secret |
| `ANDROID_KEYSTORE_PASSWORD` | Secret |
| `ANDROID_KEY_ALIAS` | Secret |
| `ANDROID_KEY_PASSWORD` | Secret |
| `ANDROID_SIGNING_CERT_SHA256` | Variable |

The temporary decoded key is removed on step exit. Main pushes publish the
manifest's `versionCode-versionName` tag; a same-version run replaces its tag and
assets, while a superseded commit skips publication. Versions are explicitly
maintained in the manifest: **0.5 / code 5**, tag **5-0.5**.

Automatic releases are prereleases, not latest. Manual **Signed release**
dispatch has an explicit `prerelease` input (default true); choosing false
publishes stable/latest. This does not imply additional device validation.
No workflow installs on a device. After the LSPosed listing exists and its README
export is complete, configure `LSPOSED_RELEASE_TOKEN` with access only to that
listing repository (Contents read/write). This explicitly enables automatic
`distribute-release.yml` dispatch after publishing. Without it, signing succeeds
and reports that distribution is disabled. Manual distribution requires an exact
source tag and main branch; it preserves the source prerelease/stable status and
verifies the checksum, certificate, package and version before upload.

## Diagnostics

With ADB available, create a new redacted JSON report:

```sh
python3 -B tools/diagnostics.py --output motoappclone-diagnostics.json
```

Use `--serial <device>` when selecting among connected devices. To include a
summary of module events, export the framework log through its manager and add
`--framework-log <file>`. The report includes build/device versions, operation
results and known event/error counts—not the device serial, profile names,
accounts, raw package dumps, raw log text or signing credentials. Existing
output files are not overwritten. Nothing is uploaded, rooted, restarted or
modified on the device. Scope cannot be read through this command; include your
selected framework scope separately after reviewing it.

For CI, export GitHub's masked logs:

```sh
gh run view <run-id> --repo doccstat/motoappclone --log > build-diagnostics.log
```

For local build failures, redirect build output to a file outside source and
include the manifest version, JDK/SDK/build-tools versions and output checksum.
Never dump the environment. Review diagnostics before sharing them in an issue.

## Language

English module metadata lives in `res/values/strings.xml`. There is no settings
UI or runtime translation service. English is the only current catalog and the
Android fallback; no new languages are selected by this release. Future targets
should be generated into disposable build output with source tracking and
selective overrides, not committed as parallel generated catalogs. Diagnostic
JSON uses stable keys; Unicode device metadata is preserved. No names or
user-supplied content are translated or normalized.
