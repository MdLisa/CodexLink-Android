package com.xukaizhe.codexlink

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The signing private key stays in Android Keystore and is never sent to the computer. */
object AndroidDeviceIdentity : DeviceIdentity {
    private const val ALIAS="codexlink_device_signing_v1"
    @Synchronized private fun store(): KeyStore {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!ks.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        return ks
    }
    override fun publicKey(): String = Base64.getEncoder().encodeToString(store().getCertificate(ALIAS).publicKey.encoded)
    override fun sign(bytes: ByteArray): String {
        val signature=Signature.getInstance("SHA256withECDSA");signature.initSign(store().getKey(ALIAS,null) as PrivateKey);signature.update(bytes)
        return Base64.getEncoder().encodeToString(signature.sign())
    }
}
