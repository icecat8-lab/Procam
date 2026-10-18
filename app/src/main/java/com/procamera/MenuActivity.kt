package com.procamera

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.activity.ComponentActivity

class MenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val rawSwitch = findViewById<CompoundButton>(R.id.switch_raw)
        rawSwitch.isChecked = AppSettings.isRawEnabled(this)
        rawSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setRawEnabled(this, checked)
        }

        findViewById<View>(R.id.row_raw).setOnClickListener { rawSwitch.toggle() }
    }
}
