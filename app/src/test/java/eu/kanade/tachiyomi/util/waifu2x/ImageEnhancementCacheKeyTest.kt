package eu.kanade.tachiyomi.util.waifu2x

import eu.kanade.tachiyomi.util.mediatek.MediaTekNpu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cache-key semantics: event matching and the config hash that decides which
 * enhanced entries remain valid when settings change.
 */
class ImageEnhancementCacheKeyTest {

    @Test
    fun savedEventsMatchOnlyTheirOwnCacheKey() {
        val event = ImageEnhancementCache.CacheEvent.Saved(
            ImageEnhancementCache.CacheKey(1L, 2L, 3, "cfg", "variant"),
        )

        assertTrue(event.affects(1L, 2L, 3, "cfg", "variant"))
        assertFalse(event.affects(9L, 2L, 3, "cfg", "variant"))
        assertFalse(event.affects(1L, 9L, 3, "cfg", "variant"))
        assertFalse(event.affects(1L, 2L, 9, "cfg", "variant"))
        assertFalse(event.affects(1L, 2L, 3, "other", "variant"))
        assertFalse(event.affects(1L, 2L, 3, "cfg", "other"))
    }

    @Test
    fun clearedEventsAffectEveryKey() {
        assertTrue(ImageEnhancementCache.CacheEvent.Cleared.affects(1L, 2L, 3, "cfg", "variant"))
        assertTrue(ImageEnhancementCache.CacheEvent.Cleared.affects(9L, 9L, 9, "", ""))
    }

    @Test
    fun configHashSeparatesBackendsAndPinsNpuFp16() {
        val vulkan = ImageEnhancementCache.getConfigHash(
            noise = 2,
            scale = 2,
            processingBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
        )
        val qualcomm = ImageEnhancementCache.getConfigHash(
            noise = 2,
            scale = 2,
            processingBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
        )
        val mediatek = ImageEnhancementCache.getConfigHash(
            noise = 2,
            scale = 2,
            processingBackend = Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU,
        )

        assertNotEquals(vulkan, qualcomm)
        assertNotEquals(qualcomm, mediatek)
        assertTrue(qualcomm.contains("_qv${Waifu2x.QNN_CONTEXT_CACHE_VERSION}_nf1"))
        assertTrue(mediatek.contains("_mtv${MediaTekNpu.CACHE_VERSION}"))
        assertFalse(vulkan.contains("_qv"))
        assertFalse(vulkan.contains("_mtv"))
    }

    @Test
    fun legacyInt8PrecisionResolvesToFp16InCacheKey() {
        val fp16 = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, precision = 0)
        val legacyInt8 = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, precision = 2)
        val fp32 = ImageEnhancementCache.getConfigHash(noise = 2, scale = 2, precision = 1)

        assertEquals(fp16, legacyInt8)
        assertNotEquals(fp16, fp32)
    }

    @Test
    fun textureGuardRevisionAppliesToProAndSeDenoise3Only() {
        val seNoDenoise = ImageEnhancementCache.getConfigHash(noise = 0, scale = 2, model = 0)
        val seDenoise3 = ImageEnhancementCache.getConfigHash(noise = 3, scale = 2, model = 0)
        val pro = ImageEnhancementCache.getConfigHash(noise = 0, scale = 2, model = 1)

        assertFalse(seNoDenoise.contains("_tv"))
        assertTrue(seDenoise3.contains("_tv"))
        assertTrue(pro.contains("_tv"))
    }

    @Test
    fun realEsrganPhotoGraphRevisionAppliesToPhotoStyleOnVulkanOnly() {
        val photoVulkan = ImageEnhancementCache.getConfigHash(
            noise = 0,
            scale = 2,
            model = Waifu2x.MODEL_REAL_ESRGAN_ANIME,
            realEsrganStyle = Waifu2x.REAL_ESRGAN_STYLE_PHOTO,
            processingBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
        )
        val animeStyleVulkan = ImageEnhancementCache.getConfigHash(
            noise = 0,
            scale = 2,
            model = Waifu2x.MODEL_REAL_ESRGAN_ANIME,
            realEsrganStyle = Waifu2x.REAL_ESRGAN_STYLE_ANIME,
            processingBackend = Waifu2x.PROCESSING_BACKEND_VULKAN,
        )
        val photoQualcomm = ImageEnhancementCache.getConfigHash(
            noise = 0,
            scale = 2,
            model = Waifu2x.MODEL_REAL_ESRGAN_ANIME,
            realEsrganStyle = Waifu2x.REAL_ESRGAN_STYLE_PHOTO,
            processingBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
        )
        val realCuganVulkan = ImageEnhancementCache.getConfigHash(noise = 0, scale = 2, model = 0)

        assertTrue(photoVulkan.contains("_rp2"))
        assertFalse(animeStyleVulkan.contains("_rp"))
        assertFalse(photoQualcomm.contains("_rp"))
        assertFalse(realCuganVulkan.contains("_rp"))
    }

    @Test
    fun fixedScaleModelsIgnoreTheScalePreference() {
        val fixedScaleModels = listOf(
            6, 8, Waifu2x.MODEL_W2XEX_PHOTO_SMALL, 16, 18, Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO, 3, 4, 5,
        )
        for (model in fixedScaleModels) {
            assertEquals(2, ImageEnhancementCache.getEffectiveScale(model, scale = 4), "model $model")
        }

        assertEquals(4, ImageEnhancementCache.getEffectiveScale(model = 0, scale = 4))
        assertEquals(
            2,
            ImageEnhancementCache.getEffectiveScale(
                Waifu2x.MODEL_REAL_ESRGAN_ANIME,
                scale = 4,
                realEsrganStyle = Waifu2x.REAL_ESRGAN_STYLE_PHOTO,
            ),
        )
        assertEquals(
            4,
            ImageEnhancementCache.getEffectiveScale(
                Waifu2x.MODEL_REAL_ESRGAN_ANIME,
                scale = 4,
                realEsrganStyle = Waifu2x.REAL_ESRGAN_STYLE_ANIME,
            ),
        )
    }
}
