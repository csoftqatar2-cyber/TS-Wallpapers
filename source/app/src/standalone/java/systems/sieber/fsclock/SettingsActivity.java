package systems.sieber.fsclock;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

public class SettingsActivity extends BaseSettingsActivity {

    SettingsActivity me;
    FeatureCheck mFc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        me = this;

        // init manual unlock
        mButtonUnlockSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openUnlockInputBox();
            }
        });
        loadPurchases();
    }

    private void loadPurchases() {
        // disable settings by default
        mLinearLayoutPurchaseContainer.setVisibility(View.VISIBLE);
        enableDisableAllSettings(false);

        // load feature state
        mFc = new FeatureCheck(this);
        mFc.setFeatureCheckReadyListener(new FeatureCheck.featureCheckReadyListener() {
            @Override
            public void featureCheckReady(boolean fetchSuccess) {
                if (mFc.unlockedSettings) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLinearLayoutPurchaseContainer.setVisibility(View.GONE);
                            enableDisableAllSettings(true);
                        }
                    });
                }
            }
        });
        mFc.init();
    }

    private void openUnlockInputBox() {
        final Dialog ad = new Dialog(this);
        ad.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ad.setContentView(R.layout.dialog_inputbox);

        // Make dialog window background transparent so our black bg shows properly
        if (ad.getWindow() != null) {
            ad.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            ad.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        ad.findViewById(R.id.buttonBuyCode).setVisibility(View.GONE);
        ad.findViewById(R.id.buttonOK).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = ((EditText) ad.findViewById(R.id.editTextInputBox)).getText().toString().trim();
                ad.dismiss();
                validateLocalCode(code);
            }
        });
        ad.show();
    }

    /**
     * Validates the activation code locally.
     * Valid codes: any 6-digit code starting with "7078"
     */
    private void validateLocalCode(String code) {
        if (code != null && code.length() == 6 && code.startsWith("7078")) {
            // Valid code – unlock settings permanently
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mFc.unlockPurchase("settings");
                    loadPurchases();
                }
            });
        } else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    infoDialog(getString(R.string.activation_failed), getString(R.string.invalid_code));
                }
            });
        }
    }

}
