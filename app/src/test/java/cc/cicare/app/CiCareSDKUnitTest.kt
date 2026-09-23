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


import android.net.wifi.WifiManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

@Implements(WifiManager.WifiLock::class)
class ShadowWifiLockWithLimit : ShadowWifiManager.ShadowWifiLock() {
    companion object {
        var activeCount = 0
        fun reset() {
            activeCount = 0
        }
    }

    @Implementation
    override fun acquire() {
        if (activeCount >= 50) {
            throw UnsupportedOperationException("Exceeded maximum number of wifi locks")
        }
        activeCount++
        super.acquire()
    }

    @Implementation
    override fun release() {
        if (activeCount > 0) activeCount--
        super.release()
    }
}

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(RobolectricTestRunner::class) // Tambahkan ini
@Config(
    sdk = [34], // Paksa ke SDK 34 yang sudah stabil
    manifest = Config.NONE,
    shadows = [ShadowWifiLockWithLimit::class]
)
class CiCareSDKUnitTest {
    @Before
    fun setup() {
        ShadowWifiLockWithLimit.reset()
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

    @Test
    fun `repeated service creation and stop should not exceed wifi locks or crash`() {
        // Simulasi 60 siklus start dan stop service (batas OS adalah 50 locks)
        repeat(60) {
            val controller = Robolectric.buildService(CiCareCallService::class.java).create()
            val service = controller.get()
            service.forceStop()
            controller.destroy()
        }
    }

    @Test
    fun `service onCreate should not crash even when wifi locks limit is reached in OS`() {
        // Simulasi OS di HP sudah memegang 50 wifi locks (kuota limit tercapai)
        ShadowWifiLockWithLimit.activeCount = 50

        // Start service — dengan kode baru, try-catch akan menangani UnsupportedOperationException dengan aman
        val controller = Robolectric.buildService(CiCareCallService::class.java).create()
        val service = controller.get()
        assertNotNull(service)

        service.forceStop()
        controller.destroy()
    }
}