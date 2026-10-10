package com.huawei.deveco.assistant.automation;

import android.os.Bundle;
import android.widget.Toast;
import com.huawei.deveco.assistant.activity.StartUpActivity;

/** Shows the mod marker once when the launcher Activity first starts in this process. */
public final class ModStartUpActivity extends StartUpActivity {
    private static boolean markerShown;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!markerShown) {
            markerShown = true;
            Toast.makeText(this, "Mod by egor20002012", Toast.LENGTH_SHORT).show();
        }
    }
}
