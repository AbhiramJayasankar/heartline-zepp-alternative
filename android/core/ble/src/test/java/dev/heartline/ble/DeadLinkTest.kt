package dev.heartline.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadLinkTest {
    @Test
    fun threeRequestsInARowThatCantStartMeanTheLinkIsDead() {
        val d = DeadLink()
        assertFalse(d.started(false)); assertFalse(d.started(false))
        assertTrue(d.started(false))
    }

    @Test
    fun aRequestThatStartsResetsTheCount() {
        val d = DeadLink()
        assertFalse(d.started(false)); assertFalse(d.started(false)); assertFalse(d.started(true))
        assertFalse(d.started(false)); assertFalse(d.started(false))
    }

    @Test
    fun aDeadBinderIsSeenThroughItsCauses() {
        // As Android 13+ rethrows it from the Bluetooth service, and as the app's own error messages carried it.
        assertTrue(DeadLink.isDeadBinder(RuntimeException(IllegalStateException("android.os.DeadObjectException"))))
        assertFalse(DeadLink.isDeadBinder(IllegalStateException("Local notification registration failed")))
        assertFalse(DeadLink.isDeadBinder(java.io.IOException("GATT write failed: 133")))
    }
}
