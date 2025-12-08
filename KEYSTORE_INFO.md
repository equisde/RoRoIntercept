# 🔐 Keystore Information - RoRo Devs

## Keystore Details

**File:** `app/roro-release.keystore`  
**Alias:** `roro-key`  
**Algorithm:** RSA 2048-bit  
**Validity:** 10,000 days (~27 years)  
**Created:** December 2024

## Certificate Details

```
CN=RoRo Devs
OU=Mobile Development  
O=RoRo Devs
L=Internet
ST=Worldwide
C=US
```

## Passwords

⚠️ **IMPORTANTE**: Guarda estas credenciales en un lugar seguro

```
Keystore Password: RoRoDevs2024!
Key Password: RoRoDevs2024!
```

## Uso

### Firmar APK manualmente

```bash
jarsigner -verbose \
  -sigalg SHA256withRSA \
  -digestalg SHA-256 \
  -keystore app/roro-release.keystore \
  -storepass RoRoDevs2024! \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  roro-key
```

### Verificar firma

```bash
jarsigner -verify -verbose -certs \
  app/build/outputs/apk/release/app-release.apk
```

### Ver información del keystore

```bash
keytool -list -v \
  -keystore app/roro-release.keystore \
  -storepass RoRoDevs2024!
```

## GitHub Actions

El keystore se usa automáticamente en el workflow de Release.

**Configuración en `app/build.gradle`:**

```gradle
signingConfigs {
    release {
        storeFile file('roro-release.keystore')
        storePassword 'RoRoDevs2024!'
        keyAlias 'roro-key'
        keyPassword 'RoRoDevs2024!'
    }
}
```

## Seguridad

### ⚠️ ADVERTENCIAS

1. **NO** subir este archivo a repositorios públicos
2. **NO** compartir las contraseñas
3. **Hacer backup** del keystore en lugar seguro
4. Si se pierde el keystore, **NO** se podrán actualizar las apps publicadas

### Para GitHub Actions (Opcional - Más seguro)

Si quieres ocultar las contraseñas:

1. Codifica el keystore en base64:
```bash
base64 app/roro-release.keystore > keystore.b64
```

2. Agrega secrets en GitHub:
   - `KEYSTORE_FILE`: Contenido del keystore.b64
   - `KEYSTORE_PASSWORD`: RoRoDevs2024!
   - `KEY_ALIAS`: roro-key
   - `KEY_PASSWORD`: RoRoDevs2024!

3. Modifica el workflow para usar secrets

## Backup

```bash
# Hacer backup
cp app/roro-release.keystore ~/backups/roro-keystore-backup-$(date +%Y%m%d).keystore

# Verificar backup
keytool -list -v \
  -keystore ~/backups/roro-keystore-backup-*.keystore \
  -storepass RoRoDevs2024!
```

## Renovación

Este keystore es válido por ~27 años (hasta ~2051).  
No es necesario renovarlo a menos que se comprometa.

---

**© 2024 RoRo Devs**
