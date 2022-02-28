package five.ec1cff.intentforwarder

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button

class IntentForwardActivity : AppCompatActivity() {
    val TAG = "IntentForwardActivity"
    fun modifyAndForwardIntent(f: Intent.() -> Unit) {
        if (intent.action == ACTION_REQUEST_FORWARD) {
            val newIntent = Intent(ACTION_FORWARD_INTENT)
            val editIntent = intent.extras?.get(Intent.EXTRA_INTENT) as Intent
            f(editIntent)
            newIntent.putExtra(Intent.EXTRA_INTENT, editIntent)
            startActivity(newIntent)
            finish()
        }
    }

    // TODO: Only accept intent forwarding request sent by us
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "forward intent ${intent}")
        setContentView(R.layout.forward_activity)
        findViewById<Button>(R.id.direct_launch).setOnClickListener {
            modifyAndForwardIntent {

            }
        }
        findViewById<Button>(R.id.replace_launch).setOnClickListener {
            modifyAndForwardIntent {
                this.component = null
                this.action = Intent.ACTION_VIEW
                this.getStringExtra("extra_url")?.also {
                    this.data = Uri.parse(it)
                }
            }
        }
    }
}