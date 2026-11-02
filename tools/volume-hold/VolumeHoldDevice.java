package io.tvos.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
            if (now - started >= 4000 || count >= 40) {
                active = null;
                blocked = true;
                return 0;
            }
            if (now < due) return 0;
            count++;
            due = now + 50;
            return code;
        }

        synchronized void stop() { active = null; blocked = true; }
    }

    static int[] parse(String line) {
        Matcher match = EVENT.matcher(line);
        if (!match.find()) return null;
        return new int[] {Integer.parseInt(match.group(2), 16), Integer.parseInt(match.group(3), 16)};
    }

    static final class Injector {
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

        void press(int code) throws ReflectiveOperationException {
            long down = ((Long) uptime.invoke(null)).longValue();
            try {
                send(code, 0, down);
            } finally {
                // Also attempt release if a DOWN call failed after reaching the system.
                send(code, 1, down);
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
        final Session session = new Session();
        final Injector injector = new Injector(); // Fail before opening input on unsupported firmware.
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
                        System.out.println("RAW " + match.group(1) + " " + parsed[0] + " " + parsed[1]);
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
            System.out.println("READY device helper: delay=400ms interval=50ms; max=4s/40 steps; heartbeat=3s");
            while (!session.stopped && !session.expired(now())) {
                int code = session.hold.take(now());
                if (code != 0 && !session.stopped && !session.expired(now())) {
                    long start = now();
                    injector.press(code);
                    System.out.println("EXTRA " + code + " inject=" + (now() - start) + "ms");
                }
                Thread.sleep(5);
            }
        } finally {
            session.stop();
            input.destroy();
            System.out.println("STOPPED device helper");
        }
    }
}
