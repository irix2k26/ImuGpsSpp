package com.example.imugpsspp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hace que el celular se comporte como un ESP32 con BluetoothSerial:
 * escucha un servicio SPP (RFCOMM) y, cuando la laptop se conecta,
 * le manda líneas de texto con IMU y GPS.
 */
@SuppressLint("MissingPermission")
class SppService : Service() {

    companion object {
        private const val TAG = "SppService"
        private const val NOMBRE_SERVICIO = "IMU-GPS-SPP"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val hilo = HandlerThread("sensores")
    private lateinit var h: Handler
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var corriendo = false
    @Volatile private var conectado = false
    @Volatile private var transmitiendo = true
    @Volatile private var servidorActual: BluetoothServerSocket? = null
    @Volatile private var clienteActual: BluetoothSocket? = null

    // Cola hacia el hilo escritor: los sensores nunca se bloquean esperando al Bluetooth.
    private val cola = LinkedBlockingQueue<String>(2000)

    private var modoNmea = false
    private var hz = 50

    // Últimos valores de los sensores (se leen y escriben en el mismo hilo "h")
    private val acc = FloatArray(3)
    private val gyr = FloatArray(3)
    private val mag = FloatArray(3)

    @Volatile private var ultima: Location? = null
    @Volatile private var nsatUsados = 0
    @Volatile private var nsatVisibles = 0
    private var proxImu = 0L

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        modoNmea = prefs.getBoolean("nmea", false)
        hz = prefs.getInt("hz", 50).coerceIn(1, 200)

        iniciarNotificacion()

        // CPU despierta con la pantalla apagada (si no, los timers se congelan)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ImuGpsSpp:servicio").apply {
            setReferenceCounted(false)
            acquire()
        }

        corriendo = true
        Estado.corriendo = true
        Estado.lineas = 0
        Estado.perdidas = 0
        Estado.ultima = "-"

        hilo.start()
        h = Handler(hilo.looper)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        iniciarGps()
        if (!modoNmea) iniciarImu()
        thread(name = "spp-servidor") { bucleServidor() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        corriendo = false
        conectado = false
        Estado.corriendo = false
        try { servidorActual?.close() } catch (e: Exception) { }
        try { clienteActual?.close() } catch (e: Exception) { }
        try { h.removeCallbacksAndMessages(null) } catch (e: Exception) { }
        try { lm.removeUpdates(locListener) } catch (e: Exception) { }
        try { lm.unregisterGnssStatusCallback(gnssCb) } catch (e: Exception) { }
        try { lm.removeNmeaListener(nmeaListener) } catch (e: Exception) { }
        try { sm.unregisterListener(sensorListener) } catch (e: Exception) { }
        hilo.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        Estado.bt = "Detenido"
        Estado.gps = "-"
        Estado.sensores = "-"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- Bluetooth SPP

    private fun bucleServidor() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        while (corriendo) {
            if (adapter == null || !adapter.isEnabled) {
                Estado.bt = "Bluetooth apagado: enciéndelo"
                pausa(2000)
                continue
            }
            var servidor: BluetoothServerSocket? = null
            try {
                val s = adapter.listenUsingRfcommWithServiceRecord(NOMBRE_SERVICIO, SPP_UUID)
                servidor = s
                servidorActual = s
                Estado.bt = "Esperando a la laptop (SPP)..."
                val cliente = s.accept()          // bloquea hasta que la laptop abre el puerto COM
                s.close()
                servidorActual = null
                atender(cliente)
            } catch (e: IOException) {
                if (corriendo) {
                    Log.w(TAG, "servidor: ${e.message}")
                    pausa(2000)
                }
            } catch (e: SecurityException) {
                Estado.bt = "Falta permiso de Bluetooth"
                pausa(3000)
            } finally {
                try { servidor?.close() } catch (e: IOException) { }
                servidorActual = null
            }
        }
    }

