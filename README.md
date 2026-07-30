# KeyInput Fabric Updater Mod (Korosoft SMP)

Este mod para Minecraft 1.21.x agrega un sistema de actualización automática y relanzamiento sin intervención manual para proyectos modpack privados usando Fabric + Prism Launcher (o cualquier launcher basado en argumentos estándar).

## Características principales
- **Hot reload de mod:** Descarga y reemplaza su propio JAR y se relanza automáticamente.
- **Payload seguro:** Toda update exige hash SHA-256 binario y msg de update (compatible con payload custom como DiSky/Skript).
- **Compatible:** Prism Launcher, MultiMC, ATLauncher y cualquier launcher que use argumentos Java estándar.

## Cómo funciona
1. El servidor Minecraft (o Skript/plugin/bridge) detecta nueva versión.
2. Empuja un payload en el canal `keyinput:mod_update` al cliente (ver ejemplo).
3. El cliente descarga el nuevo JAR desde la URL HTTPS, verifica el hash, reemplaza el mod y relanza el proceso (no hace falta cerrar Minecraft ni relanzar a mano).
4. El jugador no necesita instalar nada manual ni tocar carpetas `mods/`.

## Ejemplo de payload desde server (pseudocódigo):
```json
{
  "version": 12151, // major*10000+minor*100+patch
  "downloadUrl": "https://github.com/<tu-user>/<tu-repo>/releases/download/v1.21.51/keyinput-1.21.51.jar",
  "sha256": "<SHA256 en hexadecimal, 64 chars>",
  "message": "¡Soporte para mouse!",
  "mandatory": true
}
```

## Requisitos
- Minecraft 1.21.11 (Fabric)
- Java 21+
- Fabric API, Fabric Loader >=0.16

## Publicación oficial
- Subí el JAR como asset a GitHub Releases.
- Calculá el hash del archivo descargado de GitHub. Ejemplo:
  ```bash
  wget -O test.jar "https://github.com/<tu-user>/<tu-repo>/releases/download/v1.21.51/keyinput-1.21.51.jar"
  sha256sum test.jar
  ```
- Usá ese SHA y URL exactos en el payload del servidor/scripts.

---

### ¿Cómo probar?
- Compilá y copiá el JAR a tu carpeta de mods en PrismLauncher.
- Podés forzar la actualización debug disparando `ModUpdater.get().beginUpdate()` desde código (por ejemplo, al apretar una tecla), usando file:// en vez de https://, o desde DiSky/Skript custom.
- Si todo anda, relanza al toque y carga el mod nuevo sin cerrar el launcher ni el juego.

---

**© 2024 Korosoft SMP / @youruser**
