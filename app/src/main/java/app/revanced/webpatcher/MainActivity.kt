package app.revanced.webpatcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggle_button)
        statusText = findViewById(R.id.status)
        urlText = findViewById(R.id.url)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopServer()
            } else {
                startServer()
            }
        }
    }

    private fun startServer() {
        val intent = Intent(this, BackendService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        isRunning = true
        toggleButton.text = getString(R.string.stop_server)
        statusText.text = getString(R.string.server_running)
        urlText.text = "http://localhost:3000"
        urlText.visibility = View.VISIBLE
    }

    private fun stopServer() {
        val intent = Intent(this, BackendService::class.java)
        stopService(intent)
        
        isRunning = false
        toggleButton.text = getString(R.string.start_server)
        statusText.text = getString(R.string.server_stopped)
        urlText.visibility = View.GONE
    }
}
