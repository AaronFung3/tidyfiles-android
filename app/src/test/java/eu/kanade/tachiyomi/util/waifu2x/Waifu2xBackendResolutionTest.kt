package eu.kanade.tachiyomi.util.waifu2x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NPU routing: which model/scale combinations have bundled NPU assets, the
 * model names they map to, and how backend/precision preferences resolve.
 * The names here must stay in sync with assets/qnn-models and litert-models.
 */
class Waifu2xBackendResolutionTest {

    @Test
    fun npuModelSupportMatrix() {
        // Real-CUGAN Pro is the only model with bundled 3x NPU assets.
        assertTrue(Waifu2x.isQualcommNpuModelSupported(model = 1, scale = 2))
        assertTrue(Waifu2x.isQualcommNpuModelSupported(model = 1, scale = 3))
        assertFalse(Waifu2x.isQualcommNpuModelSupported(model = 1, scale = 4))

        // Everything else ships 2x only.
        for (model in listOf(0, Waifu2x.MODEL_REAL_ESRGAN_ANIME, Waifu2x.MODEL_W2XEX_PHOTO_SMALL, Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO)) {
            assertTrue(Waifu2x.isQualcommNpuModelSupported(model, scale = 2), "model $model")
            assertFalse(Waifu2x.isQualcommNpuModelSupported(model, scale = 3), "model $model")
        }

        // Vulkan-only models never route to the NPU.
        for (model in listOf(3, 4, 5, 6, 8, 16, 18)) {
            assertFalse(Waifu2x.isQualcommNpuModelSupported(model, scale = 2), "model $model")
        }
    }

    @Test
    fun qnnModelNamesMatchBundledAssets() {
        assertEquals("realcugan-pro-x2-denoise3x", Waifu2x.qnnModelName(1, 2, noise = 2))
        assertEquals("realcugan-pro-x2-denoise3x", Waifu2x.qnnModelName(1, 2, noise = 1))
        assertEquals("realcugan-pro-x2-conservative", Waifu2x.qnnModelName(1, 2, noise = 4))
        assertEquals("realcugan-pro-x2-no-denoise", Waifu2x.qnnModelName(1, 2, noise = 0))
        assertEquals("realcugan-pro-x3-denoise3x", Waifu2x.qnnModelName(1, 3, noise = 3))
        assertEquals("realcugan-se-x2-denoise1x", Waifu2x.qnnModelName(0, 2, noise = 1))
        assertEquals("realcugan-se-x2-no-denoise", Waifu2x.qnnModelName(0, 2, noise = 0))
        assertEquals(
            "realesrgan-animevideov3-x2",
            Waifu2x.qnnModelName(Waifu2x.MODEL_REAL_ESRGAN_ANIME, 2, noise = 0),
        )
        assertEquals(
            "realesrgan-general-x4v3-x2",
            Waifu2x.qnnModelName(
                Waifu2x.MODEL_REAL_ESRGAN_ANIME,
                2,
                noise = 0,
                realEsrganStyle = Waifu2x.REAL_ESRGAN_STYLE_PHOTO,
            ),
        )
        assertEquals("w2xex-photo-small-x2", Waifu2x.qnnModelName(Waifu2x.MODEL_W2XEX_PHOTO_SMALL, 2, noise = 0))
        assertEquals("span-nomosuni-x2", Waifu2x.qnnModelName(Waifu2x.MODEL_SPAN_NOMOSUNI_PHOTO, 2, noise = 0))

        // No bundled assets: SE 3x and Vulkan-only models.
        assertNull(Waifu2x.qnnModelName(0, 3, noise = 2))
        assertNull(Waifu2x.qnnModelName(3, 2, noise = 0))
    }

    @Test
    fun backendResolutionPreservesExplicitNpuChoicesAndRejectsUnknowns() {
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, Waifu2x.resolveProcessingBackend(0, model = 0, scale = 2))
        // An explicit NPU choice survives even for models the NPU cannot run;
        // the decoder then fails enhancement and keeps the original image.
        assertEquals(
            Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
            Waifu2x.resolveProcessingBackend(1, model = 4, scale = 2),
        )
        assertEquals(
            Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU,
            Waifu2x.resolveProcessingBackend(2, model = 4, scale = 2),
        )
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, Waifu2x.resolveProcessingBackend(9, model = 0, scale = 2))
        assertEquals(Waifu2x.PROCESSING_BACKEND_VULKAN, Waifu2x.resolveProcessingBackend(-1, model = 0, scale = 2))
    }

    @Test
    fun precisionIsForcedToFp16OnNpuBackends() {
        for (backend in listOf(Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU, Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU)) {
            assertEquals(0, Waifu2x.resolvePrecision(requestedPrecision = 1, processingBackend = backend, model = 0, scale = 2))
        }

        assertEquals(1, Waifu2x.resolvePrecision(1, Waifu2x.PROCESSING_BACKEND_VULKAN, 0, 2))
        assertEquals(3, Waifu2x.resolvePrecision(3, Waifu2x.PROCESSING_BACKEND_VULKAN, 0, 2))
        assertEquals(0, Waifu2x.resolvePrecision(2, Waifu2x.PROCESSING_BACKEND_VULKAN, 0, 2))
        assertEquals(0, Waifu2x.resolvePrecision(9, Waifu2x.PROCESSING_BACKEND_VULKAN, 0, 2))
    }
}
