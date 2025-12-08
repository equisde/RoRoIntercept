# HTTP Interceptor - Guía Rápida

## ⚡ Inicio Rápido (5 minutos)

### 1. Compilar e Instalar
```bash
cd HTTPInterceptor
./build.sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Configurar Proxy
1. Abre la app → "Start Proxy"
2. Anota la IP (ej: 192.168.1.100)
3. WiFi Settings → Modificar red → Proxy Manual
   - Host: 192.168.1.100
   - Puerto: 2580

### 3. Instalar Certificado (para HTTPS)
1. App → Menú (⋮) → "Exportar Certificado CA"
2. Ajustes → Seguridad → Instalar desde almacenamiento
3. Selecciona: http_interceptor_ca.crt

### 4. Usar Web UI
Abre en navegador: `http://192.168.1.100:8080`

## 🎯 Crear Primera Regla

### Ejemplo: Modificar API Token

**En Web UI:**
1. Click "+ Nueva Regla"
2. Llenar:
   ```
   Nombre: Cambiar Auth Token
   Patrón: api.myapp.com
   Tipo Match: Contains
   Acción: Modificar
   Headers: {"Authorization": "Bearer NEW_TOKEN"}
   ```
3. Click "Crear Regla"

¡Listo! Todos los requests a `api.myapp.com` tendrán el nuevo token.

## 📋 Ejemplos Comunes

### Bloquear Ads
```
Patrón: googleads.com
Acción: Bloquear
```

### Mock API Response
```
Patrón: /api/user
Acción: Modificar
Body: {"name": "Test User", "premium": true}
```

### Bypass Rate Limiting
```
Patrón: api.example.com
Acción: Modificar
Headers: {"X-RateLimit-Remaining": "9999"}
```

## 🔧 Troubleshooting

**HTTPS no funciona**
→ Verifica que el certificado CA esté instalado

**Requests no aparecen**
→ Revisa configuración del proxy (Host:Puerto)

**Web UI no carga**
→ Usa la IP correcta y puerto 8080

## 📱 Puertos Usados

- **2580**: Proxy HTTPS (conexiones externas)
- **8080**: Web UI Dashboard

## 🎨 Interfaz Web

Características principales:
- 📡 Requests en tiempo real
- ⚙️ Gestión de reglas
- 📝 Detalles completos
- 🔄 Auto-refresh cada 2s

## 🚀 Próximamente: IA para Reglas

La versión futura incluirá:
- Crear reglas con lenguaje natural
- "Bloquea todos los trackers de Google"
- "Modifica el JSON de respuesta para hacer premium = true"
- Sugerencias automáticas basadas en patrones

---

**¿Necesitas ayuda?** Lee el README.md completo
