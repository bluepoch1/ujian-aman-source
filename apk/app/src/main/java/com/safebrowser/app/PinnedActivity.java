package com.safebrowser.app;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

/** Penjelasan singkat tentang penyematan layar. */
public class PinnedActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pinned);
        findViewById(R.id.btn_mengerti).setOnClickListener(v -> finish());
    }
}
