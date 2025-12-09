#!/bin/bash

# Script para crear keystores persistentes para RoRo Interceptor
# Autor: RoRo Devs

echo "═══════════════════════════════════════════════════════"
echo "  RoRo Interceptor - Keystore Generator"
echo "  by RoRo Devs"
echo "═══════════════════════════════════════════════════════"
echo ""

# Crear debug keystore
echo "[1/4] Creating Debug Keystore..."
keytool -genkey -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=RoRo Devs Debug, OU=Development, O=RoRo Devs, L=Santiago, ST=RM, C=CL"

# Crear release keystore
echo ""
echo "[2/4] Creating Release Keystore..."
keytool -genkey -v \
  -keystore release.keystore \
  -storepass rocco820! \
  -alias rorointerceptor \
  -keypass rocco820! \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=RoRo Devs, OU=Mobile Development, O=RoRo Devs, L=Santiago, ST=RM, C=CL"

# Convertir a base64
echo ""
echo "[3/4] Encoding keystores to base64..."
DEBUG_KEYSTORE_BASE64=$(base64 -w 0 debug.keystore)
RELEASE_KEYSTORE_BASE64=$(base64 -w 0 release.keystore)

echo ""
echo "[4/4] Generating GitHub Secrets commands..."
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  GitHub Secrets - Use these commands:"
echo "═══════════════════════════════════════════════════════"
echo ""
echo "# Debug Keystore Secrets"
echo "gh secret set DEBUG_KEYSTORE_BASE64 -b\"$DEBUG_KEYSTORE_BASE64\""
echo "gh secret set DEBUG_KEYSTORE_PASSWORD -b\"android\""
echo "gh secret set DEBUG_KEY_ALIAS -b\"androiddebugkey\""
echo "gh secret set DEBUG_KEY_PASSWORD -b\"android\""
echo ""
echo "# Release Keystore Secrets"
echo "gh secret set KEYSTORE_BASE64 -b\"$RELEASE_KEYSTORE_BASE64\""
echo "gh secret set KEYSTORE_PASSWORD -b\"rocco820!\""
echo "gh secret set KEY_ALIAS -b\"rorointerceptor\""
echo "gh secret set KEY_PASSWORD -b\"rocco820!\""
echo ""
echo "═══════════════════════════════════════════════════════"
echo ""
echo "✅ Keystores created successfully!"
echo "⚠️  DO NOT commit these files to git!"
echo "📝 Copy and run the commands above to set GitHub Secrets"
echo ""
