package systems.sieber.fsclock;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The car type as already chosen on this head unit by لوحة تحكم ذبذبة (the controller).
 *
 * <p>The controller publishes it as a one-line, world-readable file
 * ({@link #PATH}, e.g. {@code l8_2026_ui6}, {@code denza_b5}, {@code ti7}). The owner's rule
 * (2026-09-10): an app must not ask the driver to choose the car when it is already chosen. So
 * this class exists to answer the "which car is this?" question from that file, and for nothing
 * else.
 *
 * <p><b>Read-only, by contract with the controller team.</b> This file is the controller's; this
 * app never writes, creates, truncates or deletes it, whatever it finds there. There is no
 * writing code in this class on purpose.
 *
 * <p><b>The mapping is deliberately narrow.</b> The key is an opaque token whose only truth is
 * the controller's own {@code D.cars} list; that list changes without this app knowing, so a
 * table here that tried to name every car would be wrong the day a car is added or renamed. This
 * app does not need the car — it needs the <i>family</i>, which is what its operating mode is:
 * every {@code l<digit>…} key is a Leopard-family unit (Leopard 5 / 7 / 8, any year, any UI), and
 * every {@code denza…} key is a Denza. Nothing else is interpreted: an unknown key, an empty
 * file, a missing file, a file too long to be a key, or any error at all is {@link Family#UNKNOWN},
 * which the callers treat exactly as today — they ask the driver.
 *
 * <p>No Android imports, so the mapping can be checked on a plain JVM
 * ({@code tools/CarTypeFileCheck.java}).
 */
final class CarTypeFile {

    /** The operating-mode family a key names. Only what this app needs; never the car itself. */
    enum Family { LEOPARD, DENZA, UNKNOWN }

    /** Written by the controller; read by everyone; written by nobody else. */
    static final String PATH = "/data/local/tmp/thabd/records/car.txt";
    /**
     * Written by the controller since its build 101 (2.12.6) next to the key: one lower-case word
     * naming the FAMILY ({@code leopard} / {@code denza} / {@code ti7}). It is the controller's own
     * interpretation of its key, so when it exists it wins over our prefix mapping below — that
     * mapping only remains for cars whose controller predates the family file.
     */
    static final String FAMILY_PATH = "/data/local/tmp/thabd/records/car_family.txt";

    /** A real key is a short token. Anything longer is not a key and is not interpreted. */
    static final int MAX_KEY_CHARS = 64;
    private static final String TAG = "CarTypeFile";

    private CarTypeFile() { }

    /** The family the controller's file names for this unit. Never throws. */
    static Family read() {
        Family fromFamilyFile = familyOfWord(readFirstLine(FAMILY_PATH, "car_family.txt"));
        if(fromFamilyFile != Family.UNKNOWN) return fromFamilyFile;
        return familyOf(readKey());
    }

    /** The controller's family word → our family. {@code ti7} and anything else stay UNKNOWN (asked). */
    static Family familyOfWord(String word) {
        if(word == null) return Family.UNKNOWN;
        word = word.trim();
        if(word.equals("leopard")) return Family.LEOPARD;
        if(word.equals("denza")) return Family.DENZA;
        return Family.UNKNOWN;
    }

    /**
     * The trimmed first line of the file, or {@code ""} when there is no usable key: file missing,
     * unreadable, empty, longer than {@link #MAX_KEY_CHARS}, or any exception. Never throws.
     */
    static String readKey() {
        return readFirstLine(PATH, "car.txt");
    }

    private static String readFirstLine(String path, String label) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(path), StandardCharsets.UTF_8), 256);
            String line = in.readLine();
            if(line == null) { log(label + ": empty"); return ""; }
            line = line.trim();
            if(line.isEmpty() || line.length() > MAX_KEY_CHARS) { log(label + ": unusable"); return ""; }
            // Log.e on purpose: it survives the release build's log stripping, and this one line is
            // the only way to tell "file absent" from "SELinux refused us" on a customer's car.
            log(label + ": " + line);
            return line;
        } catch(Throwable t) {
            log(label + ": unreadable: " + t);
            return "";
        } finally {
            if(in != null) {
                try { in.close(); } catch(Throwable ignored) { }
            }
        }
    }

    /**
     * Pure mapping, kept apart from the file read so it can be tested without a device.
     *
     * <ul>
     *   <li>{@code l} followed by a digit ({@code l5_ui5}, {@code l7_ui6}, {@code l8_2026_ui6},
     *       bare {@code l8}) → {@link Family#LEOPARD}</li>
     *   <li>starts with {@code denza} ({@code denza_b5}) → {@link Family#DENZA}</li>
     *   <li>anything else, including null / empty / too long → {@link Family#UNKNOWN}</li>
     * </ul>
     * Case-sensitive on purpose: the controller writes lower-case tokens, and a token that does
     * not look exactly like one of the controller's is not one of the controller's.
     */
    static Family familyOf(String key) {
        if(key == null) return Family.UNKNOWN;
        key = key.trim();
        if(key.isEmpty() || key.length() > MAX_KEY_CHARS) return Family.UNKNOWN;
        if(key.length() >= 2 && key.charAt(0) == 'l' && isAsciiDigit(key.charAt(1))) {
            return Family.LEOPARD;
        }
        if(key.startsWith("denza")) return Family.DENZA;
        return Family.UNKNOWN;
    }

    /** Log.e survives release stripping; the try/catch is for the plain-JVM check tool, where android.jar is stubs that throw. */
    private static void log(String msg) {
        try { android.util.Log.e(TAG, msg); } catch(Throwable ignored) { }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
