package com.huawei.deveco.assistant.automation;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Opens the project's usage instructions from the home-screen badge. */
public final class ModLinkView extends TextView implements View.OnClickListener {
    private static final String PROJECT_URL = "https://github.com/RandGor/deveco-assistant-mod";

    public ModLinkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnClickListener(this);
        setContentDescription("Mod by egor20002012. Open project instructions");
    }

    @Override
    public void onClick(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(getContext(), "No browser available", Toast.LENGTH_SHORT).show();
        }
    }
}
