package com.floatclip.app

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.floatclip.app.overlay.ui.EntryGestureRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class EntryGestureRowTest {
    @Test fun reverseSwipeClosesBeforeOpeningOppositeActions() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_overlay", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val front = View(activity)
            val row = EntryGestureRow(activity)
            row.bind(front, View(activity), 148, View(activity), 78)
            activity.setContentView(row)
            row.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            row.layout(0, 0, 400, 80)
            var copies = 0
            row.onSingleTap = { copies++ }
            fun swipe(dx: Float, cancel: Boolean = false) {
                val down = SystemClock.uptimeMillis()
                fun event(action: Int, offset: Long, x: Float) {
                    MotionEvent.obtain(down, down + offset, action, x, 40f, 0).also {
                        row.onTouchEvent(it); it.recycle()
                    }
                }
                event(MotionEvent.ACTION_DOWN, 0, 200f)
                event(MotionEvent.ACTION_MOVE, 200, 200f + dx)
                event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 400, 200f + dx)
                shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            }
            swipe(100f)
            assertEquals(78f, front.translationX, 0.1f)
            swipe(-180f)
            assertEquals(0f, front.translationX, 0.1f)
            swipe(-160f)
            assertEquals(-148f, front.translationX, 0.1f)
            swipe(220f)
            assertEquals(0f, front.translationX, 0.1f)
            swipe(100f, cancel = true)
            assertEquals(0f, front.translationX, 0.1f)
            assertEquals("swipe and cancellation must not copy", 0, copies)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
