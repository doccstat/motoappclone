# Moto App Clone — experimental

Vector/Xposed module **`com.lixingchi.motoappclone`** for the Motorola RTWO
RETCN Android 16 build `W1TR36H.1-33` (`865b8-1204f`). Other fingerprints/API
levels are refused. This is a **source/build candidate, not a validated
21,000-profile product**. Do not deploy it over valuable clone data yet.

## Implemented

- CLONE-only type and per-parent quotas, and Security Center's per-app limit:
  **21472**. Other user-type quotas, permission checks, storage checks and
  device-policy rules are not bypassed.
- Clone allocation prefers existing Moto IDs, then extends through **21472**,
  then uses remaining free IDs **10–899**. Existing IDs, serials and data are
  never migrated. Collisions with other users and pending deletions are rejected.
- Clone recognition uses actual **CLONE type + owner-0 parent**, not a range
  that would accidentally grant clone privileges to work/private/secondary users.
- Native clone enumeration, extended-ID-safe removal bookkeeping, and actual
  clone storage groups replace the ten-slot assumptions. Removed IDs stay
  quarantined until reboot, including for ordinary users: living processes may
  still hold a removed clone's supplemental GID.
- Launcher plugin `APP_CLONE` matching no longer hardcodes 900–909. Reviewed
  recognition callers are deoptimized to address ART inlining, including
  provisioning, package visibility, providers, permissions, notifications,
  accounts, input methods, widgets and biometric integration.
- Motorola still owns app installation, labels, startup, settings synchronization
  and per-app removal. This module creates/removes **no profiles by itself**.
- Cold-start-only activation, exact-build/member guards, rollback of partially
  registered hooks, and refusal to enable creation while a global creation
  override is active. Disable AlwaysCreateUser rather than stacking it.

**21472 is the maximum user ID, not an attainable clone count.** Android's
10–21472 pool has **21,463 slots**, shared with other users. The requested quota
is deliberately separate from allocator exhaustion. RAM, storage, IPC and
process limits still apply; no background/low-memory protections are disabled.

## Unfinished end-to-end gates

These are real limitations, not claims of completed runtime support:

1. **Process coverage:** Vector itself is installed **once, device-wide**;
   neither its root module nor its manager needs installing into every clone.
   Installing this module in owner 0 and scoping System Framework covers the
   shared system_server for all users. But this candidate also hooks app-local
   framework calls: those hooks need injection in the relevant launcher/provider
   and clone-app processes, not just system_server. The reviewed Vector source
   additionally requires this module's package to be marked installed in each
   targeted app's user; Android shares the APK binary. This installation-state
   requirement has not been verified against the phone's installed Vector 3080.
   Recommended scope metadata is not automatic scope installation, and the
   candidate does not provision new clone scopes/package registrations. Do not
   assume `initZygote` makes a scoped module global.
2. **Already-running processes:** supplemental GIDs are fixed at process
   startup. Existing owner/clone processes need an orderly restart after the
   profile topology changes to see newly added groups. No automatic process
   killer or forced reboot is included.
3. **Large lists:** stock Binder user/profile list APIs are not paginated by
   this module. At large counts, parcel size and UI enumeration can fail before
   the ID pool is exhausted. Large supplemental-group/zygote requests and the
   native FUSE/SELinux paths also need validation. Do not bulk-create thousands
   of users to discover those limits on a daily-use phone.
4. **Actual runtime:** cold hook timing, inlining (including anonymous/synthetic
   callers), profiles beyond 909, launcher, media/provider routing, IME,
   biometrics, reboot/unlock, app removal, and mixed-user isolation remain
   untested. GMS disablement and Motorola's dependency whitelist are unchanged.

Activation must be evaluated with disposable data and independent recovery
access. Require the `MotoAppClone: server policy active` log, not merely a
module-enabled checkbox or entry-class loading. Do not hot-reload this module.
Disabling it or taking an unsupported OTA does **not** delete existing profiles;
extended IDs will lose required integration. Re-enable the matching build or
perform deliberate, scoped cleanup while it is working—never bulk-remove users.

## Build and offline checks

Requirements: Python 3, a JDK supporting Java 8 targets, Android SDK platform 36
and build-tools 37.0.0. No Gradle, device commands or network downloads run in
the build. All outputs go outside source, under the selected build cache.

Download the compile-only [Xposed API 82 JAR](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar)
separately. Its required SHA-256 is:

```text
f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
```

Set these environment variables, then run `python build.py`:

```text
JAVA_HOME=<JDK directory>
ANDROID_HOME=<Android SDK directory>
XPOSED_API_JAR=<downloaded api-82.jar>
BUILD_DIR=<home cache directory>/motoappclone/build
```

The default output is aligned **`MotoAppClone-unsigned.apk`**. Optional signing
uses an **existing** key outside source/cache, never generates a credential:

```text
SIGNING_KEYSTORE=<existing keystore>
SIGNING_KEY_ALIAS=<alias>
SIGNING_STORE_PASSWORD=<password supplied privately in environment>
SIGNING_KEY_PASSWORD=<password supplied privately in environment>
```

Signed output is **`MotoAppClone.apk`**. Use a stable private release key for
future deployment; an ordinary Android debug-signed build is experimental only.

The build runs four Python parser/audit tests plus JVM policy checks, including
exhausting all 21,463 candidate IDs in memory, mixed-user classification, occupied
and quarantined IDs, non-clone pairs, exact-build rejection and GID arithmetic.
These tests do **not** execute Xposed on Android.

For static hook verification, set `FIRMWARE_DIR` to a directory containing the
seven matching, legally obtained artifacts named in `tools/audit_firmware.py`,
or run:

```sh
python -B tools/audit_firmware.py <artifact-directory>
```

The audit rejects other hashes and verifies declared DEX classes, fields and
method signatures, not just references to them. Proprietary artifacts are not
included. This is a new module, not an import of AlwaysCreateUser or a Vector
framework fork.
