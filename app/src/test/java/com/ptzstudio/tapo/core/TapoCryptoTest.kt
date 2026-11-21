package com.ptzstudio.tapo.core

import com.ptzstudio.tapo.core.TapoHash.hex
import com.ptzstudio.tapo.core.TapoHash.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Valores de referência gerados com a biblioteca pytapo (Python/pycryptodome) com as mesmas entradas. */
class TapoCryptoTest {
    private val pwd = "MinhaSenha#2024"
    private val cnonce = "A1B2C3D4E5F60718"
    private val nonce = "0F1E2D3C4B5A6978"

    @Test
    fun legacyLogin() {
        assertEquals(LegacyAuth.hashedSha256(pwd), LegacyAuth.matchDeviceConfirm(pwd, cnonce, nonce, "4DA57A2D09624627BDD66E34DDDFD20AD47005E41639B70A5C51948E5269E5E10F1E2D3C4B5A6978A1B2C3D4E5F60718"))
        assertEquals(LegacyAuth.hashedMd5(pwd), LegacyAuth.matchDeviceConfirm(pwd, cnonce, nonce, "707A1FF3C918DBE5A0CF0112474BB968C3CD891CE9C68F43F46D245CC86DCDFA0F1E2D3C4B5A6978A1B2C3D4E5F60718"))
        assertNull(LegacyAuth.matchDeviceConfirm("outra", cnonce, nonce, "707A1FF3C918DBE5A0CF0112474BB968C3CD891CE9C68F43F46D245CC86DCDFA0F1E2D3C4B5A6978A1B2C3D4E5F60718"))
        val h = LegacyAuth.hashedMd5(pwd)
        assertEquals("A54CEA7FE50B683AAC5C70961551C0F50EEBA8B4669BB4349B75C6072413FE9FA1B2C3D4E5F607180F1E2D3C4B5A6978", LegacyAuth.digestPasswd(h, cnonce, nonce))
        assertEquals("9c5591f7237c296b460098b761798a49", hex(LegacyAuth.token("lsk", h, cnonce, nonce)))
        assertEquals("7bb8f83f5aebe1976df8b9808e3c9877", hex(LegacyAuth.token("ivb", h, cnonce, nonce)))
        assertEquals("1AFBB488543A766BEA0B6EDF9FF8350885D0EAB3FE9DF0D851EDDD76D8AD6810", LegacyAuth.tag(h, cnonce, "{\"method\": \"securePassthrough\", \"params\": {\"request\": \"abc\"}}", 17))
    }

    @Test
    fun legacyCbc() {
        val key = unhex("9c5591f7237c296b460098b761798a49")
        val iv = unhex("7bb8f83f5aebe1976df8b9808e3c9877")
        val plain = "{\"method\":\"multipleRequest\",\"params\":{\"requests\":[]}}".toByteArray()
        assertEquals("bc985de1cc1f115ff8c5ca1ede49dc1867f4ed5433b549072d232eb0fbdbe9c9236daed7ff4c426b875be3b5689dc96b8016b9cb0e3a568d30440999dbb55472", hex(LegacyAuth.encrypt(key, iv, plain)))
        assertArrayEquals(plain, LegacyAuth.decrypt(key, iv, unhex("bc985de1cc1f115ff8c5ca1ede49dc1867f4ed5433b549072d232eb0fbdbe9c9236daed7ff4c426b875be3b5689dc96b8016b9cb0e3a568d30440999dbb55472")))
    }

    private fun msg(n: Int) = ByteArray(n) { ((it * 7 + 3) % 256).toByte() }

