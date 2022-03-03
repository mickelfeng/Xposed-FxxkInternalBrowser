package five.ec1cff.intentforwarder

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetTextI18n")
    fun updateView() {
        val stateView = findViewById<TextView>(R.id.state)
        val opButton = findViewById<Button>(R.id.opButton)
        if (MyApplication.controller == null) {
            stateView.text = "service not respond"
            opButton.isEnabled = false
        } else {
            opButton.isEnabled = true
            val controller: IController = MyApplication.controller ?: return
            val state = controller.state
            stateView.text = if (state) "enabled" else "disabled"
            opButton.text = if (state) "disable" else "enable"
            opButton.setOnClickListener {
                controller.state = !state
                updateView()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        refreshButton.setOnClickListener { updateView() }
        updateView()
        findViewById<Button>(R.id.dumpButton).setOnClickListener {
            MyApplication.controller?.let {
                val s = it.dumpService()
                AlertDialog.Builder(this).setMessage(s)
                    .setPositiveButton("copy") { _, _ ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("", s)
                        )
                    }
                    .show()
            }
        }

        findViewById<Button>(R.id.dumpJsonButton).setOnClickListener {
            MyApplication.controller?.dumpJson()
        }

        findViewById<Button>(R.id.loadJsonButton).setOnClickListener {
            MyApplication.controller?.loadJson()
        }
    }

    override fun onResume() {
        super.onResume()
        updateView()
    }
}
