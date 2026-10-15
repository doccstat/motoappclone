#!/usr/bin/env python3
"""Read-only exact-artifact and DEX member checks. No decompiler or device required."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

HASHES = {
    'framework.jar': 'e46185e9155f420e1f55949b6e7999250a079a957192be40d54e453a2815fc47',
    'services.jar': '9eb15bc0c1feb271d86c2ce9025feb29d1e01e170e34fd62cd8d68beede40204',
    'PrcSecurityCenter.apk': 'c2076d421ef2b74efef667c255d1fd2cdb5659b4574a724fc4f6228b18848107',
    'Launcher3QuickStep.apk': 'a8f766d645543cdd17e3f11f7ac3e8c4015e9f17af47076af43021566afe10fe',
    'moto-core_services.jar': '1f82be58c58a54b98995302ffe26d85713ba3969cb5937e2d29c10324cbd6e4d',
    'com.motorola.frameworks.core.addon.jar': '07bd2b7c80692a1aec59a3ebad8c1db011a9525732330cbdf904b6246ed9ee38',
    'moto-settings.jar': 'e0a7036a686c0ad8473384cd1fee0a1e691ca3199a9c8c7e10d420a3a8ece5c6',
}


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
    for filename, expected in HASHES.items():
        path = directory / filename
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Unsupported artifact hash: ' + filename)
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
        (descriptor('v2.d'), 'x', '()I'),
        (descriptor('com.motorola.plugin.core.rule.UserRule'), 'matchUserAlias',
         '(Ljava/lang/String;Ljava/lang/String;Landroid/os/UserManager;Z)Z'),
    }
    helper = descriptor('com.android.server.am.ContentProviderHelper')
    app_thread = descriptor('android.app.IApplicationThread')
    provider = descriptor('com.android.server.am.ContentProviderRecord')
    process = descriptor('com.android.server.am.ProcessRecord')
    provider_get = '(' + app_thread + 'Ljava/lang/String;Ljava/lang/String;IZ)Landroid/app/ContentProviderHolder;'
    checks |= {
        (ums, 'removeUser', '(I)Z'),
        (descriptor('com.android.server.am.UserController$Injector'), 'getUserManager', '()' + ums),
        (descriptor('com.android.server.am.UserController'), 'startProfiles', '()V'),
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
        'mAppCloneUserRemoveTimes', 'mUserTypes', 'mSupportsAppClone')}
    required_fields |= {(descriptor('com.android.server.pm.UserTypeDetails'), name)
                        for name in ('mMaxAllowed', 'mMaxAllowedPerParent')}
    required_fields |= {(provider, name) for name in ('connections', 'info', 'provider')}
    required_fields |= {(descriptor('com.android.server.am.ContentProviderConnection'), name)
                        for name in ('waiting', 'client')}
    required_fields |= {(descriptor('android.content.pm.ProviderInfo'), 'authority'),
                        (descriptor('android.content.pm.ApplicationInfo'), 'uid')}
    missing_fields = required_fields - {(owner, name) for owner, name, _ in fields}
    # Named hook/deoptimization targets must actually be defined in the reviewed files.
    source_dir = Path(__file__).resolve().parents[1] / 'src/com/lixingchi/motoappclone'
    source = '\n'.join((source_dir / name).read_text() for name in ('MainHook.java', 'RuntimeFixes.java'))
    import re
    names = re.findall(r'"([a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)+)"', source)
    names = [name for name in names if name not in ('com.motorola.deviceguard', 'com.motorola.launcher3')]
    names += ['com.android.server.pm.UserManagerService$LocalService']
    missing_classes = {name for name in names if descriptor(name) not in classes}
    if missing or missing_fields or missing_classes:
        raise ValueError(f'Missing methods={sorted(missing)}, fields={sorted(missing_fields)}, '
                         f'classes={sorted(missing_classes)}')
    return {'artifacts': HASHES, 'method_checks': len(checks), 'field_checks': len(required_fields),
            'deoptimization_and_lookup_classes': len(set(names)),
            'status': 'Exact artifacts and declared members verified; runtime hooks NOT tested'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input_directory', type=Path)
    print(json.dumps(audit(parser.parse_args().input_directory), indent=2))
