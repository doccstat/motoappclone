#!/usr/bin/env python3
"""Inspect supplied APK/JAR declarations against the module's native service contracts."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError('Invalid ULEB128')


def dex_members(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Expected a standard DEX file')

    def uint(offset):
        return struct.unpack_from('<I', data, offset)[0]

    strings = []
    for i in range(uint(56)):
        _, start = uleb(data, uint(uint(60) + 4 * i))
        strings.append(data[start:data.index(0, start)].decode('utf-8', errors='replace'))
    types = [strings[uint(uint(68) + 4 * i)] for i in range(uint(64))]
    protos = []
    for i in range(uint(72)):
        _, result, params = struct.unpack_from('<III', data, uint(76) + 12 * i)
        arguments = [] if not params else [types[struct.unpack_from('<H', data, params + 4 + 2 * j)[0]]
                                          for j in range(uint(params))]
        protos.append('(' + ''.join(arguments) + ')' + types[result])
    fields = []
    for i in range(uint(80)):
        owner, kind, name = struct.unpack_from('<HHI', data, uint(84) + 8 * i)
        fields.append((types[owner], strings[name], types[kind]))
    methods = []
    for i in range(uint(88)):
        owner, proto, name = struct.unpack_from('<HHI', data, uint(92) + 8 * i)
        methods.append((types[owner], strings[name], protos[proto]))
    declared_fields, declared_methods, classes = set(), set(), set()
    for i in range(uint(96)):
        entry = uint(100) + 32 * i
        classes.add(types[uint(entry)])
        offset = uint(entry + 24)
        if not offset:
            continue
        sizes = []
        for _ in range(4):
            value, offset = uleb(data, offset)
            sizes.append(value)
        for size in sizes[:2]:
            index = 0
            for _ in range(size):
                delta, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                index += delta
                declared_fields.add(fields[index])
        for size in sizes[2:]:
            index = 0
            for _ in range(size):
                delta, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                index += delta
                declared_methods.add(methods[index])
    return classes, declared_fields, declared_methods


def audit(directory):
    classes, fields, methods = set(), set(), set()
    paths = sorted([*directory.glob('*.jar'), *directory.glob('*.apk')])
    if not paths:
        raise ValueError('No APK/JAR artifacts supplied')
    hashes = {}
    for path in paths:
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.startswith('classes') and name.endswith('.dex'):
                    c, f, m = dex_members(archive.read(name))
                    classes.update(c); fields.update(f); methods.update(m)

    def descriptor(name):
        return 'L' + name.replace('.', '/') + ';'

    ums = descriptor('com.android.server.pm.UserManagerService')
    checks = {
        (ums, 'getInstance', '()' + ums),
        (ums, 'getNextAvailableAppCloneId', '()I'),
        (ums, 'getNextAvailableId', '()I'),
        (ums, 'scanNextAvailableIdLocked', '()I'),
        (ums, 'addRemovingUserIdLocked', '(I)V'),
        (ums, 'hasAppClone', '()Z'),
        (ums, 'createUserInternalUncheckedNoTracing',
         '(Ljava/lang/String;Ljava/lang/String;IIZ[Ljava/lang/String;'
         'Lcom/android/server/utils/TimingsTraceAndSlog;Ljava/lang/Object;)Landroid/content/pm/UserInfo;'),
        (descriptor('com.android.server.am.ProcessList'), 'computeGidsForProcess', '(II[IZ)[I'),
        (descriptor('android.os.UserHandle'), 'supportsAppClone', '()Z'),
        (descriptor('android.os.UserHandle'), 'isAppCloneUser', '(I)Z'),
        (descriptor('android.os.UserHandle'), 'isSystemOrAppCloneUser', '(I)Z'),
        (descriptor('android.os.UserHandle'), 'isAppCloneUserPair', '(II)Z'),
        (descriptor('android.content.res.Resources'), 'getInteger', '(I)I'),
        (descriptor('android.os.IUserManager$Stub$Proxy'), 'getUserInfo', '(I)Landroid/content/pm/UserInfo;'),
        (descriptor('android.os.IUserManager$Stub$Proxy'), 'getProfileType', '(I)Ljava/lang/String;'),
        (descriptor('android.os.IUserManager$Stub$Proxy'), 'getProfileParent', '(I)Landroid/content/pm/UserInfo;'),
        (descriptor('com.motorola.plugin.core.rule.UserRule'), 'matchUserAlias',
         '(Ljava/lang/String;Ljava/lang/String;Landroid/os/UserManager;Z)Z'),
    }
    helper = descriptor('com.android.server.am.ContentProviderHelper')
    app_thread = descriptor('android.app.IApplicationThread')
    provider = descriptor('com.android.server.am.ContentProviderRecord')
    process = descriptor('com.android.server.am.ProcessRecord')
    provider_get = '(' + app_thread + 'Ljava/lang/String;Ljava/lang/String;IZ)Landroid/app/ContentProviderHolder;'
    controller = descriptor('com.android.server.am.UserController')
    checks |= {
        (ums, 'removeUser', '(I)Z'),
        (controller, 'setInitialConfig', '(ZIZI)V'),
        (controller, 'startProfiles', '()V'),
        (helper, 'getContentProvider', provider_get),
        (descriptor('com.android.server.am.ActivityManagerService'), 'getContentProvider', provider_get),
        (helper, 'getContentProviderImpl', '(' + app_thread + 'Ljava/lang/String;Landroid/os/IBinder;'
         'ILjava/lang/String;Ljava/lang/String;ZI)Landroid/app/ContentProviderHolder;'),
        (helper, 'incProviderCountLocked', '(' + process + provider + 'Landroid/os/IBinder;I'
         'Ljava/lang/String;Ljava/lang/String;ZZJLcom/android/server/am/ProcessList;I)'
         'Lcom/android/server/am/ContentProviderConnection;'),
        (helper, 'publishContentProviders', '(' + app_thread + 'Ljava/util/List;)V'),
        (provider, 'onProviderPublishStatusLocked', '(Z)V'),
        (provider, 'newHolder', '(Lcom/android/server/am/ContentProviderConnection;Z)Landroid/app/ContentProviderHolder;'),
        (process, 'getThread', '()' + app_thread),
        (app_thread, 'notifyContentProviderPublishStatus', '(Landroid/app/ContentProviderHolder;Ljava/lang/String;IZ)V'),
    }
    for name in ('com.android.server.pm.ComputerEngine', 'com.android.server.pm.IPackageManagerBase'):
        checks.add((descriptor(name), 'getApplicationInfo', '(Ljava/lang/String;JI)Landroid/content/pm/ApplicationInfo;'))
    missing = checks - methods
    required_fields = {(ums, name) for name in (
        'mUsers', 'mUsersLock', 'mRemovingUserIds', 'mRecentlyRemovedIds',
        'mAppCloneUserRemoveTimes', 'mUserTypes', 'mSupportsAppClone',
        'MIN_USER_ID', 'MAX_USER_ID', 'MAX_RECENTLY_REMOVED_IDS_SIZE')}
    required_fields |= {(descriptor('android.os.UserHandle'), name) for name in
                        ('MIN_APP_CLONE_USER_ID', 'MAX_APP_CLONE_USER_ID', 'PER_USER_RANGE')}
    required_fields.add((controller, 'mMaxRunningUsers'))
    required_fields |= {(descriptor('com.android.server.pm.UserTypeDetails'), name)
                        for name in ('mMaxAllowed', 'mMaxAllowedPerParent')}
    required_fields |= {(provider, name) for name in ('connections', 'info', 'provider')}
    required_fields |= {(descriptor('com.android.server.am.ContentProviderConnection'), name)
                        for name in ('waiting', 'client')}
    required_fields |= {(descriptor('android.content.pm.ProviderInfo'), 'authority'),
                        (descriptor('android.content.pm.ApplicationInfo'), 'uid')}
    missing_fields = required_fields - {(owner, name) for owner, name, _ in fields}
    # Required named hook targets, not optional caller classes or obfuscated application names.
    source_dir = Path(__file__).resolve().parents[1] / 'src/com/lixingchi/motoappclone'
    source = '\n'.join((source_dir / name).read_text() for name in ('MainHook.java', 'RuntimeFixes.java'))
    import re
    names = re.findall(r'"([a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)+)"', source)
    names = [name for name in names if name not in ('com.motorola.deviceguard', 'com.motorola.launcher3')]
    missing_classes = {name for name in names if descriptor(name) not in classes}
    if missing or missing_fields or missing_classes:
        raise ValueError(f'Missing methods={sorted(missing)}, fields={sorted(missing_fields)}, '
                         f'classes={sorted(missing_classes)}')
    return {'artifacts': hashes, 'method_checks': len(checks), 'field_checks': len(required_fields),
            'lookup_classes': len(set(names)),
            'status': 'Declared members verified; runtime behavior and inlining coverage are not certified'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input_directory', type=Path)
    print(json.dumps(audit(parser.parse_args().input_directory), indent=2))
