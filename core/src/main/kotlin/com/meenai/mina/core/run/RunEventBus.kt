package com.meenai.mina.core.run

import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** 一次 Run 的事件分发器。记录、界面与送达可各订一份,单个接收方故障不影响其他接收方。 */
class RunEventBus : RunSink {
    private val listeners = CopyOnWriteArrayList<RunSink>()

    fun subscribe(listener: RunSink): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun event(e: RunEvent) {
        listeners.forEach { listener ->
            try { listener.event(e) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* a subscriber failure must not break the run */ }
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        listeners.forEach { listener ->
            try { listener.media(mediaId, png) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* same as above */ }
        }
    }
}
