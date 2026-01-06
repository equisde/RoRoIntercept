package com.httpinterceptor.ui

import android.app.Activity
import android.os.Bundle
import android.security.KeyChain
import com.httpinterceptor.utils.CertificateManager

class CertInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val certBytes = CertificateManager(this).exportCACertificateDER()
            val intent = KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_NAME, "RoRo Interceptor Root CA")
                putExtra(KeyChain.EXTRA_CERTIFICATE, certBytes)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }

        finish()
    }
}
