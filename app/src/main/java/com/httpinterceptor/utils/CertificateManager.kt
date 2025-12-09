package com.httpinterceptor.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import android.util.Base64

class CertificateManager(private val context: Context) {
    
    private val keyStore: KeyStore
    private val caCert: X509Certificate
    private val caPrivateKey: PrivateKey
    
    init {
        // Remove and re-add BC provider to ensure it's properly initialized
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        
        val certDir = File(context.filesDir, "certs")
        if (!certDir.exists()) certDir.mkdirs()
        
        val keyStoreFile = File(certDir, "proxy.keystore")
        keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        
        if (keyStoreFile.exists()) {
            keyStoreFile.inputStream().use { fis ->
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray())
            }
            caCert = keyStore.getCertificate(CA_ALIAS) as X509Certificate
            caPrivateKey = keyStore.getKey(CA_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        } else {
            keyStore.load(null, null)
            val (cert, key) = generateCACertificate()
            caCert = cert
            caPrivateKey = key
            
            keyStore.setKeyEntry(
                CA_ALIAS,
                caPrivateKey,
                KEYSTORE_PASSWORD.toCharArray(),
                arrayOf(caCert)
            )
            
            FileOutputStream(keyStoreFile).use { fos ->
                keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray())
            }
        }
        
