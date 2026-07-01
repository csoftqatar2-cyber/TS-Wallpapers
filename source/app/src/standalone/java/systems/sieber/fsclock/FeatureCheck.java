package systems.sieber.fsclock;

import android.content.Context;

public class FeatureCheck extends BaseFeatureCheck {

    FeatureCheck(Context c) {
        super(c);
    }

    @Override
    void init() {
        super.init();

        // standalone resell build: all settings are unlocked, no in-app purchase
        unlockedSettings = true;
        isReady = true;
        if(listener != null) {
            listener.featureCheckReady(true);
        }
    }

}
