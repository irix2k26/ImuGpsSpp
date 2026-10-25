# IMU GPS SPP

App Android que convierte el celular en un "ESP32" con Bluetooth Serial (SPP/RFCOMM):
manda IMU (acelerómetro, giroscopio, magnetómetro) y GPS a la laptop como líneas de texto.
No usa servicios de Google (GPS por `LocationManager`).

## Uso rápido (Windows)
1. Instala el APK, abre la app, pulsa **Iniciar** y concede permisos y el aviso de batería.
2. Pulsa **Hacer visible 5 min** y, en Windows, *Configuración > Bluetooth y dispositivos > Agregar dispositivo > Bluetooth*; empareja el celular.
3. Con la app en "Esperando a la laptop (SPP)": *Más opciones de Bluetooth > Puertos COM > Agregar > Saliente*, elige el celular y anota el COM (p. ej. COM5).
4. `pip install pyserial` y luego `python tools/leer_spp.py COM5` (o `--csv` para guardar).

## Formato de datos (CSV, una línea por muestra, terminada en CRLF)
    IMU,ms,ax,ay,az,gx,gy,gz,mx,my,mz        m/s², rad/s, µT, a la frecuencia elegida
    GPS,ms,fix,lat,lon,alt,vel,rumbo,prec,sats   1 Hz; fix=0 deja los campos vacíos
`ms` es la hora del celular en milisegundos Unix. Comandos desde la laptop: `PING`, `STOP`, `START`.

## Modo NMEA
Marca la casilla y reinicia el servicio: solo se envían las sentencias NMEA crudas del GPS
(`$GPGGA`, `$GPRMC`...), compatibles con Google Earth, u-center, gpsd, etc.

## Notas
- El GPS necesita cielo despejado; en interiores no habrá fix. El primer fix puede tardar varios minutos.
- Si el puerto COM no abre, revisa que la app diga "Esperando a la laptop" antes de abrirlo.
- Los APK se firman con `app/debug.keystore` (incluido a propósito) para poder actualizar con `adb install -r`.
