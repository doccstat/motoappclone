package com.lixingchi.motoappclone;

import android.os.Binder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real callback code with framework/Xposed test doubles, not an Android emulator. */
public final class RuntimeHooksTest {
    public static final Map<String, Class<?>> TYPES = new HashMap<>();
    private static final Set<Integer> clones = new HashSet<>();
    private static int checks;
    interface Action { void run() throws Throwable; }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Runtime check " + checks);
    }
    private static void denied(Action action) throws Throwable {
        try { action.run(); throw new AssertionError("Expected SecurityException"); }
        catch (SecurityException expected) { checks++; }
    }

    public interface IBinder {}
    public static final class AppThread {
        public final List<String> notifications = new ArrayList<>();
        public void notifyContentProviderPublishStatus(Holder holder, String authority, int user, boolean success) {
            notifications.add(user + ":" + success + ":" + (holder.provider != null));
        }
    }
    public static final class ProcessRecord {
        public AppThread thread;
        public AppThread getThread() { return thread; }
    }
    public static final class ProcessList {}
    public static final class ProviderInfo { public String authority = "settings"; }
    public static final class Connection {
        public boolean waiting;
        public ProcessRecord client;
        public int expectedUser;
    }
    public static final class Holder {
        public Object provider;
        public Connection connection;
    }
    public static final class Provider {
        public Object provider = new Object();
        public ProviderInfo info = new ProviderInfo();
        public List<Connection> connections = new ArrayList<>();
        public Holder newHolder(Connection connection, boolean local) {
            Holder holder = new Holder(); holder.provider = provider; holder.connection = connection;
            return holder;
        }
        public void onProviderPublishStatusLocked(boolean success) {
            for (Connection connection : connections) {
                if (connection.waiting && connection.client != null && connection.client.thread != null) {
                    connection.client.thread.notifyContentProviderPublishStatus(
                            newHolder(success ? connection : null, false), info.authority, connection.expectedUser, success);
                }
                connection.waiting = false;
            }
        }
    }
    public static final class ProviderHelper {
        public final Provider owner = new Provider();
        public final ProcessRecord client = new ProcessRecord();
        public final List<Integer> requests = new ArrayList<>();
        public final List<Integer> callerUids = new ArrayList<>();
        public boolean localPresent, denyTarget, denyOwner;
        public Holder local = new Holder();
        public Holder getContentProvider(AppThread thread, String callerPackage, String authority,
                                         int user, boolean stable) throws Throwable {
            requests.add(user); callerUids.add(Binder.getCallingUid());
            if (!"caller".equals(callerPackage)) throw new SecurityException("Package mismatch");
            if ((user == 0 && denyOwner) || (user != 0 && denyTarget)) throw new SecurityException("Provider permission");
            if (user != 0) return localPresent ? local : null;
            client.thread = thread;
            Connection connection = (Connection) MainHook.invoke(this, "incProviderCountLocked",
                    client, owner, null, Binder.getCallingUid(), callerPackage, null, stable, false, 0L,
                    new ProcessList(), user);
            connection.waiting = owner.provider == null;
            return owner.newHolder(connection, false);
        }
        public Connection incProviderCountLocked(ProcessRecord process, Provider provider, IBinder token,
                int uid, String callerPackage, String tag, boolean stable, boolean update, long time,
                ProcessList processes, int expectedUser) {
            if (!provider.connections.isEmpty()) return provider.connections.get(0);
            Connection connection = new Connection(); connection.client = process; connection.expectedUser = expectedUser;
            provider.connections.add(connection); return connection;
        }
        public Holder getContentProviderImpl(AppThread thread, String authority, IBinder token, int uid,
                String callerPackage, String tag, boolean stable, int user) { return null; }
        public void publishContentProviders(AppThread thread, List<?> providers) {}
    }
    public static final class ApplicationInfo {
        public int uid;
        ApplicationInfo(int value) { uid = value; }
    }
    public static final class Computer {
        public final List<Integer> requests = new ArrayList<>();
        public final List<Integer> callerUids = new ArrayList<>();
        public ApplicationInfo owner = new ApplicationInfo(1027), local;
        public boolean denyTarget, denyOwner;
        public ApplicationInfo getApplicationInfo(String pkg, long flags, int user) {
            requests.add(user); callerUids.add(Binder.getCallingUid());
            if ((user == 0 && denyOwner) || (user != 0 && denyTarget)) throw new SecurityException("Package permission");
            return user == 0 ? owner : local;
        }
    }
    public static final class UserManager {
        private final UserManagerService service;
        UserManager(UserManagerService service) { this.service = service; }
        public boolean removeUser(int id) throws Throwable {
            return (Boolean) MainHook.invoke(service, "removeUser", id);
        }
    }
    public static final class UserManagerService {
        public final List<Integer> removed = new ArrayList<>();
        public boolean removeUser(int id) {
            if (Binder.getCallingUid() != 1000) throw new SecurityException("MANAGE_USERS");
            removed.add(id); return true;
        }
    }
    public static final class UserController {
        public final UserManagerService users = new UserManagerService();
        public int maxRunningUsers = 8;
        public final List<Integer> started = new ArrayList<>();
        public boolean fail;
        public Action duringStart;
        public void setInitialConfig(boolean userSwitchUiEnabled, int maxRunningUsers,
                boolean delayUserDataLocking, int backgroundUserScheduledStopTimeSecs) {
            this.maxRunningUsers = maxRunningUsers;
        }
        public void startProfiles() throws Throwable {
            for (int i = 900; i <= 910; i++) {
                if (i - 900 < maxRunningUsers - 1) started.add(i);
                else MainHook.invoke(users, "removeUser", i);
            }
            if (duringStart != null) { Action action = duringStart; duringStart = null; action.run(); }
            if (fail) throw new IllegalStateException("Startup failed");
        }
    }

    private static void bootTests() throws Throwable {
        Binder.setCallingUid(1000);
        UserController configured = new UserController();
        MainHook.invoke(configured, "setInitialConfig", true, 8, false, 0);
        check(configured.maxRunningUsers == ClonePolicy.LIMIT);
        UserController controller = new UserController();
        MainHook.invoke(controller, "startProfiles");
        check(controller.maxRunningUsers == 8);
        check(controller.started.equals(Arrays.asList(900, 901, 902, 903, 904, 905, 906)));
        check(controller.users.removed.isEmpty());
        check((Boolean) MainHook.invoke(controller.users, "removeUser", 910));
        check(controller.users.removed.equals(Arrays.asList(910)));
        check((Boolean) MainHook.invoke(new UserManager(controller.users), "removeUser", 908));
        check(controller.users.removed.equals(Arrays.asList(910, 908)));
        controller.users.removed.clear();
        controller.duringStart = () -> MainHook.invoke(controller, "startProfiles");
        MainHook.invoke(controller, "startProfiles");
        check(controller.users.removed.isEmpty());
        controller.fail = true;
        try { MainHook.invoke(controller, "startProfiles"); throw new AssertionError("Expected startup failure"); }
        catch (IllegalStateException expected) { checks++; }
        check((Boolean) MainHook.invoke(controller.users, "removeUser", 909));
        controller.fail = false; controller.users.removed.clear();
        // An explicit removal on another thread must not be blocked by boot's scoped suppression.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        controller.duringStart = () -> {
            Thread thread = new Thread(() -> {
                try { MainHook.invoke(controller.users, "removeUser", 910); }
                catch (Throwable error) { failure.set(error); }
            });
            thread.start(); thread.join();
        };
        MainHook.invoke(controller, "startProfiles");
        check(failure.get() == null && controller.users.removed.equals(Arrays.asList(910)));
        controller.users.removed.clear();
        clones.remove(910); // Same numeric ID now belongs to another user type.
        MainHook.invoke(controller, "startProfiles");
        check(controller.users.removed.equals(Arrays.asList(910)));
        clones.add(910);
        Binder.setCallingUid(12345);
        denied(() -> MainHook.invoke(controller.users, "removeUser", 900));
    }

    private static void providerTests() throws Throwable {
        Binder.setCallingUid(91010304);
        AppThread thread = new AppThread();
        ProviderHelper helper = new ProviderHelper();
        Holder holder = (Holder) MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 910, true);
        check(holder.provider == helper.owner.provider);
        check(helper.requests.equals(Arrays.asList(910, 0)));
        check(helper.callerUids.equals(Arrays.asList(91010304, 91010304)));
        helper = new ProviderHelper(); helper.localPresent = true;
        check(MainHook.invoke(helper, "getContentProvider", thread, "caller", "contacts", 910, true) == helper.local);
        check(helper.requests.equals(Arrays.asList(910)));
        for (int target : new int[]{0, 20, -1, 21473}) {
            helper = new ProviderHelper();
            MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", target, true);
            check(helper.requests.equals(Arrays.asList(target)));
        }
        Binder.setCallingUid(2010304); // A managed/secondary user must not get this fallback.
        helper = new ProviderHelper();
        check(MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 910, true) == null);
        check(helper.requests.equals(Arrays.asList(910)));
        Binder.setCallingUid(91010304);
        ProviderHelper denied = new ProviderHelper(); denied.denyTarget = true;
        denied(() -> MainHook.invoke(denied, "getContentProvider", thread, "caller", "settings", 910, true));
        check(denied.requests.equals(Arrays.asList(910)));
        ProviderHelper ownerDenied = new ProviderHelper(); ownerDenied.denyOwner = true;
        denied(() -> MainHook.invoke(ownerDenied, "getContentProvider", thread, "caller", "settings", 910, true));
        check(ownerDenied.requests.equals(Arrays.asList(910, 0)));
        ProviderHelper badPackage = new ProviderHelper();
        denied(() -> MainHook.invoke(badPackage, "getContentProvider", thread, "wrong", "settings", 910, true));
        check(badPackage.requests.equals(Arrays.asList(910)));

        for (boolean success : new boolean[]{true, false}) {
            thread.notifications.clear();
            helper = new ProviderHelper(); helper.owner.provider = null;
            // An existing owner request and two fallback requests share one pending connection.
            MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 0, true);
            MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 910, true);
            MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 909, true);
            MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 910, true);
            helper.owner.provider = success ? new Object() : null;
            MainHook.invoke(helper.owner, "onProviderPublishStatusLocked", success);
            check(new HashSet<>(thread.notifications).equals(new HashSet<>(Arrays.asList(
                    "0:" + success + ":" + success, "909:" + success + ":" + success,
                    "910:" + success + ":" + success))));
            check(thread.notifications.size() == 3);
            thread.notifications.clear();
            MainHook.invoke(helper.owner, "onProviderPublishStatusLocked", success);
            check(thread.notifications.isEmpty());
        }
        // A failed retry must not leak its request key into unrelated subsequent publications.
        helper = new ProviderHelper(); helper.owner.provider = null;
        MainHook.invoke(helper, "getContentProvider", thread, "caller", "settings", 0, true);
        MainHook.invoke(helper.owner, "onProviderPublishStatusLocked", false);
        check(thread.notifications.equals(Arrays.asList("0:false:false")));
    }

    private static void packageTests() throws Throwable {
        Binder.setCallingUid(1027);
        Computer computer = new Computer();
        check(MainHook.invoke(computer, "getApplicationInfo", "com.android.nfc", 268436480L, 910) == computer.owner);
        check(computer.requests.equals(Arrays.asList(910, 0)));
        check(computer.callerUids.equals(Arrays.asList(1027, 1027)));
        computer = new Computer(); computer.local = new ApplicationInfo(91001027);
        check(MainHook.invoke(computer, "getApplicationInfo", "com.android.nfc", 0L, 910) == computer.local);
        check(computer.requests.equals(Arrays.asList(910)));
        computer = new Computer(); computer.owner = new ApplicationInfo(10000);
        check(MainHook.invoke(computer, "getApplicationInfo", "other.package", 0L, 910) == null);
        computer = new Computer();
        check(MainHook.invoke(computer, "getApplicationInfo", "com.android.nfc", 0L, 20) == null);
        check(computer.requests.equals(Arrays.asList(20)));
        Binder.setCallingUid(91001027);
        computer = new Computer();
        check(MainHook.invoke(computer, "getApplicationInfo", "com.android.nfc", 0L, 910) == null);
        check(computer.requests.equals(Arrays.asList(910)));
        Binder.setCallingUid(1027);
        Computer denied = new Computer(); denied.denyTarget = true;
        denied(() -> MainHook.invoke(denied, "getApplicationInfo", "com.android.nfc", 0L, 910));
        check(denied.requests.equals(Arrays.asList(910)));
        Computer ownerDenied = new Computer(); ownerDenied.denyOwner = true;
        denied(() -> MainHook.invoke(ownerDenied, "getApplicationInfo", "com.android.nfc", 0L, 910));
        check(ownerDenied.requests.equals(Arrays.asList(910, 0)));
    }

    public static void main(String[] args) throws Throwable {
        TYPES.put("com.android.server.am.UserController", UserController.class);
        TYPES.put("android.os.UserManager", UserManager.class);
        TYPES.put("com.android.server.pm.UserManagerService", UserManagerService.class);
        TYPES.put("com.android.server.am.ContentProviderHelper", ProviderHelper.class);
        TYPES.put("com.android.server.am.ActivityManagerService", ProviderHelper.class);
        TYPES.put("android.app.IApplicationThread", AppThread.class);
        TYPES.put("android.os.IBinder", IBinder.class);
        TYPES.put("com.android.server.am.ContentProviderRecord", Provider.class);
        TYPES.put("com.android.server.am.ProcessRecord", ProcessRecord.class);
        TYPES.put("com.android.server.am.ProcessList", ProcessList.class);
        TYPES.put("com.android.server.pm.ComputerEngine", Computer.class);
        TYPES.put("com.android.server.pm.IPackageManagerBase", Computer.class);
        for (int id = 900; id <= 910; id++) clones.add(id);
        RuntimeFixes.install(null, clones::contains);
        bootTests(); providerTests(); packageTests();
        System.out.println("PASS: " + checks + " real-hook callback checks (JVM doubles; not Android runtime validation)");
    }
}
