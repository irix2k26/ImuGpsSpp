#!/usr/bin/env python3
"""Lee el puerto COM Bluetooth (SPP) del celular, como si fuera un ESP32.

Uso:
    pip install pyserial
    python leer_spp.py COM5            # imprime IMU y GPS
    python leer_spp.py COM5 --csv      # además guarda datos_AAAAMMDD_HHMMSS.csv

Formato de las líneas que manda el celular:
    IMU,ms,ax,ay,az,gx,gy,gz,mx,my,mz     (m/s2, rad/s, uT)
    GPS,ms,fix,lat,lon,alt,vel,rumbo,prec,sats   (fix=1 con posición; 0 sin fix)
Comandos que acepta: PING (responde PONG), STOP, START.
"""
import argparse
import csv
import time

import serial


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("puerto", help="puerto COM saliente del celular, p. ej. COM5")
    ap.add_argument("--baud", type=int, default=115200)  # ignorado por Bluetooth, pero pyserial lo pide
    ap.add_argument("--csv", action="store_true", help="guardar un CSV con todo")
    args = ap.parse_args()

    archivo = escritor = None
    if args.csv:
        nombre = time.strftime("datos_%Y%m%d_%H%M%S.csv")
        archivo = open(nombre, "w", newline="")
        escritor = csv.writer(archivo)
        print("Guardando en", nombre)

    print(f"Abriendo {args.puerto}... (la app debe estar en 'Esperando a la laptop')")
    with serial.Serial(args.puerto, args.baud, timeout=2) as s:
        s.write(b"PING\r\n")
        n_imu = 0
        t0 = time.time()
        try:
            while True:
                linea = s.readline().decode("ascii", "ignore").strip()
                if not linea:
                    continue
                campos = linea.split(",")
                if escritor:
                    escritor.writerow(campos)

                if campos[0] == "GPS" and len(campos) >= 10:
                    if campos[2] == "1":
                        print(f"GPS  lat={campos[3]} lon={campos[4]} alt={campos[5]} m "
                              f"vel={campos[6]} m/s ±{campos[8]} m sats={campos[9]}")
                    else:
                        print(f"GPS  sin fix (sats usados: {campos[9]})")
                elif campos[0] == "IMU" and len(campos) >= 11:
                    n_imu += 1
                    if n_imu % 25 == 0:  # no inundar la consola
                        hz = n_imu / (time.time() - t0)
                        print(f"IMU  acc=({campos[2]}, {campos[3]}, {campos[4]}) "
                              f"gyro=({campos[5]}, {campos[6]}, {campos[7]})  ~{hz:.0f} Hz")
                else:
                    print(linea)  # PONG, sentencias NMEA, etc.
        except KeyboardInterrupt:
            print("\nFin.")
        finally:
            if archivo:
                archivo.close()


if __name__ == "__main__":
    main()
