# 🚀 Deployment Guide - RoRo Interceptor

## GitHub Actions CI/CD

El proyecto está configurado con GitHub Actions para compilar automáticamente los APKs.

### Workflows Configurados

#### 1. Build Workflow (`.github/workflows/build.yml`)
Se ejecuta en:
- Push a `main` o `develop`
- Pull Requests a `main`
- Manualmente desde GitHub UI

**Pasos:**
1. Checkout del código
2. Setup JDK 17 (Temurin)
3. Cache de Gradle
4. Build completo
5. Compilar APK Debug
6. Compilar APK Release
7. Subir artifacts
8. Ejecutar tests

**Artifacts generados:**
- `app-debug.apk`
- `app-release-unsigned.apk`

#### 2. Release Workflow (`.github/workflows/release.yml`)
Se ejecuta cuando:
- Se crea un tag con formato `v*` (ej: v1.0.0)
- Manualmente desde GitHub UI

**Crea un Release con:**
- APK Debug
- APK Release
- Release notes automáticas

### Cómo Descargar APKs Compilados

#### Opción 1: Desde Actions
```bash
1. Ve a: https://github.com/YOUR_USERNAME/RoRoIntercept/actions
2. Click en el último workflow "Android CI Build"
3. Scroll down a "Artifacts"
4. Download "app-debug" o "app-release"
```

#### Opción 2: Desde Releases (si existe tag)
```bash
# Con gh CLI
gh release download latest --pattern "*.apk"

# O desde el navegador
https://github.com/YOUR_USERNAME/RoRoIntercept/releases/latest
```

### Crear un Release

```bash
# Crear tag
git tag v1.0.0
git push origin v1.0.0

# GitHub Actions automáticamente:
# 1. Compila los APKs
# 2. Crea el Release
# 3. Sube los archivos
```

## Versiones de Dependencias

### Actualizadas a las últimas (Diciembre 2024)

**Build Tools:**
- Gradle: `8.5`
- Android Gradle Plugin: `8.2.1`
- Kotlin: `1.9.22`
- Java: `17`

**AndroidX:**
- Core KTX: `1.12.0`
- AppCompat: `1.6.1`
- Material: `1.11.0`
- ConstraintLayout: `2.1.4`
- RecyclerView: `1.3.2`
- Lifecycle: `2.7.0`

**Networking:**
- Netty: `4.1.104.Final` ⬆️
- BouncyCastle (jdk18on): `1.77` ⬆️
- NanoHTTPD: `2.3.1`

**Utilities:**
- Gson: `2.10.1`
- Coroutines: `1.7.3`

## Compilar Localmente

### Requisitos
```bash
- JDK 17
- Android SDK
- ANDROID_HOME configurado
```

### Build Commands

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (sin firmar)
./gradlew assembleRelease

# Limpiar build
./gradlew clean

# Build completo con tests
./gradlew build

# Instalar en dispositivo
./gradlew installDebug
```

### Ubicación de APKs

```
app/build/outputs/apk/
├── debug/
│   └── app-debug.apk
└── release/
    └── app-release-unsigned.apk
```

## Firmar APK para Release

### Generar Keystore

```bash
keytool -genkey -v \
  -keystore roro-release.keystore \
  -alias roro-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### Configurar en `app/build.gradle`

```gradle
android {
    signingConfigs {
        release {
            storeFile file("../roro-release.keystore")
            storePassword System.getenv("KEYSTORE_PASSWORD")
            keyAlias "roro-key"
            keyPassword System.getenv("KEY_PASSWORD")
        }
    }
    
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            shrinkResources true
        }
    }
}
```

### Compilar Release Firmado

```bash
export KEYSTORE_PASSWORD="your_password"
export KEY_PASSWORD="your_key_password"

./gradlew assembleRelease
```

## GitHub Secrets (para CI/CD con firma)

Si quieres firmar en GitHub Actions:

```bash
# Añadir secrets en GitHub
Settings → Secrets and variables → Actions

Secrets necesarios:
- KEYSTORE_FILE (base64 del .keystore)
- KEYSTORE_PASSWORD
- KEY_ALIAS
- KEY_PASSWORD
```

## Optimizaciones de Build

### ProGuard/R8 (Release)

Configurado en `app/proguard-rules.pro`:
```
-keep class io.netty.** { *; }
-keep class org.bouncycastle.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
```

### Build Types

**Debug:**
- BuildConfig.DEBUG = true
- Debuggable
- Application ID suffix: `.debug`
- Sin minificación

**Release:**
- Minificación habilitada (R8)
- Shrink resources
- ProGuard optimizations
- Sin sufijo en ID

## Troubleshooting

### Error: JDK version
```bash
# Asegúrate de usar JDK 17
java -version

# O especifica en gradle.properties
org.gradle.java.home=/path/to/jdk-17
```

### Error: Gradle daemon
```bash
# Limpiar y reiniciar
./gradlew --stop
./gradlew clean
./gradlew build
```

### Error: Cache corrupto
```bash
# Limpiar cache de Gradle
rm -rf ~/.gradle/caches
./gradlew build --refresh-dependencies
```

## Continuous Deployment

### Auto-deploy a Google Play (futuro)

```yaml
# .github/workflows/deploy.yml
- name: Deploy to Play Store
  uses: r0adkll/upload-google-play@v1
  with:
    serviceAccountJsonPlainText: ${{ secrets.SERVICE_ACCOUNT_JSON }}
    packageName: com.httpinterceptor
    releaseFiles: app/build/outputs/bundle/release/app-release.aab
    track: internal
```

## Monitoring

### Build Status

```markdown
[![Build Status](https://github.com/YOUR_USERNAME/RoRoIntercept/workflows/Android%20CI%20Build/badge.svg)](https://github.com/YOUR_USERNAME/RoRoIntercept/actions)
```

### Release Version

```markdown
[![Release](https://img.shields.io/github/v/release/YOUR_USERNAME/RoRoIntercept)](https://github.com/YOUR_USERNAME/RoRoIntercept/releases)
```

---

**¿Problemas?** Abre un [issue](https://github.com/YOUR_USERNAME/RoRoIntercept/issues)