    private fun atender(cliente: BluetoothSocket) {
        clienteActual = cliente
        val nombre = try { cliente.remoteDevice.name ?: cliente.remoteDevice.address } catch (e: Exception) { "laptop" }
        Estado.bt = "Conectado: $nombre"
        cola.clear()
        transmitiendo = true
        conectado = true

        val escritor = thread(name = "spp-escritor") {
            try {
                val out = cliente.outputStream
                while (conectado) {
                    val linea = cola.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(linea.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    Estado.lineas++
                    Estado.ultima = linea.trim()
                }
            } catch (e: IOException) {
                Log.w(TAG, "escritor: ${e.message}")
            } finally {
                conectado = false
                try { cliente.close() } catch (e: IOException) { }
            }
        }

        // Lectura de comandos de la laptop: START, STOP, PING (y detecta la desconexión)
        try {
            val lector = cliente.inputStream.bufferedReader(Charsets.US_ASCII)
            while (conectado) {
                val cmd = lector.readLine() ?: break
                when (cmd.trim().uppercase(Locale.US)) {
                    "START" -> transmitiendo = true
                    "STOP" -> transmitiendo = false
                    "PING" -> cola.offer("PONG\r\n")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "lector: ${e.message}")
        }

        conectado = false
        try { cliente.close() } catch (e: IOException) { }
        clienteActual = null
        try { escritor.join(1500) } catch (e: InterruptedException) { }
        if (corriendo) Estado.bt = "Laptop desconectada"
    }

    private fun enviar(linea: String) {
        if (!conectado || !transmitiendo) return
        if (!cola.offer(linea)) Estado.perdidas++
    }

    private fun pausa(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    // ---------------------------------------------------------------- IMU

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> System.arraycopy(e.values, 0, acc, 0, 3)
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(e.values, 0, gyr, 0, 3)
                Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(e.values, 0, mag, 0, 3)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // Emite una línea IMU a frecuencia fija (todos los sensores en la misma muestra)
    private val tickImu = object : Runnable {
        override fun run() {
            if (!corriendo) return
            enviar(
                String.format(
                    Locale.US,
                    "IMU,%d,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,%.2f,%.2f,%.2f\r\n",
                    System.currentTimeMillis(),
                    acc[0], acc[1], acc[2],
                    gyr[0], gyr[1], gyr[2],
                    mag[0], mag[1], mag[2]
                )
            )
            proxImu += 1000L / hz
            h.postAtTime(this, proxImu)
        }
    }

    private fun iniciarImu() {
        val periodoUs = 1_000_000 / hz
        val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        a?.let { sm.registerListener(sensorListener, it, periodoUs, h) }
        g?.let { sm.registerListener(sensorListener, it, periodoUs, h) }
        m?.let { sm.registerListener(sensorListener, it, periodoUs, h) }
        Estado.sensores = "acc:${if (a != null) "ok" else "NO"} " +
                "gyro:${if (g != null) "ok" else "NO"} " +
                "mag:${if (m != null) "ok" else "NO"} @ $hz Hz"
        proxImu = SystemClock.uptimeMillis()
        h.post(tickImu)
    }

    // ---------------------------------------------------------------- GPS

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            ultima = location
        }

        // Se declaran todos: en Android 10 el sistema los llama y no tienen implementación por defecto.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usados = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) usados++
            }
            nsatUsados = usados
            nsatVisibles = status.satelliteCount
        }
    }

    // Modo NMEA: pasa tal cual las sentencias que genera el chip GPS
    private val nmeaListener = OnNmeaMessageListener { mensaje, _ ->
        enviar(mensaje.trim() + "\r\n")
    }

    // Una vez por segundo: línea GPS (modo CSV) y estado para la pantalla
    private val tickGps = object : Runnable {
        override fun run() {
            if (!corriendo) return
            val ahora = System.currentTimeMillis()
            val l = ultima
            val conFix = l != null &&
                    (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) < 5_000_000_000L

            Estado.gps = if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                "GPS apagado: actívalo en Ajustes > Ubicación"
            } else if (conFix && l != null) {
                String.format(Locale.US, "FIX %.6f, %.6f (±%.0f m) sats %d/%d",
                    l.latitude, l.longitude, l.accuracy, nsatUsados, nsatVisibles)
            } else {
                "sin fix (sats usados %d / visibles %d). Sal al aire libre.".format(nsatUsados, nsatVisibles)
            }

            if (!modoNmea) {
                if (conFix && l != null) {
                    enviar(
                        String.format(
                            Locale.US,
                            "GPS,%d,1,%.7f,%.7f,%.1f,%.2f,%.1f,%.1f,%d\r\n",
                            ahora, l.latitude, l.longitude, l.altitude,
                            l.speed, l.bearing, l.accuracy, nsatUsados
                        )
                    )
                } else {
                    enviar(String.format(Locale.US, "GPS,%d,0,,,,,,,%d\r\n", ahora, nsatUsados))
                }
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun iniciarGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, hilo.looper)
            lm.registerGnssStatusCallback(gnssCb, h)
            if (modoNmea) lm.addNmeaListener(nmeaListener, h)
        } catch (e: SecurityException) {
            Estado.gps = "sin permiso de ubicación"
            Log.e(TAG, "GPS sin permiso", e)
        }
        h.post(tickGps)
    }

    // ---------------------------------------------------------------- notificación

    private fun iniciarNotificacion() {
        val canal = "imu_gps_spp"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(canal, "IMU GPS SPP", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, canal)
            .setContentTitle("IMU GPS SPP")
            .setContentText("Transmitiendo IMU y GPS por Bluetooth SPP")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(201, notif)
    }
}
