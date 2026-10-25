package com.example.imugpsspp

// Estado compartido entre el servicio y la pantalla (la pantalla lo lee cada segundo).
object Estado {
    @Volatile var corriendo: Boolean = false
    @Volatile var bt: String = "Detenido"
    @Volatile var gps: String = "-"
    @Volatile var sensores: String = "-"
    @Volatile var ultima: String = "-"
    @Volatile var lineas: Long = 0
    @Volatile var perdidas: Long = 0
}
