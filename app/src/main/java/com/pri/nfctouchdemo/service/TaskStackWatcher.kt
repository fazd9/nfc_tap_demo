package com.pri.nfctouchdemo.service

import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.util.Log

//pri added by zhuyang,Change 2: same job as LongShotScreen TaskStackModel - tell the overlay the top task changed,20261014,start
//pri added by zhuyang,TaskStackListener / ActivityTaskManager are @hide and absent from the public SDK, so they come from the in-house,20261014
//pri added by zhuyang,systemlibs/framework-header.jar (compileOnly); a real ITaskStackListener.Stub subclass is what the system calls back,20261014
class TaskStackWatcher(private val onTopTaskChanged: () -> Unit) : TaskStackListener() {

    //pri added by zhuyang,register / unregister both run on the main thread (service onCreate / onDestroy): no synchronization needed,20261014
    private var registered = false

    //pri added by zhuyang,Live trigger, as in the reference: the trailing onTopTaskChanged() of onTaskMovedToFront is commented out there,20261014
    //pri added by zhuyang,Fired on a binder thread - the callback is marshalled to the main thread by the service,20261014
    override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {
        //pri added by zhuyang,Log the delivery itself at INFO: this ROM's default log level is INFO, Log.d is invisible here,20261014
        Log.i(TAG, "顶层任务焦点变化：taskId=$taskId, focused=$focused")
        if (focused) onTopTaskChanged()
    }

    //pri added by zhuyang,Registered from OverlayService.onCreate; a refusal is logged and never fatal - the overlay just stays,20261014
    fun register() {
        if (registered) return
        runCatching { ActivityTaskManager.getService().registerTaskStackListener(this) }
            .onSuccess {
                registered = true
                Log.i(TAG, "注册顶层任务监听成功")
            }
            .onFailure { Log.e(TAG, "注册顶层任务监听失败", it) }
    }

    //pri added by zhuyang,Unregistered from OverlayService.onDestroy, as the reference's release() does,20261014
    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { ActivityTaskManager.getService().unregisterTaskStackListener(this) }
            .onFailure { Log.e(TAG, "注销顶层任务监听失败", it) }
    }

    private companion object {
        const val TAG = "TaskStackWatcher"
    }
}
//pri added by zhuyang,Change 2: same job as LongShotScreen TaskStackModel - tell the overlay the top task changed,20261014,end
