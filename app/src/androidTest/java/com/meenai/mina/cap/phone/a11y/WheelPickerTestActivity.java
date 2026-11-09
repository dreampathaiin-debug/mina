package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.util.Locale;

/**
 *Wheel test page:Two original digital scroll wheels(0–59,two digit display,cycle),All start from 0.
 *"Min" can be entered directly;"Lock" blocks the input box inside(Can't get focus,The words written in will not be used),You can only roll one square at a time.
 *The test APK page runs in the test package's own process, without the Kotlin standard library.,So write it in Java.
 */
public class WheelPickerTestActivity extends Activity {
    public static final String MINUTES = "point";
    public static final String LOCKED = "lock";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addPicker(row, MINUTES, false);
        addPicker(row, LOCKED, true);
        setContentView(row);
    }

    private void addPicker(LinearLayout row, String label, boolean block) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(20f);
        col.addView(title);
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(59);
        picker.setValue(0);
        picker.setFormatter(v -> String.format(Locale.ROOT, "%02d", v));
        picker.setContentDescription(label);
        if (block) picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        col.addView(picker);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
