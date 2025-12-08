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
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        val now = Date()
        val notBefore = Date(now.time - 86400000L) // 1 day before
        val notAfter = Date(now.time + 365L * 24 * 60 * 60 * 1000 * 10) // 10 years
        
        val issuer = X500Name("CN=HTTP Interceptor CA, O=HTTP Interceptor, C=US")
        val subject = issuer
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)
        
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .getCertificate(certHolder)
        
        return Pair(cert, keyPair.private)
    }
    
    fun generateServerCertificate(hostname: String): Pair<X509Certificate, PrivateKey> {
        val cachedAlias = "server_$hostname"
        
        if (keyStore.containsAlias(cachedAlias)) {
            val cert = keyStore.getCertificate(cachedAlias) as X509Certificate
            val key = keyStore.getKey(cachedAlias, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
            return Pair(cert, key)
        }
        
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        val now = Date()
        val notBefore = Date(now.time - 86400000L)
        val notAfter = Date(now.time + 365L * 24 * 60 * 60 * 1000) // 1 year
        
        val issuer = X500Name(caCert.subjectX500Principal.name)
        val subject = X500Name("CN=$hostname, O=HTTP Interceptor, C=US")
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Add SAN (Subject Alternative Name)
        val san = GeneralNames(arrayOf(
            GeneralName(GeneralName.dNSName, hostname),
            GeneralName(GeneralName.dNSName, "*.$hostname")
        ))
        certBuilder.addExtension(Extension.subjectAlternativeName, false, san)
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(caPrivateKey)
        
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .getCertificate(certHolder)
        
        // Cache the certificate
        keyStore.setKeyEntry(
            cachedAlias,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(cert, caCert)
        )
        
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
    fun exportCACertificateDER(): ByteArray {
        return caCert.encoded
    }
    
    // Export certificate in PKCS#12 format with empty password
    fun exportCACertificateP12(): ByteArray {
        val p12 = KeyStore.getInstance("PKCS12")
        p12.load(null, null)
        
        // Store CA certificate and private key with empty password
        p12.setKeyEntry(
            "RoRo Interceptor CA",
            caPrivateKey,
            CharArray(0), // Empty password
            arrayOf(caCert)
        )
        
        val outputStream = java.io.ByteArrayOutputStream()
        p12.store(outputStream, CharArray(0)) // Empty password
        return outputStream.toByteArray()
    }
    
    companion object {
        private const val TAG = "CertificateManager"
        private const val KEYSTORE_PASSWORD = "httpinterceptor"
        private const val CA_ALIAS = "http_interceptor_ca"
    }
}
