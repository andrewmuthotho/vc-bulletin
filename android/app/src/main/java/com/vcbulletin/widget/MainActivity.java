package com.vcbulletin.widget;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The app screen. Its main job is to explain how to add the widget. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(64, 64, 64, 64);
        layout.setBackgroundColor(Color.parseColor("#141412"));

        TextView title = new TextView(this);
        title.setText("VC Bulletin");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#ECEBE6"));

        TextView help = new TextView(this);
        help.setText("To add the widget: long-press your home screen → Widgets → VC Bulletin, "
                + "and drag it onto the screen.\n\nTap a headline to open the story, the header "
                + "to open the full bulletin, and ↻ to refresh.");
        help.setTextSize(15);
        help.setTextColor(Color.parseColor("#9A968C"));
        help.setPadding(0, 32, 0, 48);

        Button open = new Button(this);
        open.setText("Open today's bulletin");
        open.setOnClickListener(v -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(BulletinWidget.BULLETIN_PAGE))));

        layout.addView(title);
        layout.addView(help);
        layout.addView(open);
        setContentView(layout);
    }
}
