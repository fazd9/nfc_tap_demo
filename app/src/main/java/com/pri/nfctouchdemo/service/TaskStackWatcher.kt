package com.pri.nfctouchdemo.service

import android.os.Binder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

//pri added by zhuyang,Change 2: same job as LongShotScreen TaskStackModel - tell the overlay the top task changed,20261014,start
//pri added by zhuyang,TaskStackListener / ActivityTaskManager / ITaskStackListener are @hide and absent from the public SDK we compile against,20261014
//pri added by zhuyang,so the listener is a Proxy (type token + asBinder) over a Binder whose onTransact dispatches the callbacks,20261014
//pri added by zhuyang,registerTaskStackListener itself only needs MANAGE_ACTIVITY_TASKS, which uid 1000 passes without declaring it,20261014
class TaskStackWatcher(private val onTopTaskChanged: () -> Unit) : Binder() {

    //pri added by zhuyang,asBinder() is the one method the framework calls on the proxy: it must hand back this Binder,20261014
    private val handler = InvocationHandler { _, method, args ->
        when {
            method.name == "asBinder" -> this
            method.name == "toString" -> "TaskStackWatcher"
            method.name == "hashCode" -> System.identityHashCode(this)
            method.name == "equals" -> args?.firstOrNull() === this
            else -> null
        }
    }

    private var unregisterAction: (() -> Unit)? = null
    private var movedToFrontTransaction = -1
    private var focusChangedTransaction = -1

    //pri added by zhuyang,The two callbacks that mean "top task changed": moved to front, and focus gained,20261014,start
    //pri added by zhuyang,Runs on a binder thread - the callback is marshalled to the main thread by the service,20261014
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            movedToFrontTransaction -> onTopTaskChanged()
            focusChangedTransaction -> {
                data.enforceInterface(DESCRIPTOR)
                data.readInt()
                if (data.readInt() != 0) onTopTaskChanged()
            }
        }
        return true
    }
    //pri added by zhuyang,The two callbacks that mean "top task changed": moved to front, and focus gained,20261014,end

    //pri added by zhuyang,Registered from OverlayService.onCreate; a refusal is logged and never fatal - the overlay just stays,20261014
    fun register() {
        if (unregisterAction != null) return
        runCatching {
            val iface = Class.forName("android.app.ITaskStackListener")
            val stub = Class.forName("android.app.ITaskStackListener\$Stub")
            movedToFrontTransaction = stub.transaction("onTaskMovedToFront")
            focusChangedTransaction = stub.transaction("onTaskFocusChanged")
            val service = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)!!
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
            //pri modify by zhuyang,register/unregisterTaskStackListener are declared on IActivityTaskManager (the interface getService returns), not on ITaskStackListener,20261014,start
            val iatm = Class.forName("android.app.IActivityTaskManager")
            iatm.getMethod("registerTaskStackListener", iface).invoke(service, proxy)
            unregisterAction = {
                iatm.getMethod("unregisterTaskStackListener", iface).invoke(service, proxy)
            }
            //pri modify by zhuyang,register/unregisterTaskStackListener are declared on IActivityTaskManager (the interface getService returns), not on ITaskStackListener,20261014,end
        }.onFailure { Log.e(TAG, "注册顶层任务监听失败", it) }
    }

    //pri added by zhuyang,Unregistered from OverlayService.onDestroy: a listener bound to a dead service must not survive it,20261014
    fun unregister() {
        val action = unregisterAction ?: return
        unregisterAction = null
        runCatching { action() }.onFailure { Log.e(TAG, "注销顶层任务监听失败", it) }
    }

    //pri added by zhuyang,Transaction codes come from the ROM's own Stub, so they always match what the system calls,20261014
    private fun Class<*>.transaction(method: String): Int =
        getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)

    private companion object {
        const val TAG = "TaskStackWatcher"
        const val DESCRIPTOR = "android.app.ITaskStackListener"
    }
}
//pri added by zhuyang,Change 2: same job as LongShotScreen TaskStackModel - tell the overlay the top task changed,20261014,end
