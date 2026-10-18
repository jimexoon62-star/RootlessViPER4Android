package me.timschneeberger.rootlessjamesdsp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

abstract class BaseAudioProcessorService : Service() {
    private val binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: BaseAudioProcessorService
            get() = this@BaseAudioProcessorService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        instances[javaClass] = this
        activeServices++
        super.onCreate()
    }

    override fun onDestroy() {
        instances.remove(javaClass, this)
        activeServices--
        super.onDestroy()
    }

    /** Stop processing even when a UI still holds the service binder. */
    open fun requestStop() { stopSelf() }

    companion object {
        private val instances = java.util.concurrent.ConcurrentHashMap<Class<*>, BaseAudioProcessorService>()
        fun liveInstances(): List<BaseAudioProcessorService> = instances.values.toList()

        var activeServices: Int = 0
            private set
    }
}
