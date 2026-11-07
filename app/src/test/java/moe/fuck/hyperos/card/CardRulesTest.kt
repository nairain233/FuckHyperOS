package moe.fuck.hyperos.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class CardRulesTest {
    private val bindings = listOf(
        CardBinding("door-a", "a.png", setOf("https://host/door/shared.png")),
        CardBinding("door-b", "b.png", setOf("https://host/door/shared.png")),
        CardBinding("bus", "c.png", setOf("https://host/bus/card.png")),
    )

    @Test fun islandDoorUsesAidEvenWhenItsOriginalArtDiffers() {
        assertEquals("a.png", selectCardImage("door-a", "https://other/island.png", bindings))
        assertEquals("b.png", selectCardImage("door-b", null, bindings))
    }

    @Test fun anUnconfiguredCardNeverInheritsAnotherCardsArt() {
        assertNull(selectCardImage("unconfigured", "https://host/door/shared.png", bindings))
    }

    @Test fun sharedUrlDoesNotSelectAnArbitraryDoor() {
        assertNull(selectCardImage(null, "https://host/door/shared.png", bindings))
    }

    @Test fun walletLoadsExactUrlOrUniqueFilenameAlias() {
        assertEquals("c.png", selectCardImage(null, "https://host/bus/card.png", bindings))
        assertEquals("c.png", selectCardImage(null, "https://cdn/bus/card.png?v=2#fragment", bindings))
    }

    @Test fun collidingFilenameAliasesDoNotSelectAnArbitraryCard() {
        val colliding = bindings + CardBinding("other", "d.png", setOf("https://another/card.png"))
        assertNull(selectCardImage(null, "https://cdn/card.png", colliding))
        assertEquals("c.png", selectCardImage(null, "https://host/bus/card.png", colliding))
    }

    @Test fun multipleCardsCanExplicitlyShareOneCustomImage() {
        val shared = bindings.map { it.copy(image = "same.png") }
        assertEquals("same.png", selectCardImage(null, "https://host/door/shared.png", shared))
    }

    @Test fun unconfiguredCardsAlsoPreventSharedUrlAliases() {
        val first = WalletCard("first", "门卡 1", "DOOR_CARD", setOf("https://host/shared.png", "https://host/unique.png"))
        val second = WalletCard("second", "门卡 2", "DOOR_CARD", setOf("https://cdn/shared.png"))
        assertEquals(setOf("https://host/unique.png"), unsharedCardUrls(first, listOf(first, second)))
    }

    @Test fun missingAndBlankUrlsAreNotMatched() {
        assertNull(selectCardImage(null, null, bindings))
        assertNull(selectCardImage(null, "", bindings))
        assertNull(cardUrlName("aid"))
        assertNull(cardUrlName("https://host/"))
    }

    @Test fun onlyGeneratedPngNamesCanAccessTheImageDirectory() {
        assertTrue(validCardImageName("a".repeat(32) + ".png"))
        listOf("../secret", "/data/secret", "a.png", "a".repeat(32) + ".png/../secret",
            "a".repeat(32) + ".png.tmp", "A".repeat(32) + ".png").forEach { assertFalse(validCardImageName(it)) }
    }

    @Test fun imageTransferPreservesAllBytes() {
        val bytes = ByteArray(100_001) { (it % 255).toByte() }
        val output = ByteArrayOutputStream()
        copyCardImage(ByteArrayInputStream(bytes), output)
        assertTrue(bytes.contentEquals(output.toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyImageIsRejected() { copyCardImage(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()) }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedTransferIsStoppedWithoutLoadingItAllIntoMemory() {
        val endless = object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
        }
        copyCardImage(endless, object : java.io.OutputStream() { override fun write(value: Int) = Unit })
    }
}
