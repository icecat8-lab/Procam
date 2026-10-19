package com.procamera

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.activity.ComponentActivity

class MenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val back = findViewById<View>(R.id.btn_back)
        AnimUtils.pressable(back, 0.88f)
        back.setOnClickListener { finish() }

        val rawSwitch = findViewById<CompoundButton>(R.id.switch_raw)
        rawSwitch.isChecked = AppSettings.isRawEnabled(this)
        rawSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setRawEnabled(this, checked)
        }

        val row = findViewById<View>(R.id.row_raw)
        AnimUtils.pressable(row, 0.985f)
        row.setOnClickListener { rawSwitch.toggle() }
    }

    override fun finish() {
        super.finish()
        AnimUtils.slideClose(this)
    }
}
