package dev.heartline

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where the Zepp sign-in page sends the browser once Heartline has the sign-in (heartline://zepp-signed-in): brings the
 * app back over the browser tab and closes.
 */
class ZeppReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
