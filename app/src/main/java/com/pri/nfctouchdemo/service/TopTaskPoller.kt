package com.pri.nfctouchdemo.service

import android.app.ActivityManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

//pri added by zhuyang,Change 2 replacement: the ITaskStackListener Proxy over a Binder registers fine on this ROM but the system never calls it back,20261014,start
//pri added by zhuyang,(device-verified: the registration log prints and no onTransact ever arrives across a Home press),20261014
//pri added by zhuyang,so the top task is polled instead - the primitive the in-house reference keeps as its fallback (ActivityTaskManager.getTasks),20261014
//pri added by zhuyang,Polling runs only between start() and stop(), i.e. only while the page is attached: nothing is polled while nothing is showing,20261014
class TopTaskPoller(private val onTopTaskChanged: () -> Unit) {

    //pri added by zhuyang,Written on the main thread (start / stop), read by the poller thread: volatile is the whole "still running" state,20261014
    @Volatile
    private var handler: Handler? = null

    private var thread: HandlerThread? = null

    //pri added by zhuyang,taskId of the task that was on top when the page appeared; null = not captured yet,20261014
    private var baselineTaskId: Int? = null

    //pri added by zhuyang,Ticks on the poller thread: the task query is a binder call and must stay off the main thread,20261014
    private val tick = object : Runnable {
        override fun run() {
            val top = readTopTaskId()
            //pri added by zhuyang,Unreadable this round (exception / null / empty) is not a change: stay up and retry next tick,20261014
            if (top != null) {
                val base = baselineTaskId
                if (base == null) {
                    //pri added by zhuyang,The attach-time capture had failed: arm on the first readable tick instead of comparing,20261014
                    baselineTaskId = top
                } else if (top != base) {
                    Log.i(TAG, "顶层任务变化：$base -> $top")
                    //pri added by zhuyang,Re-baseline before firing: one callback per observed change, never a repeated one,20261014
                    baselineTaskId = top
                    //pri added by zhuyang,A tick blocked in binder while the page was detached must not report against the next page,20261014
                    if (handler != null) onTopTaskChanged()
                }
            }
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    //pri added by zhuyang,Called on attach (main thread): snapshot the top task first, then start ticking - the first tick never dismisses,20261014
    fun start() {
        if (handler != null) return
        val pollerThread = HandlerThread(THREAD_NAME)
        pollerThread.start()
        val pollerHandler = Handler(pollerThread.looper)
        thread = pollerThread
        handler = pollerHandler
        baselineTaskId = null

        pollerHandler.post {
            baselineTaskId = readTopTaskId()
            val base = baselineTaskId
            //pri added by zhuyang,Both branches log at INFO: this ROM's default log level is INFO, Log.d is invisible here,20261014
            if (base == null) {
                Log.e(TAG, "顶层任务基线读取失败，第一帧不比，等读到再设基线")
            } else {
                Log.i(TAG, "开始观察顶层任务：基线 taskId=$base")
            }
            pollerHandler.postDelayed(tick, POLL_INTERVAL_MS)
        }
    }

    //pri added by zhuyang,Called on detach / teardown (main thread): drop every pending tick and let the thread die,20261014
    fun stop() {
        val pollerThread = thread ?: return
        thread = null
        handler = null
        pollerThread.quitSafely()
    }

    /**
     * pri added by zhuyang,Top task as seen by the user, read reflectively (ActivityTaskManager and getTasks are @hide),20261014
     *
     * pri added by zhuyang,The returned list is ordered top task first (focused task, else topmost visible), so index 0 is the one to watch,20261014
     * pri added by zhuyang,The list holds other apps' tasks too (REAL_GET_TASKS is granted); with no task of our own it still holds the foreground one,20261014
     * pri added by zhuyang,null on any failure (exception / null / empty list) - the caller treats that as "no reading", never as a change,20261014
     */
    private fun readTopTaskId(): Int? = runCatching {
        val clazz = Class.forName("android.app.ActivityTaskManager")
        val manager = clazz.getMethod("getInstance").invoke(null)
        val tasks = clazz.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(manager, TOP_TASK_COUNT)
        (tasks as? List<*>)?.firstOrNull()?.let { (it as ActivityManager.RunningTaskInfo).taskId }
    }.getOrNull()

    private companion object {
        const val TAG = "TopTaskPoller"
        const val THREAD_NAME = "top-task-poller"

        //pri added by zhuyang,Poll period while a page is up: worst case this is how long the page outlives a Home / Recents press,20261014
        //pri added by zhuyang,300ms keeps that lag below the edge of noticing while costing ~3 small getTasks calls per second,20261014
        const val POLL_INTERVAL_MS = 300L

        //pri added by zhuyang,Only the top entry is ever needed - the list is ordered top task first,20261014
        const val TOP_TASK_COUNT = 1
    }
}
//pri added by zhuyang,Change 2 replacement: the ITaskStackListener Proxy over a Binder registers fine on this ROM but the system never calls it back,20261014,end
