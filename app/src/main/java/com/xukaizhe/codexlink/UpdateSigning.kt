@file:Suppress("DEPRECATION")

package com.xukaizhe.codexlink

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal fun updateSigningFlags():Int = if(Build.VERSION.SDK_INT>=28)
    PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
else PackageManager.GET_SIGNATURES

internal fun updateSigners(info:PackageInfo):Set<String> {
    // Prefer current signers; legacy fields are only a fallback when modern data is absent.
    // Never union these fields: a legacy/history certificate must not override a current signer.
    val modern=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else null
    val certificates=modern?.takeIf{it.isNotEmpty()}?:info.signatures.orEmpty()
    return certificates.map{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hexDigest()}.toSet()
}

internal fun readUpdatePackageInfo(read:(Int)->PackageInfo?):PackageInfo? {
    val flags=updateSigningFlags()
    val info=read(flags)
    if(info!=null&&updateSigners(info).isNotEmpty())return info
    // Some package-manager implementations only populate certificates with the legacy flag.
    // Retrying a read is allowed; a non-empty but mismatched signer is never retried/accepted.
    if(flags!=PackageManager.GET_SIGNATURES){
        val legacy=read(PackageManager.GET_SIGNATURES)
        if(legacy!=null&&updateSigners(legacy).isNotEmpty())return legacy
    }
    return info
}

internal fun ByteArray.hexDigest()=joinToString(""){"%02x".format(it.toInt() and 255)}