        Log.d(TAG, "Certificate Manager initialized")
    }
    
    private fun generateCACertificate(): Pair<X509Certificate, PrivateKey> {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        val now = Date()
        val notBefore = Date(now.time - 86400000L) // 1 day before
        val notAfter = Date(now.time + 365L * 24 * 60 * 60 * 1000 * 10) // 10 years
        
        // Use simple common name, similar to Fiddler
        val issuer = X500Name("CN=RoRo Interceptor Root CA, O=RoRo Devs, OU=Development")
        val subject = issuer
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Add basic constraints to mark this as a CA certificate
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            org.bouncycastle.asn1.x509.BasicConstraints(true)
        )
        
        // Add key usage extensions (critical for CA)
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.keyCertSign or
                org.bouncycastle.asn1.x509.KeyUsage.cRLSign
            )
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.private)
        
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
        
        return Pair(cert, keyPair.private)
    }
    
    fun generateServerCertificate(hostname: String, originalCert: X509Certificate? = null): Pair<X509Certificate, PrivateKey> {
        Log.d(TAG, "🔐 Generating certificate for: $hostname")
        
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        val now = Date()
        val notBefore = Date(now.time - 86400000L)
        val notAfter = Date(now.time + 365L * 24 * 60 * 60 * 1000) // 1 year
        
        val issuer = X500Name(caCert.subjectX500Principal.name)
        
        // Use hostname in CN (Critical for certificate validation)
        val subject = X500Name("CN=$hostname")
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.currentTimeMillis() + SecureRandom().nextInt(100000)),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Add SAN (Subject Alternative Name) - CRITICAL for modern browsers
        val sanList = mutableListOf<GeneralName>()
        
        // Always add the primary hostname
        sanList.add(GeneralName(GeneralName.dNSName, hostname))
        
        // Add wildcard for subdomains
        if (hostname.contains(".")) {
            val parts = hostname.split(".")
            if (parts.size >= 2) {
                // For www.example.com -> add *.example.com
                val wildcard = "*." + parts.takeLast(2).joinToString(".")
                sanList.add(GeneralName(GeneralName.dNSName, wildcard))
            }
        }
        
        // Add IP address if hostname is an IP
        if (hostname.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            sanList.add(GeneralName(GeneralName.iPAddress, hostname))
        }
        
        val san = GeneralNames(sanList.toTypedArray())
        certBuilder.addExtension(Extension.subjectAlternativeName, false, san)
        
        // Add Extended Key Usage for TLS server authentication
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
            )
        )
        
        // Add Authority Key Identifier
        val authorityKeyIdentifier = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
            .createAuthorityKeyIdentifier(caCert.publicKey)
        certBuilder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            authorityKeyIdentifier
        )
        
        // Add Subject Key Identifier
        val subjectKeyIdentifier = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
            .createSubjectKeyIdentifier(keyPair.public)
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            subjectKeyIdentifier
        )
        
        // Add Key Usage
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.digitalSignature or
                org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment
            )
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(caPrivateKey)
        
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
        
        Log.d(TAG, "✅ Generated certificate for $hostname")
        Log.d(TAG, "   Subject: ${cert.subjectDN}")
        Log.d(TAG, "   Issuer: ${cert.issuerDN}")
        Log.d(TAG, "   SAN: ${sanList.joinToString { 
            when(it.tagNo) {
                GeneralName.dNSName -> "DNS:${it.name}"
                GeneralName.iPAddress -> "IP:${it.name}"
                else -> it.name.toString()
            }
        }}")
        
        return Pair(cert, keyPair.private)
    }
    
    fun exportCACertificate(): File {
        // Export to internal app storage in PEM format (plain text, no encryption)
        val certFile = File(context.filesDir, "RoRo_Interceptor_CA.crt")
        
        // Export as PEM format (same as Fiddler - no password required)
        val encoded = Base64.encodeToString(caCert.encoded, Base64.NO_WRAP)
        val pem = buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            // Split into 64-character lines as per PEM standard
            encoded.chunked(64).forEach { appendLine(it) }
            appendLine("-----END CERTIFICATE-----")
        }
        
        certFile.writeText(pem)
        
        Log.d(TAG, "CA certificate exported to: ${certFile.absolutePath}")
        return certFile
    }
    
    fun getCACertificateBytes(): ByteArray {
        // Return raw certificate bytes in DER format
        return caCert.encoded
    }
    
    fun exportCACertificateToPEM(): File {
        // Also export PEM format to Downloads for manual installation
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val certFile = File(downloadsDir, "RoRo_Interceptor_CA.pem")
        
        // Export as PEM format
        val encoded = Base64.encodeToString(caCert.encoded, Base64.NO_WRAP)
        val pem = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            encoded.chunked(64).forEach { line ->
                append(line)
                append("\n")
            }
            append("-----END CERTIFICATE-----\n")
        }
        
        FileWriter(certFile).use { writer ->
            writer.write(pem)
        }
        
        Log.d(TAG, "CA certificate (PEM) exported to: ${certFile.absolutePath}")
        return certFile
    }
    
    fun getCACertificate(): X509Certificate = caCert
    
    fun getCAPrivateKey(): PrivateKey = caPrivateKey
    
    // Export certificate in PEM format (plain text, like Fiddler)
    // ONLY CERTIFICATE, NO PRIVATE KEY - for CA installation
    fun exportCACertificatePEM(): ByteArray {
        val encoded = Base64.encodeToString(caCert.encoded, Base64.NO_WRAP)
        val pem = buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            encoded.chunked(64).forEach { appendLine(it) }
            appendLine("-----END CERTIFICATE-----")
        }
        return pem.toByteArray(Charsets.UTF_8)
    }
    
    // Export certificate in DER format (binary)
    // ONLY CERTIFICATE, NO PRIVATE KEY - recommended for Android CA installation
    fun exportCACertificateDER(): ByteArray {
        // Return only the certificate in DER format, no private key
        // This is what Android expects for CA installation
        return caCert.encoded
    }
    
    // Export certificate in CRT format (same as DER but with .crt extension)
    fun exportCACertificateCRT(): ByteArray {
        // CRT is just DER format with different extension
        return caCert.encoded
    }
    
    // Export certificate in CER format (same as DER but with .cer extension)
    fun exportCACertificateCER(): ByteArray {
        // CER is just DER format with different extension
        return caCert.encoded
    }
    
    fun isCertificateInstalled(): Boolean {
        return try {
            val trustManager = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManager.init(null as KeyStore?)
            
            val x509TrustManager = trustManager.trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .firstOrNull()
            
            if (x509TrustManager != null) {
                val acceptedIssuers = x509TrustManager.acceptedIssuers
                val isInstalled = acceptedIssuers.any { cert ->
                    cert.subjectDN.name.contains("RoRo Interceptor Root CA") ||
                    cert.issuerDN.name.contains("RoRo Interceptor Root CA")
                }
                Log.d(TAG, "Certificate installed: $isInstalled")
                isInstalled
            } else {
                Log.w(TAG, "No X509TrustManager found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking certificate installation", e)
            false
        }
    }
    
    fun shouldReinstallCertificate(): Boolean {
        if (!isCertificateInstalled()) {
            Log.d(TAG, "Certificate not installed, should reinstall")
            return true
        }
        
        // Check if certificate is about to expire (within 30 days)
        val thirtyDaysFromNow = Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
        return try {
            caCert.checkValidity(thirtyDaysFromNow)
            Log.d(TAG, "Certificate is valid, no need to reinstall")
            false
        } catch (e: Exception) {
            Log.d(TAG, "Certificate expiring soon or invalid, should reinstall")
            true
        }
    }
    
    companion object {
        private const val TAG = "CertificateManager"
        private const val KEYSTORE_PASSWORD = "httpinterceptor"
        private const val CA_ALIAS = "http_interceptor_ca"
    }
}
