package systems.sieber.fsclock;

import java.io.File;

/**
 * Plain-JVM check of {@link CarTypeFile}'s pure mappings — the controller's car key
 * ({@code familyOf}), its family word ({@code familyOfWord}), the store's car id
 * ({@code familyOfStoreCar}) and the {@code get_car_type} answer body ({@code storeCarOfRpcBody}).
 * No Android, no Gradle:
 *
 * <pre>
 *   AJ=$ANDROID_HOME/platforms/android-36/android.jar   # CarTypeFile logs through android.util.Log
 *   javac -cp "$AJ" -d /tmp/ctf source/app/src/main/java/systems/sieber/fsclock/CarTypeFile.java tools/CarTypeFileCheck.java
 *   (cd /tmp/ctf && java -cp ".;$AJ" systems.sieber.fsclock.CarTypeFileCheck)   # use the JDK's own java, ';' on Windows
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

        // The controller's car_family.txt word (build 101+): the family is its interpretation, ours only maps it.
        expectWord("leopard", CarTypeFile.Family.LEOPARD);
        expectWord(" leopard ", CarTypeFile.Family.LEOPARD);   // trimmed
        expectWord("denza", CarTypeFile.Family.DENZA);
        expectWord("ti7", CarTypeFile.Family.LEOPARD);        // owner 2026-09-14: Ti7 is the Leopard family
        expectWord("tank500", CarTypeFile.Family.UNKNOWN);    // the controller's word list stays narrow
        expectWord("Leopard", CarTypeFile.Family.UNKNOWN);    // case-sensitive like the key
        expectWord("", CarTypeFile.Family.UNKNOWN);
        expectWord(null, CarTypeFile.Family.UNKNOWN);

        // The store's car ids (ذبذبة ستور's picker, served by RPC get_car_type) — a different vocabulary.
        // The owner's complete table, 2026-09-14.
        expectStore("leopard", CarTypeFile.Family.LEOPARD);
        expectStore(" leopard ", CarTypeFile.Family.LEOPARD);   // trimmed
        expectStore("ti7", CarTypeFile.Family.LEOPARD);         // same family as Leopard
        expectStore("denza", CarTypeFile.Family.DENZA);
        expectStore("icar_03t", CarTypeFile.Family.ICAR03T);
        expectStore("tank500", CarTypeFile.Family.GWM);
        expectStore("lynk_and_co", CarTypeFile.Family.LYNKCO);
        expectStore("jetour_g700", CarTypeFile.Family.JETOUR);
        expectStore("jetour_t1", CarTypeFile.Family.JETOUR);    // every jetour* id is the Jetour mode
        expectStore("jetour_t2", CarTypeFile.Family.JETOUR);
        expectStore("jetour_idm_03", CarTypeFile.Family.JETOUR);
        expectStore("jetour_x_future", CarTypeFile.Family.JETOUR);
        expectStore("dong_feng", CarTypeFile.Family.OTHERS);    // the plain drawn screen, no question
        expectStore("iacaur", CarTypeFile.Family.OTHERS);
        expectStore("china_212", CarTypeFile.Family.OTHERS);
        expectStore("haval_v7", CarTypeFile.Family.OTHERS);
        // Anything the store does not write keeps asking the driver.
        expectStore("Leopard", CarTypeFile.Family.UNKNOWN);      // case-sensitive like the others
        expectStore("TI7", CarTypeFile.Family.UNKNOWN);
        expectStore("Jetour_G700", CarTypeFile.Family.UNKNOWN);
        expectStore("l8_2026_ui6", CarTypeFile.Family.UNKNOWN);  // a controller key is not a store id
        expectStore("lynkco", CarTypeFile.Family.UNKNOWN);
        expectStore("tank", CarTypeFile.Family.UNKNOWN);
        expectStore("", CarTypeFile.Family.UNKNOWN);
        expectStore("   ", CarTypeFile.Family.UNKNOWN);
        expectStore(null, CarTypeFile.Family.UNKNOWN);

        // The RPC body as PostgREST writes a scalar text answer: one JSON string, or bare null.
        expectBody("\"leopard\"", "leopard");
        expectBody(" \"tank500\"\n", "tank500");
        expectBody("null", null);
        expectBody("", null);
        expectBody(null, null);
        expectBody("leopard", null);            // unquoted is not the RPC's shape
        expectBody("\"\"", null);
        expectBody("\"a\\\"b\"", null);         // an escape is not a car id
        expectBody("[\"leopard\"]", null);      // an array is some other RPC
        expectBody("{\"car\":\"leopard\"}", null);

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
        System.out.println("OK: CarTypeFile mappings (controller key, family word, store car id, RPC body) match the spec");
    }

    private static void expect(String key, CarTypeFile.Family want) {
        CarTypeFile.Family got = CarTypeFile.familyOf(key);
        if(got != want) fail("familyOf(" + (key == null ? "null" : "\"" + key + "\"") + ")", want.name(), got.name());
    }

    private static void expectWord(String word, CarTypeFile.Family want) {
        CarTypeFile.Family got = CarTypeFile.familyOfWord(word);
        if(got != want) fail("familyOfWord(" + (word == null ? "null" : "\"" + word + "\"") + ")", want.name(), got.name());
    }

    private static void expectStore(String carId, CarTypeFile.Family want) {
        CarTypeFile.Family got = CarTypeFile.familyOfStoreCar(carId);
        if(got != want) fail("familyOfStoreCar(" + (carId == null ? "null" : "\"" + carId + "\"") + ")", want.name(), got.name());
    }

    private static void expectBody(String body, String want) {
        String got = CarTypeFile.storeCarOfRpcBody(body);
        boolean ok = want == null ? got == null : want.equals(got);
        if(!ok) fail("storeCarOfRpcBody(" + (body == null ? "null" : "\"" + body + "\"") + ")",
                String.valueOf(want), String.valueOf(got));
    }

    private static void fail(String what, String want, String got) {
        failures++;
        System.out.println("MISMATCH " + what + ": expected " + want + ", got " + got);
    }
}
