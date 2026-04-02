package cc.cicare.app

import cc.cicare.sdkcall.services.CiCareCallService
import org.junit.Test

import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCloseGuard


/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(RobolectricTestRunner::class) // Tambahkan ini
@Config(
    sdk = [34], // Paksa ke SDK 34 yang sudah stabil
    manifest = Config.NONE
)
class CiCareSDKUnitTest {
    @Before
    fun setup() {
        // Mematikan peringatan leak khusus saat testing

    }

    @Test
    fun `releaseWakeLock should not crash when called twice`() {
        val controller = Robolectric.buildService(CiCareCallService::class.java).create()
        val service = controller.get()

        service.forceStop()
        service.forceStop()
        service.onDestroy()
    }

    @Test
    fun `stopRingback should not crash`() {
        val controller = Robolectric.buildService(CiCareCallService::class.java).create()
        val service = controller.get()

        try {
            service.playRingback(RuntimeEnvironment.getApplication())
            service.stopRingback()
            service.stopRingback()
        } finally {
            // PENTING: Hancurkan service untuk melepas semua resource (CloseGuard)
            controller.destroy()
        }
    }
}