# 🔍 Guía de Reglas de Modificación - RoRo Interceptor

## Sistema de Búsqueda y Reemplazo Avanzado

RoRo Interceptor ahora soporta búsqueda y reemplazo flexible en headers y body de requests/responses.

## 📋 Tipos de Modificación

### 1. Modificación de Headers

#### a) Agregar/Reemplazar Headers
```json
{
  "Authorization": "Bearer NEW_TOKEN",
  "X-Custom-Header": "custom-value",
  "User-Agent": "RoRo-Interceptor/1.0"
}
```

#### b) Eliminar Headers
```
X-Powered-By, Server, X-Frame-Options
```

#### c) Buscar y Reemplazar en Headers
```json
[
  {
    "search": "old-domain.com",
    "replace": "new-domain.com",
    "useRegex": false,
    "caseSensitive": true,
    "replaceAll": true
  }
]
```

### 2. Modificación de Body

#### a) Reemplazo Completo
```json
{
  "status": "success",
  "premium": true,
  "credits": 9999
}
```

#### b) Buscar y Reemplazar en Body
```json
[
  {
    "search": "\"premium\":false",
    "replace": "\"premium\":true",
    "useRegex": false
  },
  {
    "search": "\\\"credits\\\":\\d+",
    "replace": "\"credits\":9999",
    "useRegex": true
  }
]
```

## 🎯 Ejemplos Completos

### Ejemplo 1: Modificar Status de Usuario a Premium

**Objetivo:** Cambiar `premium: false` a `premium: true` en respuestas

```json
{
  "name": "Usuario Premium",
  "urlPattern": "/api/user",
  "matchType": "CONTAINS",
  "action": "MODIFY",
  "modifyResponse": {
    "searchReplaceBody": [
      {
        "search": "\"premium\":false",
        "replace": "\"premium\":true",
        "useRegex": false,
        "caseSensitive": true,
        "replaceAll": true
      },
      {
        "search": "\"tier\":\"free\"",
        "replace": "\"tier\":\"premium\"",
        "useRegex": false
      }
    ]
  }
}
```

### Ejemplo 2: Cambiar Todos los Números a 9999 con Regex

**Objetivo:** Reemplazar todos los valores numéricos por 9999

```json
{
  "name": "Números a 9999",
  "urlPattern": "/api/credits",
  "matchType": "ENDS_WITH",
  "action": "MODIFY",
  "modifyResponse": {
    "searchReplaceBody": [
      {
        "search": "\\d+",
        "replace": "9999",
        "useRegex": true,
        "replaceAll": true
      }
    ]
  }
}
```

### Ejemplo 3: Modificar Headers de CORS

**Objetivo:** Agregar headers CORS y cambiar dominios

```json
{
  "name": "Fix CORS",
  "urlPattern": "api.example.com",
  "matchType": "CONTAINS",
  "action": "MODIFY",
  "modifyResponse": {
    "modifyHeaders": {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE"
    },
    "searchReplaceHeaders": [
      {
        "search": "example.com",
        "replace": "myapp.com",
        "useRegex": false,
        "replaceAll": true
      }
    ]
  }
}
```

### Ejemplo 4: Eliminar Información Sensible

**Objetivo:** Remover headers de servidor y buscar/eliminar emails

```json
{
  "name": "Quitar Info Sensible",
  "urlPattern": ".*",
  "matchType": "REGEX",
  "action": "MODIFY",
  "modifyResponse": {
    "removeHeaders": [
      "Server",
      "X-Powered-By",
      "X-AspNet-Version"
    ],
    "searchReplaceBody": [
      {
        "search": "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
        "replace": "[REDACTED]",
        "useRegex": true,
        "replaceAll": true
      }
    ]
  }
}
```

### Ejemplo 5: Cambiar URLs en Body

**Objetivo:** Reemplazar todas las URLs de un dominio a otro

```json
{
  "name": "Cambiar URLs",
  "urlPattern": "/api/config",
  "matchType": "CONTAINS",
  "action": "MODIFY",
  "modifyResponse": {
    "searchReplaceBody": [
      {
        "search": "https://old-cdn.example.com",
        "replace": "https://new-cdn.example.com",
        "useRegex": false,
        "caseSensitive": true,
        "replaceAll": true
      }
    ]
  }
}
```

### Ejemplo 6: Modificar JSON con Múltiples Reemplazos

**Objetivo:** Cambiar varios valores en un JSON de respuesta

