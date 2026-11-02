package io.tvos.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in shell experiment. No Android app/service installation or setting changes. */
public final class VolumeHoldDevice {
    private VolumeHoldDevice() { }
    private static final Pattern EVENT = Pattern.compile(
            "(/dev/input/event[0-9]+):\\s+0001\\s+(0072|0073)\\s+(0000000[012])\\s*$");

    static final class Hold {
        private final Set<String> pressed = new HashSet<String>();
        private String active;
        private int code, count;
        private long started, due;
        private boolean blocked;

        synchronized void event(String device, int scan, int value, long now) {
            notifyAll();
            String key = device + ":" + scan;
            if (value == 0) {
                pressed.remove(key);
                if (key.equals(active)) active = null;
                if (pressed.isEmpty()) blocked = false;
            } else if (value == 2 || pressed.contains(key)) {
                active = null;
                blocked = true;
            } else {
                pressed.add(key);
                if (blocked || pressed.size() != 1) {
                    active = null;
                    blocked = true;
                    return;
                }
                active = key;
                code = scan == 115 ? 24 : 25;
                started = now;
                due = now + 400;
                count = 0;
            }
        }

        synchronized int take(long now) {
            if (active == null) return 0;
            if (now - started >= 6000 || count >= 100) {
                active = null;
                blocked = true;
                return 0;
            }
            if (now < due) return 0;
            count++;
            due = now + 50;
            return code;
        }

        synchronized void stop() { active = null; blocked = true; notifyAll(); }
        synchronized void pause(long now) throws InterruptedException {
            wait(active == null ? 250 : Math.max(1, Math.min(250, due - now)));
        }
    }

    static int[] parse(String line) {
        Matcher match = EVENT.matcher(line);
        if (!match.find()) return null;
        return new int[] {Integer.parseInt(match.group(2), 16), Integer.parseInt(match.group(3), 16)};
    }

    interface Stepper { void press(int code) throws ReflectiveOperationException; }

    static final class Injector implements Stepper {
        private final Object manager;
        private final Method inject, uptime;
        private final Constructor<?> key;

        Injector() throws ReflectiveOperationException {
            Class<?> managerClass;
            try {
                managerClass = Class.forName("android.hardware.input.InputManagerGlobal");
            } catch (ClassNotFoundException legacy) {
                managerClass = Class.forName("android.hardware.input.InputManager");
            }
            manager = managerClass.getMethod("getInstance").invoke(null);
            if (manager == null) throw new IllegalStateException("Input manager unavailable");
            Class<?> event = Class.forName("android.view.InputEvent");
            inject = managerClass.getMethod("injectInputEvent", event, int.class);
            key = Class.forName("android.view.KeyEvent").getConstructor(long.class, long.class,
                    int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
            uptime = Class.forName("android.os.SystemClock").getMethod("uptimeMillis");
        }

        private void send(int code, int action, long down) throws ReflectiveOperationException {
            long now = ((Long) uptime.invoke(null)).longValue();
            Object event = key.newInstance(down, now, action, code, 0, 0, -1, 0, 0, 257);
            // WAIT_FOR_FINISH: serialize completed events instead of accumulating async input.
            Object result = inject.invoke(manager, event, 2);
            if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("Input injection rejected");
        }

        @Override public void press(int code) throws ReflectiveOperationException {
            long down = ((Long) uptime.invoke(null)).longValue();
            try {
                send(code, 0, down);
            } finally {
                // Also attempt release if a DOWN call failed after reaching the system.
                send(code, 1, down);
            }
        }
    }

    /** Experimental direct audio path: bypasses InputDispatcher, not system audio policy. */
    static final class AudioStepper implements Stepper {
        private final Object service;
        private final Method method, uptime;
        private final Constructor<?> key;

        AudioStepper() throws ReflectiveOperationException {
            Class<?> binder = Class.forName("android.os.IBinder");
            Object remote = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "audio");
            service = Class.forName("android.media.IAudioService$Stub")
                    .getMethod("asInterface", binder).invoke(null, remote);
            if (service == null) throw new IllegalStateException("Audio service unavailable");
            Class<?> api = Class.forName("android.media.IAudioService");
            Method selected;
            Constructor<?> eventConstructor = null;
            Method clock = null;
            Class<?> event = Class.forName("android.view.KeyEvent");
            try {
                selected = api.getMethod("handleVolumeKey", event, boolean.class, String.class, String.class);
            } catch (NoSuchMethodException legacy) {
                // Android 9 exposes the suggested-stream method instead of handleVolumeKey.
                selected = api.getMethod("adjustSuggestedStreamVolume", int.class, int.class,
                        int.class, String.class, String.class);
            }
            if ("handleVolumeKey".equals(selected.getName())) {
                eventConstructor = event.getConstructor(long.class, long.class, int.class, int.class,
                        int.class, int.class, int.class, int.class, int.class, int.class);
                clock = Class.forName("android.os.SystemClock").getMethod("uptimeMillis");
            }
            method = selected;
            key = eventConstructor;
            uptime = clock;
            System.out.println("AUDIO method=" + method.getName() + " (submission time, not playback acknowledgement)");
        }

