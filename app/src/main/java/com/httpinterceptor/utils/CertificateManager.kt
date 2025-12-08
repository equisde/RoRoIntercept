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
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*

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
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val certFile = File(downloadsDir, "http_interceptor_ca.crt")
        
        FileOutputStream(certFile).use { fos ->
            fos.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
            fos.write(Base64.getEncoder().encode(caCert.encoded))
            fos.write("\n-----END CERTIFICATE-----\n".toByteArray())
        }
        
        Log.d(TAG, "CA certificate exported to: ${certFile.absolutePath}")
        return certFile
    }
    
    fun getCACertificate(): X509Certificate = caCert
    
    fun getCAPrivateKey(): PrivateKey = caPrivateKey
    
    companion object {
        private const val TAG = "CertificateManager"
        private const val KEYSTORE_PASSWORD = "httpinterceptor"
        private const val CA_ALIAS = "http_interceptor_ca"
    }
}
