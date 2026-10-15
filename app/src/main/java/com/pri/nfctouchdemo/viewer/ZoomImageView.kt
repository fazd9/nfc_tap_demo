package com.pri.nfctouchdemo.viewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

//pri added by zhuyang,D5: in-app image viewer (replaces the Google Photos pager),20261014,start
//pri added by zhuyang,One image on a Matrix: pinch-zoom 1x-5x plus drag-pan clamped so an edge can never leave the screen,20261014
//pri added by zhuyang,Interaction semantics borrowed from the reference implementation,20261014
//pri added by zhuyang,(mtk_x .../filemanagement/ui/preview/ImagePreviewScreen.kt: scale.coerceIn(1f, 5f) + pan clamp + double-tap 1x/2.5x),20261014
//pri added by zhuyang,At 1x a horizontal swipe pages to the previous / next image of this transfer,20261014
//pri added by zhuyang,D6: the swipe is no longer judged on release - every move is handed to the pager, which drags the
//pri added by zhuyang,neighbouring image in under the finger and settles or springs back when the finger lifts,20261014
//pri added by zhuyang,While zoomed the drag pans and paging is off - pinch or double-tap back to 1x first,20261014,end
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    /** The pager this page lives in: it owns prev / current / next and the page animation. */
    interface PageDragListener {
        /** A horizontal page drag started (the touch slop has just been crossed). */
        fun onPageDragStart()

        /** The finger moved [dx] px since the last report. */
        fun onPageDragBy(dx: Float)

        /** The finger lifted: settle to the neighbour or spring back, [velocityX] in px/s. */
        fun onPageDragEnd(velocityX: Float)

        /** The gesture turned into something else (a second finger landed): spring back. */
        fun onPageDragCancel()
    }

    var pageDrag: PageDragListener? = null

    private val matrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, TapListener())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var swipeX = 0f
    private var swipeY = 0f
    private var dragMode = DragMode.UNDECIDED
    private var tracker: VelocityTracker? = null

    private enum class DragMode { UNDECIDED, PAGE, IGNORED }

    init {
        scaleType = ImageView.ScaleType.MATRIX
    }

    //pri added by zhuyang,Every new image starts at 1x centred: the previous image's zoom must not carry over,20261014
    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMatrix() //pri added by zhuyang,Refit and re-clamp on a size change (rotation is handled in place),20261014
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                swipeX = 0f
                swipeY = 0f
                dragMode = DragMode.UNDECIDED
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                //pri added by zhuyang,A second finger lands: this gesture is a pinch, so drop any pending page swipe,20261014
                cancelDrag()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                //pri added by zhuyang,One finger lifted: re-seed the anchor so the remaining finger cannot jump the image,20261014
                lastX = event.getX(0)
                lastY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (isZoomed()) {
                        transX += dx //pri added by zhuyang,Zoomed: the drag pans, applyMatrix clamps it to the edges,20261014
                        transY += dy
                        applyMatrix()
                    } else {
                        swipeX += dx
                        swipeY += dy
                        when (dragMode) {
                            //pri added by zhuyang,At 1x the first slop decides the gesture: sideways pages, up / down is ignored,20261014
                            DragMode.UNDECIDED -> if (abs(swipeX) > slop || abs(swipeY) > slop) {
                                dragMode = if (abs(swipeX) > abs(swipeY)) DragMode.PAGE else DragMode.IGNORED
                                if (dragMode == DragMode.PAGE) {
                                    pageDrag?.onPageDragStart()
                                    pageDrag?.onPageDragBy(dx)
                                }
                            }
                            //pri added by zhuyang,Following: every move goes straight to the pager, nothing is held back,20261014
                            DragMode.PAGE -> pageDrag?.onPageDragBy(dx)
                            DragMode.IGNORED -> Unit
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragMode == DragMode.PAGE) {
                    tracker?.computeCurrentVelocity(VELOCITY_UNITS)
                    pageDrag?.onPageDragEnd(tracker?.xVelocity ?: 0f)
                }
                dragMode = DragMode.IGNORED
                endTracker()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                endTracker()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun isZoomed(): Boolean = scale > 1.01f

    private fun cancelDrag() {
        if (dragMode == DragMode.PAGE) pageDrag?.onPageDragCancel()
        dragMode = DragMode.IGNORED
        swipeX = 0f
        swipeY = 0f
    }

    private fun endTracker() {
        tracker?.recycle()
        tracker = null
    }

    private fun resetZoom() {
        scale = 1f
        transX = 0f
        transY = 0f
        applyMatrix()
    }

    private fun applyMatrix() {
        val drawable = drawable ?: return
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        if (iw <= 0 || ih <= 0 || vw <= 0f || vh <= 0f) return
        val s = min(vw / iw, vh / ih) * scale //pri added by zhuyang,Fit-centre at 1x, magnified by the zoom level,20261014
        val boundX = max(0f, (iw * s - vw) / 2f)
        val boundY = max(0f, (ih * s - vh) / 2f)
        transX = transX.coerceIn(-boundX, boundX) //pri added by zhuyang,Clamp: the image cannot be dragged off-screen,20261014
        transY = transY.coerceIn(-boundY, boundY)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate(
            paddingLeft + (vw - iw * s) / 2f + transX,
            paddingTop + (vh - ih * s) / 2f + transY
        )
        imageMatrix = matrix
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            if (next == scale) return true
            //pri added by zhuyang,Keep the pinch focus fixed: the pixel under the fingers must not slide away,20261014
            val k = next / scale
            val cx = width / 2f
            val cy = height / 2f
            transX = (detector.focusX - cx) * (1f - k) + transX * k
            transY = (detector.focusY - cy) * (1f - k) + transY * k
            scale = next
            applyMatrix()
            return true
        }
    }

    private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            //pri added by zhuyang,Double-tap: back to 1x re-centred (paging works again) or up to 2.5x re-centred,20261014
            scale = if (isZoomed()) MIN_SCALE else DOUBLE_TAP_SCALE
            transX = 0f
            transY = 0f
            applyMatrix()
            return true
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        const val VELOCITY_UNITS = 1000 //pri added by zhuyang,px/s, the unit the pager's fling test expects,20261014
    }
}
