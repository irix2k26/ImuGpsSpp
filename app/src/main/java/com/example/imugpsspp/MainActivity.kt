package com.example.imugpsspp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtEstado: TextView
    private lateinit var chkNmea: CheckBox
    private lateinit var grupoHz: RadioGroup

    private val ui = Handler(Looper.getMainLooper())
    private val refresco = object : Runnable {
        override fun run() {
            actualizar()
            ui.postDelayed(this, 1000)
        }
    }

    private val pedirPermisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.all { it }) {
                iniciarServicio()
            } else {
                Toast.makeText(this, "Faltan permisos (ubicación y Bluetooth)", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val margen = (16 * dp).toInt()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margen, margen, margen, margen)
        }

        raiz.addView(TextView(this).apply {
            text = "IMU + GPS por Bluetooth SPP"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        raiz.addView(TextView(this).apply {
            text = "Frecuencia de la IMU:"
            setPadding(0, margen, 0, 0)
        })
        grupoHz = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hzGuardado = prefs.getInt("hz", 50)
        listOf(10, 25, 50, 100).forEach { v ->
            grupoHz.addView(RadioButton(this).apply {
                text = "$v Hz"
                id = View.generateViewId()
                tag = v
                isChecked = (v == hzGuardado)
            })
        }
        raiz.addView(grupoHz)

        chkNmea = CheckBox(this).apply {
            text = "Modo NMEA (solo GPS, para Google Earth / u-center / gpsd)"
            isChecked = prefs.getBoolean("nmea", false)
        }
        raiz.addView(chkNmea)

        raiz.addView(Button(this).apply {
            text = "Iniciar / reiniciar servicio"
            setOnClickListener { pedirPermisosEIniciar() }
        })
        raiz.addView(Button(this).apply {
            text = "Detener"
            setOnClickListener { stopService(Intent(this@MainActivity, SppService::class.java)) }
        })
        raiz.addView(Button(this).apply {
            text = "Hacer visible 5 min (para emparejar)"
            setOnClickListener { hacerVisible() }
        })

        txtEstado = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, margen, 0, 0)
        }
        raiz.addView(txtEstado)

        setContentView(ScrollView(this).apply { addView(raiz) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresco)
    }

    override fun onPause() {
        ui.removeCallbacks(refresco)
        super.onPause()
    }

    private fun actualizar() {
        txtEstado.text = "Servicio:  ${if (Estado.corriendo) "ACTIVO" else "detenido"}\n" +
                "Bluetooth: ${Estado.bt}\n" +
                "GPS:       ${Estado.gps}\n" +
                "IMU:       ${Estado.sensores}\n" +
                "Enviadas:  ${Estado.lineas}   perdidas: ${Estado.perdidas}\n" +
                "Última:    ${Estado.ultima}"
    }

    private fun pedirPermisosEIniciar() {
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lista += Manifest.permission.BLUETOOTH_CONNECT
            lista += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lista += Manifest.permission.POST_NOTIFICATIONS
        }
        val faltan = lista.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltan.isEmpty()) iniciarServicio() else pedirPermisos.launch(faltan.toTypedArray())
    }

    private fun iniciarServicio() {
        val hz = grupoHz.findViewById<RadioButton>(grupoHz.checkedRadioButtonId)?.tag as? Int ?: 50
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putInt("hz", hz)
            .putBoolean("nmea", chkNmea.isChecked)
            .apply()

        val intent = Intent(this, SppService::class.java)
        stopService(intent)   // reinicia con la configuración nueva
        ui.postDelayed({ ContextCompat.startForegroundService(this, intent) }, 600)
        pedirIgnorarBateria()
    }

    private fun hacerVisible() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        )
    }

    // Aviso del sistema (una sola vez) para excluir la app del ahorro de batería
    private fun pedirIgnorarBateria() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }
}