```json
{
  "name": "Modificar Config JSON",
  "urlPattern": "/api/config",
  "matchType": "EXACT",
  "action": "MODIFY",
  "modifyResponse": {
    "searchReplaceBody": [
      {
        "search": "\"max_downloads\":5",
        "replace": "\"max_downloads\":999",
        "useRegex": false
      },
      {
        "search": "\"ads_enabled\":true",
        "replace": "\"ads_enabled\":false",
        "useRegex": false
      },
      {
        "search": "\"subscription_required\":true",
        "replace": "\"subscription_required\":false",
        "useRegex": false
      }
    ]
  }
}
```

### Ejemplo 7: Bypass de Rate Limiting

**Objetivo:** Modificar headers de rate limit

```json
{
  "name": "Bypass Rate Limit",
  "urlPattern": "api.",
  "matchType": "CONTAINS",
  "action": "MODIFY",
  "modifyResponse": {
    "modifyHeaders": {
      "X-RateLimit-Remaining": "999999",
      "X-RateLimit-Reset": "9999999999"
    },
    "searchReplaceHeaders": [
      {
        "search": "X-RateLimit-Limit: \\d+",
        "replace": "X-RateLimit-Limit: 999999",
        "useRegex": true
      }
    ]
  }
}
```

### Ejemplo 8: Modificar Texto Visible (HTML)

**Objetivo:** Cambiar textos en HTML

```json
{
  "name": "Modificar HTML",
  "urlPattern": ".html",
  "matchType": "ENDS_WITH",
  "action": "MODIFY",
  "modifyResponse": {
    "searchReplaceBody": [
      {
        "search": "Versión Gratuita",
        "replace": "Versión Premium",
        "useRegex": false,
        "caseSensitive": false,
        "replaceAll": true
      },
      {
        "search": "<div class=\"ad\">.*?</div>",
        "replace": "",
        "useRegex": true,
        "replaceAll": true
      }
    ]
  }
}
```

## ⚙️ Parámetros de SearchReplace

| Parámetro | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `search` | string | (requerido) | Texto o patrón regex a buscar |
| `replace` | string | (requerido) | Texto de reemplazo |
| `useRegex` | boolean | false | Usar expresión regular |
| `caseSensitive` | boolean | true | Distinguir mayúsculas/minúsculas |
| `replaceAll` | boolean | true | Reemplazar todas las ocurrencias |

## 📝 Regex Útiles

### Números
```regex
\d+                    # Cualquier número
\d{1,3}                # Números de 1 a 3 dígitos
[0-9]+                 # Alternativa
```

### Emails
```regex
[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}
```

### URLs
```regex
https?://[^\s]+        # URLs HTTP/HTTPS
```

### JSON Values
```regex
"key":\s*"[^"]*"       # JSON string value
"key":\s*\d+           # JSON number value
"key":\s*(true|false)  # JSON boolean
```

### HTML Tags
```regex
<tag>.*?</tag>         # Specific tag
<[^>]+>                # Any tag
```

## 💡 Tips y Buenas Prácticas

1. **Testear Regex**: Usa https://regex101.com/ para probar patrones

2. **Escapar Caracteres Especiales**: En regex, escapa: `. * + ? ^ $ { } [ ] ( ) | \`
   - Ejemplo: `\.` para punto literal

3. **Case Insensitive**: Útil para texto de usuario
   ```json
   {"search": "premium", "replace": "free", "caseSensitive": false}
   ```

4. **Replace First Only**: Para cambiar solo la primera ocurrencia
   ```json
   {"search": "token", "replace": "NEW_TOKEN", "replaceAll": false}
   ```

5. **Combinar Técnicas**: Usa múltiples SearchReplace en orden
   ```json
   [
     {"search": "old", "replace": "new"},
     {"search": "false", "replace": "true"}
   ]
   ```

## ⚠️ Advertencias

- **Performance**: Regex complejos pueden ser lentos
- **Encoding**: Body debe ser texto (JSON, HTML, XML)
- **Backup**: Guarda reglas importantes
- **Testing**: Prueba reglas antes de usar en producción

## 🔗 Ver También

- [QUICKSTART.md](QUICKSTART.md) - Inicio rápido
- [FEATURES.txt](FEATURES.txt) - Lista completa de características
- [KEYSTORE_INFO.md](KEYSTORE_INFO.md) - Información del keystore

---

**© 2024 RoRo Devs**
