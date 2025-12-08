# 🚀 RoRo Interceptor

[![Android CI](https://github.com/YOUR_USERNAME/RoRoIntercept/workflows/Android%20CI%20Build/badge.svg)](https://github.com/YOUR_USERNAME/RoRoIntercept/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)

**Intercepta y modifica tráfico HTTP/HTTPS en Android - Como Fiddler Everywhere**

RoRo Interceptor es una potente aplicación Android para interceptar, analizar y modificar tráfico de red en tiempo real. Con interfaz web moderna, sistema de reglas avanzado y generación automática de certificados SSL.

## ✨ Características

### 🔥 Core Features
- ✅ **Proxy HTTPS** en puerto 2580 (acepta conexiones externas)
- ✅ **Certificados SSL automáticos** con BouncyCastle
- ✅ **Interfaz Web** moderna en puerto 8080
- ✅ **Sistema de Reglas** para modificar requests/responses
- ✅ **Headers y Body** - Modifica todo el tráfico
- ✅ **Interceptación MITM** completa
- ✅ **Web Dashboard** con auto-refresh
- ✅ **Material Design 3**

### 🎨 Interfaz Web (Port 8080)
- Dashboard en tiempo real
- Gestión de reglas (CRUD)
- Visualización de requests/responses
- Colores por método HTTP
- Auto-refresh cada 2 segundos

## 📱 Instalación

### Desde GitHub Actions

Los APKs se compilan automáticamente en cada push:

```bash
# Descargar el último APK desde GitHub Actions
# Ve a: Actions → Build → Artifacts → Download

# O desde Releases
gh release download latest --pattern "*.apk"
```

### Compilar Localmente

```bash
# Clonar repo
git clone https://github.com/YOUR_USERNAME/RoRoIntercept.git
cd RoRoIntercept

# Compilar
./gradlew assembleDebug

# Instalar
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🚀 Uso Rápido

1. **Iniciar Proxy**
   ```
   Abrir app → "Start Proxy"
   Anotar IP (ej: 192.168.1.100)
   ```

2. **Configurar WiFi**
   ```
   Settings → WiFi → Modify Network
   Proxy: Manual
   Host: 192.168.1.100
   Port: 2580
   ```

3. **Instalar Certificado CA**
   ```
   App Menu (⋮) → Export CA Certificate
   Settings → Security → Install from Storage
   Select: http_interceptor_ca.crt
   ```

4. **Acceder Web UI**
   ```
   http://192.168.1.100:8080
   ```

## 🎯 Crear Reglas

### Ejemplo: Modificar API Token
```javascript
Name: Change Auth Token
Pattern: api.myapp.com
Match: Contains
Action: Modify
Headers: {
  "Authorization": "Bearer NEW_TOKEN"
}
```

### Ejemplo: Bloquear Trackers
```javascript
Name: Block Ads
Pattern: googleads.com
Match: Contains
Action: Block
```

### Ejemplo: Mock Response
```javascript
Name: Premium User
Pattern: /api/user/profile
Match: Ends With
Action: Modify
Body: {
  "premium": true,
  "credits": 9999
}
```

## 🏗️ Arquitectura

```
┌─────────────────────────┐
│   Android App (UI)      │
│   Material Design 3     │
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│   ProxyService          │
│   Core Logic            │
└─────┬──────────┬────────┘
      │          │
┌─────▼───┐  ┌──▼─────────┐
│ Proxy   │  │ Web Server │
│ :2580   │  │ :8080      │
│ Netty   │  │ NanoHTTPD  │
└─────────┘  └────────────┘
```

## 🔧 Stack Tecnológico

- **Kotlin** 1.9.22
- **Gradle** 8.5
- **AGP** 8.2.1
- **Java** 17
- **Netty** 4.1.104
- **BouncyCastle** 1.77
- **NanoHTTPD** 2.3.1
- **Material 3**

## 📋 Requisitos

- Android 7.0+ (API 24)
- JDK 17
- Gradle 8.5+

## 🔮 Roadmap

### Fase 2: IA Integration (Planeado)
- [ ] Creación de reglas con lenguaje natural
- [ ] GPT-4 para generar reglas automáticamente
- [ ] Análisis inteligente de patrones
- [ ] Sugerencias automáticas

### Fase 3: Advanced Features
- [ ] WebSocket support
- [ ] HTTP/2 nativo
- [ ] Breakpoints (pausar requests)
- [ ] HAR export
- [ ] Dark mode
- [ ] VPN mode

## 🤝 Contribuir

¡Contribuciones bienvenidas!

```bash
# Fork el repo
git clone https://github.com/YOUR_USERNAME/RoRoIntercept.git

# Crear branch
git checkout -b feature/amazing-feature

# Commit
git commit -m "Add amazing feature"

# Push
git push origin feature/amazing-feature

# Abrir Pull Request
```

## 📄 Licencia

MIT License - Ver [LICENSE](LICENSE) para más detalles

## 🙏 Créditos

- [Netty](https://netty.io/)
- [BouncyCastle](https://www.bouncycastle.org/)
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
- [Material Design](https://material.io/)

## 📞 Soporte

- **Issues**: [GitHub Issues](https://github.com/YOUR_USERNAME/RoRoIntercept/issues)
- **Discussions**: [GitHub Discussions](https://github.com/YOUR_USERNAME/RoRoIntercept/discussions)

---

**Hecho con ❤️ para la comunidad Android**

*Disclaimer: Solo para propósitos educativos. Usar responsablemente.*
