package com.xukaizhe.codexlink

/** JVM substitute for the hardware key store; vault bytes still use real AES/GCM encryption. */
class TestVaultStore:java.security.KeyStoreSpi(){
    override fun engineGetKey(alias:String?,password:CharArray?):java.security.Key=javax.crypto.spec.SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")
    override fun engineGetCertificateChain(alias:String?):Array<java.security.cert.Certificate>?=null
    override fun engineGetCertificate(alias:String?):java.security.cert.Certificate?=null
    override fun engineGetCreationDate(alias:String?)=java.util.Date(0)
    override fun engineSetKeyEntry(alias:String?,key:java.security.Key?,password:CharArray?,chain:Array<out java.security.cert.Certificate>?)=error("unused")
    override fun engineSetKeyEntry(alias:String?,key:ByteArray?,chain:Array<out java.security.cert.Certificate>?)=error("unused")
    override fun engineSetCertificateEntry(alias:String?,cert:java.security.cert.Certificate?)=error("unused")
    override fun engineDeleteEntry(alias:String?)=Unit
    override fun engineAliases():java.util.Enumeration<String> = java.util.Collections.enumeration(listOf("codexlink_connection_v1"))
    override fun engineContainsAlias(alias:String?)=true
    override fun engineSize()=1
    override fun engineIsKeyEntry(alias:String?)=true
    override fun engineIsCertificateEntry(alias:String?)=false
    override fun engineGetCertificateAlias(cert:java.security.cert.Certificate?):String?=null
    override fun engineStore(stream:java.io.OutputStream?,password:CharArray?)=Unit
    override fun engineLoad(stream:java.io.InputStream?,password:CharArray?)=Unit
}
