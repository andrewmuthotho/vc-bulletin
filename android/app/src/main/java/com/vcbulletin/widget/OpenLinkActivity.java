package com.vcbulletin.widget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * An invisible go-between. When you tap a headline, the widget hands the
 * article's address to this screen, which immediately passes it to your
 * browser and closes. (Newer Android won't let a widget hand an address
 * straight to "any app that can open links", so it goes via our own app.)
 */
public class OpenLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri link = getIntent().getData();
        if (link == null) link = Uri.parse(BulletinData.PAGE);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, link)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // No browser found: nothing sensible to do, just close.
        }
        finish();
    }
}
