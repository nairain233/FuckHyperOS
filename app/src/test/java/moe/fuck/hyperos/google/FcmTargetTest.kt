package moe.fuck.hyperos.google

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FcmTargetTest {
    @Test fun explicitReceiverTakesPriorityOverPackageAndImplicitTargetIsNotBypassed() {
        val message = Intent(FcmLiveHook.RECEIVE).setPackage("app.package")
        assertEquals("app.package", FcmLiveHook.targetPackage(message))
        message.component = ComponentName("app.receiver", "app.receiver.PushReceiver")
        assertEquals("app.receiver", FcmLiveHook.targetPackage(message))
        assertNull(FcmLiveHook.targetPackage(Intent(FcmLiveHook.RECEIVE)))
    }
}
