# 🔐 Configurar Keystore en GitHub Secrets

## Para proteger el keystore y no subirlo al repositorio público

### Paso 1: Codificar el keystore

Ya se generó el archivo `keystore.b64` con el keystore codificado en base64.

### Paso 2: Agregar Secrets en GitHub

Ve a: https://github.com/equisde/RoRoIntercept/settings/secrets/actions

Agrega estos secrets:

#### 1. KEYSTORE_BASE64
```
Nombre: KEYSTORE_BASE64
Valor: (pega el contenido completo del archivo keystore.b64)
```

#### 2. KEYSTORE_PASSWORD
```
Nombre: KEYSTORE_PASSWORD
Valor: RoRoDevs2024!
```

#### 3. KEY_ALIAS
```
Nombre: KEY_ALIAS
Valor: roro-key
```

#### 4. KEY_PASSWORD
```
Nombre: KEY_PASSWORD
Valor: RoRoDevs2024!
```

### Paso 3: Verificar Workflow

El workflow `build-release.yml` ahora:

1. Decodifica el keystore desde `KEYSTORE_BASE64`
2. Crea `keystore.properties` con los secrets
3. Firma el APK automáticamente
4. No expone las contraseñas en los logs

### Paso 4: Remover keystore del repo (Opcional)

Si quieres eliminar el keystore del historial de git:

```bash
# Hacer backup primero
cp app/roro-release.keystore ~/roro-keystore-backup.keystore

# Eliminar del repo
git rm app/roro-release.keystore
git commit -m "Remove keystore from repository - use GitHub Secrets"
git push origin main
```

**IMPORTANTE**: Antes de eliminar, asegúrate de:
- ✅ Tener backup del keystore
- ✅ Haber agregado KEYSTORE_BASE64 a GitHub Secrets
- ✅ Probar que el build funciona con secrets

### Archivos Protegidos

Estos archivos están en `.gitignore` y NO se subirán:

```
keystore.properties
keystore.b64
*.keystore.backup
```

### Cómo Funciona

**Localmente:**
- Lee credenciales de `keystore.properties`
- Usa `app/roro-release.keystore`

**En GitHub Actions:**
- Decodifica keystore desde secret `KEYSTORE_BASE64`
- Crea `keystore.properties` temporal con secrets
- Firma APK y elimina archivos sensibles

### Verificar que Funciona

1. Haz push del código
2. Ve a Actions: https://github.com/equisde/RoRoIntercept/actions
3. Espera que compile
4. Descarga el APK firmado

---

**© 2024 RoRo Devs**
