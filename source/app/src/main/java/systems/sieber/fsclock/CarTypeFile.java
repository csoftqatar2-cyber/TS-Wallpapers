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
 * <p><b>The store is the second publisher of the same choice</b> (2026-09-14). Most cars carry
 * ذبذبة ستور and no controller, so neither file exists there — but the store reports its own car
 * picker's answer on every check-in, and the backend hands it back through the read-only RPC
 * {@code get_car_type(device_hw_id)} ({@link WallpaperRepo#fetchStoreCarType}). Its car ids are a
 * different vocabulary from the controller's keys ({@code leopard}, {@code tank500},
 * {@code lynk_and_co}, …), so they get their own table, {@link #familyOfStoreCar}. The network
 * call itself lives in {@code WallpaperRepo}; this class only maps and parses, so the whole
 * mapping stays checkable on a plain JVM.
 *
 * <p>No Android imports, so the mapping can be checked on a plain JVM
 * ({@code tools/CarTypeFileCheck.java}).
 */
final class CarTypeFile {

    /**
     * The operating-mode family a key names. Only what this app needs; never the car itself.
     * LEOPARD is the BYD Leopard family, which on a two-screen unit means Leopard on the driver
     * screen and FSE on the passenger instance — that split is the caller's
     * ({@code ModeConfirmActivity.controllerMode}), not this table's.
     */
    enum Family { LEOPARD, DENZA, ICAR03T, GWM, LYNKCO, JETOUR, OTHERS, UNKNOWN }

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

    /**
     * The family the controller's files name for this unit ({@code car_family.txt} first, then
     * {@code car.txt}), or UNKNOWN. Never throws, never touches the network — the store's answer
     * is a separate, asynchronous source ({@code ModeConfirmActivity.askStoreAsync}).
     */
    static Family read() {
        Family fromFamilyFile = familyOfWord(readFirstLine(FAMILY_PATH, "car_family.txt"));
        if(fromFamilyFile != Family.UNKNOWN) return fromFamilyFile;
        return familyOf(readKey());
    }

    /**
     * The controller's family word → our family. {@code ti7} is the Leopard family (owner's
     * decision 2026-09-14: same product, FSE on the passenger instance included); anything else
     * stays UNKNOWN (asked).
     */
    static Family familyOfWord(String word) {
        if(word == null) return Family.UNKNOWN;
        word = word.trim();
        if(word.equals("leopard")) return Family.LEOPARD;
        if(word.equals("ti7")) return Family.LEOPARD;
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

    /**
     * The store's car id → our family. The vocabulary is ذبذبة ستور's car picker
     * ({@code store_installs.car}), as served by RPC {@code get_car_type}:
     *
     * The complete table is the owner's (2026-09-14):
     * <ul>
     *   <li>{@code leopard}, {@code ti7} → {@link Family#LEOPARD} (one family: hand-off on the
     *       driver screen, FSE on the passenger instance)</li>
     *   <li>{@code denza} → {@link Family#DENZA}</li>
     *   <li>{@code icar_03t} → {@link Family#ICAR03T}</li>
     *   <li>{@code tank500} → {@link Family#GWM}</li>
     *   <li>{@code lynk_and_co} → {@link Family#LYNKCO}</li>
     *   <li>any id starting with {@code jetour} ({@code jetour_g700}, {@code jetour_t1},
     *       {@code jetour_t2}, {@code jetour_idm_03}, …) → {@link Family#JETOUR}</li>
     *   <li>{@code dong_feng}, {@code iacaur}, {@code china_212}, {@code haval_v7} →
     *       {@link Family#OTHERS}: the plain drawn screen ("Others"), applied without a
     *       question</li>
     *   <li>anything else — null, empty, an id the store does not write → {@link Family#UNKNOWN}:
     *       ask the driver</li>
     * </ul>
     * Exact, case-sensitive matches, like {@link #familyOf}: an id the store does not write is
     * not the store's.
     */
    static Family familyOfStoreCar(String carId) {
        if(carId == null) return Family.UNKNOWN;
        carId = carId.trim();
        if(carId.isEmpty() || carId.length() > MAX_KEY_CHARS) return Family.UNKNOWN;
        if(carId.startsWith("jetour")) return Family.JETOUR;
        switch(carId) {
            case "leopard":
            case "ti7":         return Family.LEOPARD;
            case "denza":       return Family.DENZA;
            case "icar_03t":    return Family.ICAR03T;
            case "tank500":     return Family.GWM;
            case "lynk_and_co": return Family.LYNKCO;
            case "dong_feng":
            case "iacaur":
            case "china_212":
            case "haval_v7":    return Family.OTHERS;
            default:            return Family.UNKNOWN;
        }
    }

    /**
     * The car id inside {@code get_car_type}'s answer body, or null. PostgREST returns a scalar
     * text function as one JSON string — {@code "leopard"} — and SQL NULL as the bare word
     * {@code null}. Hand-parsed rather than through org.json so the plain-JVM check can run it:
     * a quoted token is unquoted (the store's ids carry no escapes, and an escape makes the answer
     * not-a-car-id, which is the safe reading), and anything else is null.
     */
    static String storeCarOfRpcBody(String body) {
        if(body == null) return null;
        body = body.trim();
        if(body.length() < 2 || body.charAt(0) != '"' || body.charAt(body.length() - 1) != '"') return null;
        String car = body.substring(1, body.length() - 1).trim();
        if(car.isEmpty() || car.indexOf('\\') >= 0 || car.indexOf('"') >= 0) return null;
        return car;
    }

    /** Log.e survives release stripping; the try/catch is for the plain-JVM check tool, where android.jar is stubs that throw. */
    private static void log(String msg) {
        try { android.util.Log.e(TAG, msg); } catch(Throwable ignored) { }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
