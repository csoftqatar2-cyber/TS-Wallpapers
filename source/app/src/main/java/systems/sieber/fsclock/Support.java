package systems.sieber.fsclock;

/**
 * The shop's contact details, as shown on the activation overlay and on the blocked screen.
 *
 * Deliberately the same number and the same wa.me link the Store app uses on its own
 * activation screen: a customer whose car is blocked will be looking at both programs, and
 * two different numbers would read as two different companies.
 *
 * There is no "open the chat on this car" helper here. Head units do not have WhatsApp
 * installed, so that button failed on nearly every car it could appear on; the QR built from
 * {@link #WHATSAPP_URL} is the real path, because the device that can act is the customer's
 * own phone.
 */
public class Support {

    /** Shown to the user, human formatting. Kept in sync with @string/support_number. */
    public static final String PHONE_DISPLAY = "+974 5515 8880";

    /** wa.me wants the number bare: country code, no '+', no spaces. */
    private static final String PHONE_E164 = "97455158880";

    /** What the QR encodes. */
    public static final String WHATSAPP_URL = "https://wa.me/" + PHONE_E164;
}
