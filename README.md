# Moto App Clone — experimental

Vector/Xposed module **`com.lixingchi.motoappclone`** for the Motorola RTWO
RETCN Android 16 build `W1TR36H.1-33` (`865b8-1204f`). Other fingerprints/API
levels are refused. This is a **source/build candidate, not a validated
21,000-profile product**. Do not deploy it over valuable clone data yet.

**Both v0.1 and v0.2 have lost profiles in live tests.** V0.1 reached user 910,
crashed and rebooted, losing 907–910. V0.2's new eighth clone (907) launched
normally but was deleted during an ordinary reboot despite confirmed v0.2
activation, leaving 900–906. The guard hooked `android.os.UserManager`,
but Motorola's boot path calls `UserManagerService` directly. This is a module
bug, not evidence that Vector cannot hook the policy. **Do not resume clone
creation/reboot experiments on v0.2.** V0.3 corrects the hook target and test
fixture; offline checks, owner-0 installation and cold activation with seven
existing clones pass. **Above-budget preservation remains unverified.** An
activation log does not establish safety. This update cannot recover deleted data.

## Implemented

- CLONE-only type and per-parent quotas, and Security Center's per-app limit:
  **21472**. This module does not bypass other user-type quotas, permission
  checks, storage checks or device-policy rules.
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
- V0.3 targets `UserManagerService.removeUser(int)` during `startProfiles()`
  instead of v0.2's unused client wrapper. It retains the running-user limit
  and explicit removal handling outside that operation. The corrected fixture
  rejects the old target, but preservation still needs on-device validation.
- Missing clone providers are retried in owner 0 inside system_server, retaining
  the caller identity, package validation and provider permissions. Pending
  publications notify both original and clone request keys. This covers the
  failed Settings-provider lookup without injection into each cloned app.
- An owner service such as NFC can obtain its own owner-installed package code
  when its clone-context lookup returns null. The fallback requires the same
  caller UID; it does not install NFC in clones or bypass access denials.
- Motorola still owns app installation, labels, startup, settings synchronization
  and per-app removal. This module creates/removes **no profiles by itself**.
- Cold-start-only activation, exact-build/member guards and rollback of partially
  registered hooks. No requirement to disable AlwaysCreateUser: other modules'
  overrides remain their responsibility. Runtime coexistence is not yet tested.

**21472 is the maximum user ID, not an attainable clone count.** Android's
10–21472 pool has **21,463 slots**, shared with other users. The requested quota
is deliberately separate from allocator exhaustion. RAM, storage, IPC and
process limits still apply; no background/low-memory protections are disabled.

## Unfinished end-to-end gates

These are real limitations, not claims of completed runtime support:

1. **Process coverage:** Vector itself is installed **once, device-wide**;
   neither its root module nor its manager needs installing into every clone.
   Installing this module in owner 0 and scoping System Framework covers the
   shared system_server for all users, including the v0.2 crash fixes. Other
   app-local framework hooks still need injection in the relevant processes;
   these server fallbacks do not make all local clone recognition global.
   The reviewed Vector source
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
4. **Actual runtime:** v0.2 cold activation and eighth-app launch passed, but
   **above-budget reboot preservation failed**: 907 was removed, not stopped.
   The observed `mMaxRunningUsers=8` is unchanged. V0.3 corrects the service-side
   target and fixture. Installation and cold activation with seven surviving
   profiles are verified. A new eighth clone (907/serial 912) runs unlocked;
   foreground launch and above-budget reboot preservation remain untested.
   Extended-user provider/NFC fixes, IME, biometrics, app removal and mixed-user
   isolation remain unverified. GMS disablement and Motorola's dependency
   whitelist are unchanged.

The `MotoAppClone: server policy active` log was present during the failed test;
it proves activation, not safety. Do not hot-reload this module. Disabling hooks
or accepting an unsupported OTA does not itself delete profiles, but also does
not protect them from Motorola's destructive boot cleanup. The
`server policy active: version=0.2` log was present in the failed reboot too.
V0.3 needs a coordinated disposable-data test of the actual deletion boundary
before broader profile testing. Never bulk-remove users or recreate the removed IDs as
a supposed recovery.

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

The build runs four Python parser/audit tests and 21,537 JVM policy checks,
including exhausting all 21,463 IDs in memory, classification, quarantine and
GID arithmetic. Another 51 checks execute the production runtime callbacks
against small framework/Xposed doubles: direct service-side boot removal,
separate client/service paths, explicit removal, exception cleanup, provider
publication, unchanged caller identity, access refusals and NFC's own-package
fallback. These do **not** execute Xposed on Android. Test doubles are excluded
from the APK.

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
