package com.bharatdroid.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal dialer required when BharatDroid is set as the default Phone app.
 * Handles ACTION_DIAL and ACTION_CALL intents so users can still make outgoing calls normally.
 * Incoming calls are handled by CallAnsweringService (InCallService), not here.
 */
class DialerActivity : AppCompatActivity() {

    private var number = StringBuilder()
    private lateinit var tvDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If launched with a number pre-filled (e.g. from contacts), pre-populate it
        val prefilledNumber = when (intent?.action) {
            Intent.ACTION_DIAL, Intent.ACTION_CALL, Intent.ACTION_VIEW -> {
                intent.data?.schemeSpecificPart?.let { Uri.decode(it) } ?: ""
            }
            else -> ""
        }
        if (prefilledNumber.isNotBlank()) number.append(prefilledNumber)

        setContentView(R.layout.activity_dialer)
        tvDisplay = findViewById(R.id.tvDialerNumber)
        tvDisplay.text = number.toString().ifEmpty { "Enter number" }

        val keys = mapOf(
            R.id.btnDial0 to "0", R.id.btnDial1 to "1", R.id.btnDial2 to "2",
            R.id.btnDial3 to "3", R.id.btnDial4 to "4", R.id.btnDial5 to "5",
            R.id.btnDial6 to "6", R.id.btnDial7 to "7", R.id.btnDial8 to "8",
            R.id.btnDial9 to "9", R.id.btnDialStar to "*",  R.id.btnDialHash to "#",
        )

        keys.forEach { (id, digit) ->
            findViewById<Button>(id).setOnClickListener {
                number.append(digit)
                tvDisplay.text = number.toString()
            }
        }

        findViewById<Button>(R.id.btnDialDelete).setOnClickListener {
            if (number.isNotEmpty()) {
                number.deleteCharAt(number.length - 1)
                tvDisplay.text = number.toString().ifEmpty { "Enter number" }
            }
        }

        findViewById<Button>(R.id.btnDialCall).setOnClickListener {
            val num = number.toString().trim()
            if (num.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
            }
        }

        // Back button
        findViewById<Button>(R.id.btnDialerBack).setOnClickListener { finish() }
    }
}
