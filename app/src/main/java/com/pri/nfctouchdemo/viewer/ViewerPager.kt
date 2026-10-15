package com.pri.nfctouchdemo.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

//pri added by zhuyang,D6: drag-follow pager - the image stays under the finger while the page is dragged,20261014,start
//pri added by zhuyang,Why not ViewPager2: this project has no viewpager2 dependency and no new one may be added, and the
//pri added by zhuyang,viewer only ever pages between the photos of one transfer. Three full-size ZoomImageViews laid out
//pri added by zhuyang,one page width apart and scrolled together are enough: slot 0 is [index]-1, slot 1 is [index], slot 2
//pri added by zhuyang,is [index]+1. The parent's scrollX is the drag - the neighbour already sits one width to the side, so
//pri added by zhuyang,scrolling brings it in under the finger instead of after release. The host puts the bitmaps in the
//pri added by zhuyang,slots and is told about every settled turn through onPageChanged,20261014,end
class ViewerPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), ZoomImageView.PageDragListener {

    /** Slot 0 / 1 / 2 shows page [index] - 1 / [index] / [index] + 1; the host binds the bitmaps. */
    val slots: List<ZoomImageView> = List(SLOTS) { ZoomImageView(context) }

    /** A page turn settled on the new [index] (from a drag or from a tap in the thumbnail strip). */
    var onPageChanged: ((Int) -> Unit)? = null

    var index = 0
        private set

    var pageCount = 0
        private set

    private var offset = 0f //pri added by zhuyang,Finger travel in px; negative = towards the next page,20261014
    private var animator: ValueAnimator? = null
    private var token = 0
    private val decelerate = DecelerateInterpolator()

    init {
        for (slot in slots) {
            //pri added by zhuyang,At 1x the page drags the whole pager; the zoom / pan rules stay inside ZoomImageView,20261014
            slot.pageDrag = this
            addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    //pri added by zhuyang,The three pages sit one width apart; the parent's scrollX slides them past the window,20261014
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        slots.forEachIndexed { i, slot -> slot.layout((i - 1) * w, 0, i * w, h) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        abort() //pri added by zhuyang,A resize (rotation) drops whatever the drag was doing,20261014
    }

    /** Show a fresh list: [start] is the page in the middle slot, the host fills the slots around it. */
    fun setPages(count: Int, start: Int = 0) {
        abort()
        pageCount = max(0, count)
        index = if (pageCount == 0) 0 else start.coerceIn(0, pageCount - 1)
        for (slot in slots) slot.setImageDrawable(null)
    }

    /** Jump to [target] (a tap in the thumbnail strip): the page being left slides out, the new one slides in. */
    fun goTo(target: Int) {
        if (target !in 0 until pageCount || target == index) return
        val forward = target > index
        abort()
        index = target
        onPageChanged?.invoke(index) //pri added by zhuyang,The host rebinds the slots before the next frame is drawn,20261014
        val w = width.toFloat()
        if (w <= 0f) return
        //pri added by zhuyang,Start on the page being left: it is the slot next to the new one, so it must be the one on screen,20261014
        offset = if (forward) w else -w
        applyOffset(damp = false)
        animateOffset(0f, COMMIT_NONE, damp = false)
    }

    //pri added by zhuyang,The current page forwards its 1x horizontal drag: the pages follow the finger from here on,20261014,start
    override fun onPageDragStart() {
        token++
        animator?.cancel() //pri added by zhuyang,Grabbing a page that is still settling continues from where it is,20261014
        animator = null
    }

    override fun onPageDragBy(dx: Float) {
        offset += dx
        applyOffset(damp = true)
    }

    override fun onPageDragEnd(velocityX: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        val forward = offset < 0f
        val target = if (forward) index + 1 else index - 1
        val reachable = target in 0 until pageCount
        val past = abs(offset) > w * PAGE_TURN_FRACTION
        val flung = abs(velocityX) > flingVelocity() && (velocityX < 0f) == forward
        if (reachable && (past || flung)) animateOffset(if (forward) -w else w, target, damp = true)
        else animateOffset(0f, COMMIT_NONE, damp = true)
    }

    override fun onPageDragCancel() {
        //pri added by zhuyang,A second finger landed (pinch) or the gesture was cancelled: the pages spring back,20261014
        if (offset != 0f || animator != null) animateOffset(0f, COMMIT_NONE, damp = true)
    }
    //pri added by zhuyang,The current page forwards its 1x horizontal drag: the pages follow the finger from here on,20261014,end

    //pri added by zhuyang,Scroll the three pages by [offset]; at the first / last page the drag is damped instead,20261014
    private fun applyOffset(damp: Boolean) {
        val w = width.toFloat()
        if (w <= 0f) return
        val hasNeighbour = if (offset < 0f) index < pageCount - 1 else index > 0
        val shown = if (damp && !hasNeighbour) offset * EDGE_DAMP else offset.coerceIn(-w, w)
        scrollTo(-shown.roundToInt(), 0)
    }

    //pri added by zhuyang,Settle or spring back by animating the offset itself, so the pages keep following,20261014
    private fun animateOffset(target: Float, commitTo: Int, damp: Boolean) {
        val mine = ++token //pri added by zhuyang,Bump first: a cancelled animation must not commit anything,20261014
        animator?.cancel()
        animator = null
        val animation = ValueAnimator.ofFloat(offset, target)
        animation.duration = SETTLE_MS
        animation.interpolator = decelerate
        animation.addUpdateListener {
            offset = it.animatedValue as Float
            applyOffset(damp)
        }
        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (mine != token) return //pri added by zhuyang,A newer gesture / turn took over,20261014
                animator = null
                if (commitTo != COMMIT_NONE) index = commitTo
                offset = 0f
                applyOffset(damp = false)
                if (commitTo != COMMIT_NONE) onPageChanged?.invoke(index)
            }
        })
        animator = animation
        animation.start()
    }

    private fun abort() {
        token++
        animator?.cancel()
        animator = null
        offset = 0f
        scrollTo(0, 0)
    }

    private fun flingVelocity(): Float = FLING_DP * resources.displayMetrics.density

    private companion object {
        const val SLOTS = 3
        const val COMMIT_NONE = -1
        const val SETTLE_MS = 220L
        //pri added by zhuyang,Past a quarter of the page on release settles to the neighbour,20261014
        const val PAGE_TURN_FRACTION = 0.25f
        //pri added by zhuyang,ViewPager's MIN_FLING_VELOCITY (400dp/s): a quicker flick settles even when short,20261014
        const val FLING_DP = 400f
        //pri added by zhuyang,First / last page: the drag rubber-bands instead of running off into the black,20261014
        const val EDGE_DAMP = 0.35f
    }
}
