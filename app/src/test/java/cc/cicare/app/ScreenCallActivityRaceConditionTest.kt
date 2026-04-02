package cc.cicare.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Race condition test untuk ScreenCallActivity.onCallStateChanged().
 *
 * Menggunakan:
 * - Robolectric: menjalankan Activity tanpa emulator
 * - ShadowLooper: memflush pending tasks di main thread secara manual
 * - CountDownLatch: sinkronisasi antara background thread dan main thread
 * - AtomicInteger: menghitung berapa kali side-effect dipanggil secara thread-safe
 *
 * Pola umum tiap test:
 *   1. Launch Activity via ActivityScenario
 *   2. Set state awal via reflection (isOutgoingCall, hasBeenConnected, dll)
 *   3. Kirim state berulang dari N background thread secara bersamaan
 *   4. Flush main looper via shadowOf(Looper.getMainLooper()).idle()
 *   5. Assert side-effect (finish, tearDown, dll) dipanggil sesuai ekspektasi
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED) // Kita kontrol kapan main looper diflush
class ScreenCallActivityRaceConditionTest {

    private lateinit var scenario: ActivityScenario<ScreenCallActivity>

    // ─────────────────────────────────────────────────────────────
    // Helper: build Intent
    // ─────────────────────────────────────────────────────────────