    @Test
    fun ccm() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (100 + it).toByte() }
        assertEquals("c6711098481d6b4c0eab3e31740c3182", hex(AesCcm.seal(key, nonce, msg(0))))
        assertArrayEquals(msg(0), AesCcm.open(key, nonce, unhex("c6711098481d6b4c0eab3e31740c3182")))
        assertEquals("a3438639a2bcd2675981c0b50ab13844884476ac06", hex(AesCcm.seal(key, nonce, msg(5))))
        assertArrayEquals(msg(5), AesCcm.open(key, nonce, unhex("a3438639a2bcd2675981c0b50ab13844884476ac06")))
        assertEquals("a3438639a23dd6d3ebbaf3b9ea9fc126df94857145157ad3ffc4017e768ed7a1", hex(AesCcm.seal(key, nonce, msg(16))))
        assertArrayEquals(msg(16), AesCcm.open(key, nonce, unhex("a3438639a23dd6d3ebbaf3b9ea9fc126df94857145157ad3ffc4017e768ed7a1")))
        assertEquals("a3438639a23dd6d3ebbaf3b9ea9fc1260d43157b0acb6f5287fb04b4dcabb82bf71afcb71e40be7a16d1a0328840bf5595", hex(AesCcm.seal(key, nonce, msg(33))))
        assertArrayEquals(msg(33), AesCcm.open(key, nonce, unhex("a3438639a23dd6d3ebbaf3b9ea9fc1260d43157b0acb6f5287fb04b4dcabb82bf71afcb71e40be7a16d1a0328840bf5595")))
        assertEquals("a3438639a23dd6d3ebbaf3b9ea9fc1260d43157b0acb6f5287fb04b4dcabb82bf74be068c12dbfbdcf76a218bcfe2c2f02bbe4871d110545742d9e0081563fded435697197c4a8c289c369b253fe88aba08fd5b9a48dd93ad82d392a39cada70d8c946344fa3117abd7bd54db81e12e2e79d19be019081771141565ea05f61cf344874ff3c9b9f7ef917f405d22d7913d2e064eb0360a12b13947676ba26039aac39c09698248d9ca7618b5f2b92e007aa2e4e12b8ff34ea6219a9b0d1383ec5156669a06f7e3859210ab4a9341a8516b3398cdab5b25922", hex(AesCcm.seal(key, nonce, msg(200))))
        assertArrayEquals(msg(200), AesCcm.open(key, nonce, unhex("a3438639a23dd6d3ebbaf3b9ea9fc1260d43157b0acb6f5287fb04b4dcabb82bf74be068c12dbfbdcf76a218bcfe2c2f02bbe4871d110545742d9e0081563fded435697197c4a8c289c369b253fe88aba08fd5b9a48dd93ad82d392a39cada70d8c946344fa3117abd7bd54db81e12e2e79d19be019081771141565ea05f61cf344874ff3c9b9f7ef917f405d22d7913d2e064eb0360a12b13947676ba26039aac39c09698248d9ca7618b5f2b92e007aa2e4e12b8ff34ea6219a9b0d1383ec5156669a06f7e3859210ab4a9341a8516b3398cdab5b25922")))
        val bad = unhex("a3438639a23dd6d3ebbaf3b9ea9fc1260d43157b0acb6f5287fb04b4dcabb82bf71afcb71e40be7a16d1a0328840bf5595")
        bad[3] = (bad[3].toInt() xor 1).toByte()
        assertNull(AesCcm.open(key, nonce, bad))
    }

    @Test
    fun sha256Crypt() {
        assertEquals("${"$"}5${"$"}abcdefgh${"$"}p0s3qQx81DKoazYZJK6XHt4GnS63PwrYAaf3odTudt9", ExtraCrypt.sha256Crypt("e10adc3949ba59abbe56e057f20f883e", "${"$"}5${"$"}abcdefgh${"$"}"))
        assertEquals("${"$"}5${"$"}rounds=1200${"$"}saltsaltsaltsalt${"$"}wJ9l.b1yBT1yNNik9qOjjoDzwscJaNQE1hBUvGBfBR5", ExtraCrypt.sha256Crypt("senha", "${"$"}5${"$"}rounds=1200${"$"}saltsaltsaltsaltEXTRA${"$"}"))
        assertEquals("${"$"}5${"$"}shortsalt${"$"}4SNO/idjOD0mk7MF6b7hMGninMhj6SUyE44vjDCt/M1", ExtraCrypt.sha256Crypt("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "shortsalt"))
        assertEquals(TapoHash.sha1Hex("abc"), ExtraCrypt.apply("abc", "password_shadow", 2, null))
        assertEquals("abc", ExtraCrypt.apply("abc", null, null, null))
        assertEquals("21232f297a57a5a743894a0e4a801fc3", TapoHash.md5Hex("admin"))
    }

    @Test
    fun spake2p() {
        val c = Spake2pClient(
            devSalt = TapoHash.unb64("AAECAwQFBgcICQoLDA0ODw=="),
            iterations = 1000,
            devShare = TapoHash.unb64("BMDT0mgZJKjNXTRI1XFye8gHdB7opl25UyIoR1C+iethIgwcp/obNgYp+xqdXFD4zPH/kG9JO6JpjShH/U/+J5U="),
            userRandom = TapoHash.unb64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
            devRandom = TapoHash.unb64("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="),
            credential = "${"$"}5${"$"}xyz${"$"}abc",
            randomScalar = BigInteger("987654321987654321987654321"),
        )
        assertEquals("040f1afb770a9db6546b64d34c2ed092e819071785ea61bffa7acdb5152f49736e1f35eb7df048ad459d98fc5d4b6d5c2654412e080199f883b842490e4a6362ee", hex(c.userShare))
        assertEquals("e37c158e85c10c1b43af2c0c7d36a96345b2d03eb5fca0c73d1718668ab86ee9", hex(c.userConfirm))
        assertTrue(c.deviceConfirmOk(unhex("29b734d8990f1055573ac0c2df9f1599976579833f6ccdbde5c9cb600dcd7e65")))
        assertFalse(c.deviceConfirmOk(ByteArray(32)))
        val (key, n) = c.sessionKeyAndNonce()
        assertEquals("9616cf669687ebbf8bd090f3ef6edd57", hex(key))
        assertEquals("d5244173cf471006baa0d454", hex(n))
    }
}
