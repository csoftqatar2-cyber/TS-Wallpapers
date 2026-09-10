package systems.sieber.fsclock;

import java.io.File;

/**
 * Plain-JVM check of {@link CarTypeFile#familyOf} — the mapping from the controller's car key to
 * the operating-mode family. No Android, no Gradle:
 *
 * <pre>
 *   javac -d /tmp/ctf source/app/src/main/java/systems/sieber/fsclock/CarTypeFile.java tools/CarTypeFileCheck.java
 *   java -cp /tmp/ctf systems.sieber.fsclock.CarTypeFileCheck
 * </pre>
 *
 * Exits non-zero on the first mismatch. Lives in the app's package because the class under test
 * is package-private, like everything else in it.
 */
public class CarTypeFileCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        // The controller's D.cars keys as published 2026-09-10 — the Leopard family.
        expect("l5_ui5", CarTypeFile.Family.LEOPARD);
        expect("l5_2025_ui6", CarTypeFile.Family.LEOPARD);
        expect("l7_ui6", CarTypeFile.Family.LEOPARD);
        expect("l8_2025_ui5", CarTypeFile.Family.LEOPARD);
        expect("l8_2026_ui6", CarTypeFile.Family.LEOPARD);
        expect("l8", CarTypeFile.Family.LEOPARD);
        expect("  l8_2026_ui6  ", CarTypeFile.Family.LEOPARD);   // trimmed
        expect("l9_anything_new", CarTypeFile.Family.LEOPARD);    // a new Leopard needs no code change

        // Denza.
        expect("denza_b5", CarTypeFile.Family.DENZA);
        expect("denza", CarTypeFile.Family.DENZA);
        expect("denza_z9gt_2027", CarTypeFile.Family.DENZA);

        // Everything else keeps asking the driver.
        expect("ti7", CarTypeFile.Family.UNKNOWN);
        expect("lynkco", CarTypeFile.Family.UNKNOWN);      // 'l' + letter is not a Leopard key
        expect("l", CarTypeFile.Family.UNKNOWN);
        expect("l_8", CarTypeFile.Family.UNKNOWN);
        expect("L8", CarTypeFile.Family.UNKNOWN);          // case-sensitive on purpose
        expect("DENZA_B5", CarTypeFile.Family.UNKNOWN);
        expect("leopard", CarTypeFile.Family.UNKNOWN);
        expect("", CarTypeFile.Family.UNKNOWN);
        expect("   ", CarTypeFile.Family.UNKNOWN);
        expect(null, CarTypeFile.Family.UNKNOWN);
        expect("8l", CarTypeFile.Family.UNKNOWN);
        StringBuilder tooLong = new StringBuilder("l8");
        while(tooLong.length() <= CarTypeFile.MAX_KEY_CHARS) tooLong.append('x');
        expect(tooLong.toString(), CarTypeFile.Family.UNKNOWN);   // longer than a key can be

        // The file path never exists on a build machine: missing file must be UNKNOWN, no throw.
        if(!new File(CarTypeFile.PATH).exists()) {
            String key = CarTypeFile.readKey();
            if(!"".equals(key)) fail("readKey() on a missing file", "\"\"", "\"" + key + "\"");
            CarTypeFile.Family f = CarTypeFile.read();
            if(f != CarTypeFile.Family.UNKNOWN) fail("read() on a missing file", "UNKNOWN", f.name());
        }

        if(failures > 0) {
            System.out.println("FAILED: " + failures + " mismatch(es)");
            System.exit(1);
        }
        System.out.println("OK: CarTypeFile.familyOf mapping matches the spec");
    }

    private static void expect(String key, CarTypeFile.Family want) {
        CarTypeFile.Family got = CarTypeFile.familyOf(key);
        if(got != want) fail("familyOf(" + (key == null ? "null" : "\"" + key + "\"") + ")", want.name(), got.name());
    }

    private static void fail(String what, String want, String got) {
        failures++;
        System.out.println("MISMATCH " + what + ": expected " + want + ", got " + got);
    }
}
