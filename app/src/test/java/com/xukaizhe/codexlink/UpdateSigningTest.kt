@file:Suppress("DEPRECATION")

package com.xukaizhe.codexlink

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UpdateSigningTest {
    private fun legacy(vararg values:String)=PackageInfo().apply{signatures=values.map{Signature(it.toByteArray())}.toTypedArray()}
    @Test fun missingModernInformationUsesPopulatedLegacyCertificates(){
        val info=legacy("original-certificate")
        assertNull(info.signingInfo)
        assertEquals(setOf(MessageDigest.getInstance("SHA-256").digest("original-certificate".toByteArray()).hexDigest()),updateSigners(info))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,updateSigningFlags())
    }
    @Test fun retriesLegacyOnlyWhenCombinedQueryHasNoCertificates(){
        val reads=mutableListOf<Int>();val empty=PackageInfo();val signed=legacy("original-certificate")
        val result=readUpdatePackageInfo{flags->reads+=flags;if(flags==PackageManager.GET_SIGNATURES)signed else empty}
        assertSame(signed,result);assertEquals(listOf(updateSigningFlags(),PackageManager.GET_SIGNATURES),reads)
    }
    @Test fun doesNotReplaceAnyExistingSignerWithAnotherRead(){
        val reads=mutableListOf<Int>();val original=legacy("different-certificate")
        val result=readUpdatePackageInfo{flags->reads+=flags;if(flags==PackageManager.GET_SIGNATURES)legacy("trusted") else original}
        assertSame(original,result);assertEquals(listOf(updateSigningFlags()),reads)
    }
    @Test fun modernCurrentSignerAlwaysTakesPriorityOverLegacyOrHistoricalCertificates(){
        val modern=Shadow.newInstanceOf(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(modern).apply{
            setSignatures(arrayOf(Signature("current".toByteArray())))
            setPastSigningCertificates(arrayOf(Signature("old".toByteArray())))
        }
        val info=legacy("old").apply{signingInfo=modern}
        assertEquals(setOf(MessageDigest.getInstance("SHA-256").digest("current".toByteArray()).hexDigest()),updateSigners(info))
        assertNotEquals(updateSigners(legacy("old")),updateSigners(info))
    }
    @Test fun missingSignaturesRemainMissingAndCannotPassTheIdentityCheck(){
        val info=readUpdatePackageInfo{PackageInfo()}!!;assertTrue(updateSigners(info).isEmpty())
        assertNull(readUpdatePackageInfo{null})
    }
    @Test @Config(sdk=[26]) fun androidEightUsesLegacyQueryWithoutDuplicatingIt(){
        val reads=mutableListOf<Int>();val info=legacy("original-certificate")
        assertSame(info,readUpdatePackageInfo{flags->reads+=flags;info})
        assertEquals(listOf(PackageManager.GET_SIGNATURES),reads);assertEquals(1,updateSigners(info).size)
    }
}