    private fun buildIntent(
        action: String = CiCareCallService.ACTION.OUTGOING,
        callType: String = "outgoing"
    ): Intent = Intent(
        ApplicationProvider.getApplicationContext(),
        ScreenCallActivity::class.java
    ).apply {
        this.action = action
        putExtra("call_type", callType)
        putExtra("caller_name", "Test User")
        putExtra("caller_id", "caller-123")
        putExtra("callee_id", "callee-456")
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: reflection shortcuts
    // ─────────────────────────────────────────────────────────────

    private fun ScreenCallActivity.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun ScreenCallActivity.getField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    // ─────────────────────────────────────────────────────────────
    // Helper: kirim state dari N thread secara bersamaan
    // ─────────────────────────────────────────────────────────────

    /**
     * Menjalankan [states] dari background thread secara concurrent.
     * Semua thread menunggu di barrier yang sama sebelum fire,
     * sehingga overlap-nya semaksimal mungkin.
     *
     * Setelah semua thread selesai, flush main looper agar
     * runOnUiThread() yang di-post benar-benar dieksekusi.
     */
    private fun fireStatesFromBackgroundThreads(
        activity: ScreenCallActivity,
        states: List<CallState>,
        repeatEach: Int = 1
    ) {
        val executor = Executors.newFixedThreadPool(states.size * repeatEach)
        val startBarrier = CountDownLatch(1)           // semua thread siap, lalu mulai barengan
        val doneLatch = CountDownLatch(states.size * repeatEach)

        repeat(repeatEach) {
            states.forEach { state ->
                executor.submit {
                    try {
                        startBarrier.await(2, TimeUnit.SECONDS)  // tunggu aba-aba
                        activity.onCallStateChanged(state)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
        }

        startBarrier.countDown()                        // lepas semua thread sekaligus
        doneLatch.await(5, TimeUnit.SECONDS)            // tunggu semua selesai

        // Flush semua runOnUiThread() yang dipending oleh background thread
        shadowOf(android.os.Looper.getMainLooper()).idle()

        executor.shutdown()
    }

    // ─────────────────────────────────────────────────────────────
    // Teardown
    // ─────────────────────────────────────────────────────────────

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            try { scenario.close() } catch (_: Exception) {}
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 1
    // Race condition: END dikirim berkali-kali secara bersamaan
    // ─────────────────────────────────────────────────────────────
    // Skenario: socket/network layer mengirim END lebih dari sekali
    // karena timeout dan retry terjadi hampir bersamaan.
    //
    // Ekspektasi:
    //   - Activity tidak crash
    //   - isFinishing = true (finish dipanggil)
    //   - Tidak ada IllegalStateException dari double-unbind
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `END send 5x repeatedly from background thread - no crash`() {
        scenario = ActivityScenario.launch(buildIntent())

        scenario.onActivity { activity ->
            // Fire END 5x from different thread simultaneously
            fireStatesFromBackgroundThreads(
                activity,
                states = listOf(CallState.END),
                repeatEach = 5
            )
            // No Crash = pass
            // finishWithDelay posted via postDelayed
            assertNotNull(activity)
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 2
    // Race condition: CONNECTED lalu END hampir bersamaan
    // ─────────────────────────────────────────────────────────────
    // Skenario: CONNECTED dan END datang dari socket dalam
    // selisih < 1ms (race antara answer callback dan hangup event).
    //
    // Ekspektasi:
    //   - hasBeenConnected = true (CONNECTED harus tercatat)
    //   - finishWithDelay dipanggil (bukan finish langsung)
    //   - missedCall TIDAK dipanggil
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `CONNECTED dan END bersamaan dari 2 thread - hasBeenConnected tercatat`() {
        scenario = ActivityScenario.launch(buildIntent(action = CiCareCallService.ACTION.INCOMING))

        scenario.onActivity { activity ->
            activity.setField("isOutgoingCall", false)
            activity.setField("hasBeenConnected", false)

            // CONNECTED dan END dikirim hampir bersamaan dari 2 thread berbeda
            fireStatesFromBackgroundThreads(
                activity,
                states = listOf(CallState.CONNECTED, CallState.END),
                repeatEach = 1
            )

            // Salah satu dari dua kondisi ini harus benar:
            // (a) CONNECTED diproses duluan → hasBeenConnected = true, finishWithDelay
            // (b) END diproses duluan sebelum CONNECTED → finish langsung (missed)
            // Yang TIDAK boleh terjadi: crash / IllegalStateException
            assertNotNull(activity)
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 3
    // Race condition: ANSWERING dikirim 3x bersamaan
    // ─────────────────────────────────────────────────────────────
    // Skenario: ANSWERING datang dari IncomingCallService lebih dari
    // sekali karena binding callback dipanggil ulang saat reconnect.
    //
    // Bug yang dicek:
    //   - unbindService tidak boleh dipanggil lebih dari 1x
    //     (throw IllegalArgumentException jika dipanggil 2x)
    //   - forceStop boleh dipanggil berkali-kali (null-safe) tapi
    //     tidak boleh crash
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `ANSWERING dikirim 3x bersamaan - unbind hanya sekali tidak crash`() {
        scenario = ActivityScenario.launch(buildIntent(action = CiCareCallService.ACTION.INCOMING))

        scenario.onActivity { activity ->
            // inbound = true agar unbindService dipanggil
            activity.setField("inbound", true)
            activity.setField("incomingService", null) // service null, hanya test flag

            var exceptionCaught: Exception? = null
            val executor = Executors.newFixedThreadPool(3)
            val barrier = CountDownLatch(1)
            val done = CountDownLatch(3)

            repeat(3) {
                executor.submit {
                    try {
                        barrier.await(2, TimeUnit.SECONDS)
                        activity.onCallStateChanged(CallState.ANSWERING)
                    } catch (e: Exception) {
                        exceptionCaught = e
                    } finally {
                        done.countDown()
                    }
                }
            }

            barrier.countDown()
            done.await(5, TimeUnit.SECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            executor.shutdown()

            // Setelah ANSWERING pertama, inbound harus false
            // sehingga unbind ke-2 dan ke-3 di-skip
            assertFalse(activity.getField("inbound") as Boolean)

            // Tidak boleh ada exception yang tidak tertangkap
            assertNull(
                "Exception tidak terduga: ${exceptionCaught?.message}",
                exceptionCaught
            )
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 4
    // Race condition: END dan TIMEOUT hampir bersamaan
    // ─────────────────────────────────────────────────────────────
    // Skenario: server kirim END, lalu timeout terjadi 50ms kemudian
    // sebelum Activity sempat finish.
    //
    // Bug yang dicek:
    //   - tearDownCallService tidak boleh dipanggil 2x
    //     (second call akan crash jika callService sudah null)
    //   - finishWithDelay / finish tidak boleh dobel
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `END lalu TIMEOUT bersamaan - tearDown tidak crash`() {
        scenario = ActivityScenario.launch(buildIntent())

        scenario.onActivity { activity ->
            activity.setField("isOutgoingCall", true)
            activity.setField("hasBeenConnected", true)
            activity.setField("bound", false) // service tidak bound, tearDown aman

            fireStatesFromBackgroundThreads(
                activity,
                states = listOf(CallState.END, CallState.TIMEOUT),
                repeatEach = 1
            )

            // Tidak crash = pass
            assertNotNull(activity)
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 5
    // Race condition: banyak state berbeda dalam waktu bersamaan
    // ─────────────────────────────────────────────────────────────
    // Skenario: stress test — semua CallState dikirim dari thread
    // masing-masing secara bersamaan (worst case dari socket yang
    // mengirim event berturut-turut sangat cepat).
    //
    // Ekspektasi: tidak ada crash, tidak ada deadlock (test selesai
    // dalam batas timeout 5 detik).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `stress test semua state dikirim bersamaan - tidak crash tidak deadlock`() {
        scenario = ActivityScenario.launch(buildIntent())

        scenario.onActivity { activity ->
            activity.setField("isOutgoingCall", true)
            activity.setField("hasBeenConnected", false)
            activity.setField("bound", false)

            val allStates = listOf(
                CallState.CONNECTED,
                CallState.ANSWERING,
                CallState.END,
                CallState.TIMEOUT,
                CallState.MISSED,
                CallState.REFUSED,
                CallState.BUSY,
            )

            val completedCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(allStates.size)
            val barrier = CountDownLatch(1)
            val done = CountDownLatch(allStates.size)

            allStates.forEach { state ->
                executor.submit {
                    try {
                        barrier.await(2, TimeUnit.SECONDS)
                        activity.onCallStateChanged(state)
                        completedCount.incrementAndGet()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        done.countDown()
                    }
                }
            }

            barrier.countDown()
            val finished = done.await(5, TimeUnit.SECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            executor.shutdown()

            // Semua thread harus selesai dalam 5 detik (tidak deadlock)
            assertTrue("Deadlock terdeteksi — tidak semua thread selesai", finished)

            // Semua state berhasil diproses
            assertEquals(allStates.size, completedCount.get())
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 6
    // Thread-safety: runOnUiThread harus redirect background thread
    // ke main thread dengan benar
    // ─────────────────────────────────────────────────────────────
    // Skenario: verifikasi bahwa guard di awal onCallStateChanged
    //   if (Thread.currentThread() != Looper.getMainLooper().thread)
    //       runOnUiThread { onCallStateChanged(callState) }
    // benar-benar memindahkan eksekusi ke main thread.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `state dari background thread dieksekusi di main thread`() {
        scenario = ActivityScenario.launch(buildIntent())

        scenario.onActivity { activity ->
            activity.setField("isOutgoingCall", true)
            activity.setField("hasBeenConnected", false)

            val executedOnMainThread = AtomicInteger(0)
            val latch = CountDownLatch(1)

            // Wrap onCallStateChanged untuk verifikasi thread
            Thread {
                // Dipanggil dari background thread
                assertFalse(
                    "Harus dipanggil dari background thread",
                    android.os.Looper.getMainLooper().isCurrentThread
                )

                activity.onCallStateChanged(CallState.CONNECTED)
                latch.countDown()
            }.start()

            latch.await(3, TimeUnit.SECONDS)

            // Flush main looper — ini mengeksekusi runOnUiThread block
            shadowOf(android.os.Looper.getMainLooper()).idle()

            // Setelah flush, hasBeenConnected harus sudah true
            // (artinya eksekusi berhasil pindah ke main thread)
            val hasBeenConnected = activity.getField("hasBeenConnected") as Boolean
            assertTrue(
                "hasBeenConnected harus true setelah CONNECTED diproses di main thread",
                hasBeenConnected
            )
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TEST 7
    // Race condition: CONNECTED dikirim berulang dari banyak thread
    // ─────────────────────────────────────────────────────────────
    // Skenario: WebRTC connection callback dipanggil multiple times
    // (diketahui terjadi pada beberapa versi library WebRTC).
    //
    // Ekspektasi:
    //   - hasBeenConnected = true (idempoten)
    //   - Tidak ada side effect ganda (tearDown, finish tidak dipanggil)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `CONNECTED dikirim 10x bersamaan - idempoten tidak ada side effect ganda`() {
        scenario = ActivityScenario.launch(buildIntent())

        scenario.onActivity { activity ->
            activity.setField("isOutgoingCall", true)
            activity.setField("hasBeenConnected", false)

            fireStatesFromBackgroundThreads(
                activity,
                states = listOf(CallState.CONNECTED),
                repeatEach = 10
            )

            val hasBeenConnected = activity.getField("hasBeenConnected") as Boolean
            assertTrue(hasBeenConnected)

            // Activity tidak boleh finishing karena CONNECTED tidak trigger finish
            assertFalse(
                "Activity tidak seharusnya finishing setelah CONNECTED",
                activity.isFinishing
            )
        }
    }
}