        private void send(int code, int action, long down) throws ReflectiveOperationException {
            long time = ((Long) uptime.invoke(null)).longValue();
            Object event = key.newInstance(down, time, action, code, 0, 0, -1, 0, 0, 257);
            method.invoke(service, event, true, "com.android.shell", "tvOS-volume-test");
        }

        @Override public void press(int code) throws ReflectiveOperationException {
            if (key == null) {
                // USE_DEFAULT_STREAM_TYPE; SHOW_UI | PLAY_SOUND | FROM_KEY, as system volume keys.
                method.invoke(service, code == 24 ? 1 : -1, Integer.MIN_VALUE,
                        1 | 4 | 4096, "com.android.shell", "tvOS-volume-test");
            } else {
                long down = ((Long) uptime.invoke(null)).longValue();
                try {
                    send(code, 0, down);
                } finally {
                    send(code, 1, down);
                }
            }
        }
    }

    static final class Session {
        final Hold hold = new Hold();
        volatile boolean stopped;
        volatile long heartbeat = now();
        void stop() { stopped = true; hold.stop(); }
        boolean expired(long time) { return time - heartbeat >= 3000; }
    }

    private static long now() { return System.nanoTime() / 1000000; }

    public static void main(String[] args) throws Exception {
        // A process death releases this lock. Never unlink the lock inode while a peer may own it.
        try (RandomAccessFile owner = new RandomAccessFile("/data/local/tmp/io.tvos-volume.lock", "rw");
             FileChannel channel = owner.getChannel(); FileLock lease = channel.tryLock()) {
            if (lease == null) throw new IllegalStateException("Volume helper already running");
            run(args);
        }
    }

    private static void run(String[] args) throws Exception {
        final Session session = new Session();
        if (args.length > 2 || (args.length > 0 && !"audio".equals(args[0]))
                || (args.length == 2 && !"quiet".equals(args[1]))) {
            throw new IllegalArgumentException("Expected optional audio mode");
        }
        final boolean audio = args.length > 0;
        final boolean quiet = args.length == 2;
        final Stepper injector = audio ? new AudioStepper() : new Injector();
        // Fail before opening input on unsupported firmware.
        final Process input = new ProcessBuilder("/system/bin/getevent", "-t")
                .redirectErrorStream(true).start();
        Thread controls = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                    String line;
                    while (!session.stopped && (line = reader.readLine()) != null) {
                        if ("PING".equals(line)) session.heartbeat = now();
                        else if ("STOP".equals(line)) break;
                    }
                } catch (Exception error) {
                    System.out.println("CONTROL ERROR " + error.getClass().getSimpleName());
                } finally { session.stop(); }
            }
        }, "volume-control");
        Thread events = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(input.getInputStream(), "UTF-8"));
                    String line;
                    while (!session.stopped && (line = reader.readLine()) != null) {
                        int[] parsed = parse(line);
                        if (parsed == null) {
                            if (line.contains("Permission denied") || line.contains("could not")) {
                                System.out.println("INPUT ERROR " + line);
                                break;
                            }
                            continue;
                        }
                        Matcher match = EVENT.matcher(line);
                        if (!match.find()) continue;
                        session.hold.event(match.group(1), parsed[0], parsed[1], now());
                        if (!quiet) System.out.println("RAW " + match.group(1) + " " + parsed[0] + " " + parsed[1]);
                    }
                } catch (Exception error) {
                    System.out.println("INPUT ERROR " + error.getClass().getSimpleName());
                } finally { session.stop(); }
            }
        }, "volume-input");
        controls.setDaemon(true);
        events.setDaemon(true);
        try {
            controls.start();
            events.start();
            System.out.println("READY device helper mode=" + (audio ? "audio" : "input") + ": delay=400ms interval=50ms; max=6s/100 steps; heartbeat=3s");
            while (!session.stopped && !session.expired(now())) {
                int code = session.hold.take(now());
                if (code != 0 && !session.stopped && !session.expired(now())) {
                    long start = now();
                    injector.press(code);
                    if (!quiet) System.out.println("EXTRA " + code + (audio ? " audio-submit=" : " inject=") + (now() - start) + "ms");
                }
                if (!session.stopped && !session.expired(now())) session.hold.pause(now());
            }
        } finally {
            session.stop();
            input.destroy();
            System.out.println("STOPPED device helper");
        }
    }
}
