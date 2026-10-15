package com.pri.nfctouchdemo.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pri.nfctouchdemo.R
import com.pri.nfctouchdemo.gfx.ImageLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

//pri added by zhuyang,D5: in-app image viewer, opened by TapService after a transfer completed,20261014,start
//pri added by zhuyang,Why our own Activity: it shows only the photos of this transfer (the system handler was Google
//pri added by zhuyang,Photos HostPhotoPagerActivity, which is a whole-gallery pager) and, being ours, the transition is ours
//pri added by zhuyang,to kill - the viewer theme sets windowAnimationStyle=@null (a Service cannot call overridePendingTransition),20261014
//pri added by zhuyang,Black background, full-bleed image, Back finishes. No overlay / float window involved: this is a real Activity,20261014
//pri added by zhuyang,The photos of a transfer are passed as Uris (ArrayList<Uri> extra); the list is small and the Uris are the
//pri added by zhuyang,MediaStore rows Xfer already created while saving, so no bitmap crosses the Intent boundary,20261014
//pri added by zhuyang,D6: the chrome (back arrow, date / file name, counter, thumbnail strip) follows the reference viewer
//pri added by zhuyang,(mtk_x .../filemanagement/ui/preview/ImagePreviewScreen.kt) but its share / delete / detail actions are
//pri added by zhuyang,intentionally not copied - they are the file manager's business, not ours,20261014
//pri added by zhuyang,D6: three pages (prev / current / next) are decoded and held at once so a drag can show the neighbour
//pri added by zhuyang,immediately; the middle slot is what the thumbnails and the strips are marked from,20261014,end
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewerPager
    private lateinit var topBar: View
    private lateinit var thumbs: HorizontalScrollView
    private lateinit var thumbsRow: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var position: TextView
    private lateinit var failure: TextView

    private var uris: List<Uri> = emptyList()
    private var generation = 0 //pri added by zhuyang,Decode generation: a late result from another transfer must be dropped,20261014
    private var destroyed = false

    private val bitmaps = HashMap<Int, Bitmap>() //pri added by zhuyang,Decoded pages around the current one,20261014
    private val metas = HashMap<Int, Meta>() //pri added by zhuyang,Name / date per page, for the title block,20261014
    private val failed = HashSet<Int>()
    private val decoding = HashSet<Int>()
    private val slotPages = IntArray(SLOT_COUNT) { NO_PAGE } //pri added by zhuyang,Which page each pager slot is showing,20261014
    //pri added by zhuyang,The bitmap each slot draws right now - the slot's own record of it, so no bitmap on screen
    //pri added by zhuyang,is ever recycled and a late decode always knows the slot is still waiting for it,20261014
    private val slotShown = arrayOfNulls<Bitmap>(SLOT_COUNT)
    private val thumbBitmaps = ArrayList<Bitmap>()
    private val thumbsRowPadding = IntArray(4) //pri added by zhuyang,The row's own padding, kept when half a viewport is added to centre a cell,20261014
    private var thumbPad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //pri added by zhuyang,Edge-to-edge like HomeActivity, but with explicit dark bars: the viewer is black in day
        //pri added by zhuyang,and night mode, and the default auto style would pick dark icons in day mode (invisible here),20261014
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_image_viewer)

        pager = findViewById(R.id.viewer_pager)
        topBar = findViewById(R.id.viewer_top_bar)
        thumbs = findViewById(R.id.viewer_thumbs)
        thumbsRow = findViewById(R.id.viewer_thumbs_row)
        title = findViewById(R.id.viewer_title)
        subtitle = findViewById(R.id.viewer_subtitle)
        position = findViewById(R.id.viewer_position)
        failure = findViewById(R.id.viewer_failure)

        //pri added by zhuyang,The two bars keep clear of the status bar / cutout / navigation bar: bars push them, not cover,20261014
        val root = findViewById<View>(R.id.viewer_root)
        val barPadding = intArrayOf(topBar.paddingLeft, topBar.paddingTop, topBar.paddingRight, topBar.paddingBottom)
        val stripPadding = intArrayOf(thumbs.paddingLeft, thumbs.paddingTop, thumbs.paddingRight, thumbs.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(
                barPadding[0] + bars.left,
                barPadding[1] + bars.top,
                barPadding[2] + bars.right,
                barPadding[3]
            )
            thumbs.setPadding(
                stripPadding[0] + bars.left,
                stripPadding[1],
                stripPadding[2] + bars.right,
                stripPadding[3] + bars.bottom
            )
            insets
        }

        //pri added by zhuyang,D6: the only way out used to be the physical Back key,20261014
        findViewById<View>(R.id.viewer_back).setOnClickListener { finish() }

        //pri added by zhuyang,The selected cell is centred, but the strip width is 0 until it is laid out: the pad
        //pri added by zhuyang,that makes an edge cell centre-able is applied once the width is known. The strip's own
        //pri added by zhuyang,layout covers the first layout and a rotation, the row's covers the pass that follows the
        //pri added by zhuyang,pad (a change inside the row does not lay the strip out again),20261014
        thumbsRowPadding[0] = thumbsRow.paddingLeft
        thumbsRowPadding[1] = thumbsRow.paddingTop
        thumbsRowPadding[2] = thumbsRow.paddingRight
        thumbsRowPadding[3] = thumbsRow.paddingBottom
        thumbs.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateThumbSelection() }
        thumbsRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateThumbSelection() }

        //pri added by zhuyang,D6: a settled turn rebinds the slots (still in the same frame, so nothing flashes),20261014
        pager.onPageChanged = {
            syncSlots()
            trim()
            updateChrome()
            ensureDecoded()
        }

        uris = readUris(intent)
        open(0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        //pri added by zhuyang,Second transfer while this viewer is up (launchMode singleTop): show the new set from the start,20261014
        uris = readUris(intent)
        open(0)
    }

    override fun onDestroy() {
        //pri added by zhuyang,Teardown: no decode may touch the view any more, and every bitmap is handed back,20261014
        destroyed = true
        releaseAll()
        super.onDestroy()
    }

    //pri added by zhuyang,Show [start] of the current [uris]: the empty list is the only case that must not crash,20261014
    private fun open(start: Int) {
        releaseAll()
        if (uris.isEmpty()) {
            //pri added by zhuyang,The service only launches this with saved photos,20261014
            Log.e(TAG, "opened without any uri, intent=$intent")
            pager.setPages(0)
            buildThumbs()
            updateChrome()
            return
        }
        pager.setPages(uris.size, start)
        buildThumbs()
        syncSlots()
        updateChrome()
        ensureDecoded()
    }

    //pri added by zhuyang,Drop the whole set: the slots first so no view is drawing a bitmap when it is recycled,20261014
    private fun releaseAll() {
        generation++
        //pri added by zhuyang,setPages(0) also stops a settle that is still running - it must not rebind recycled bitmaps,20261014
        pager.setPages(0)
        for (i in 0 until SLOT_COUNT) {
            slotPages[i] = NO_PAGE
            slotShown[i] = null
            pager.slots[i].setImageDrawable(null)
        }
        thumbsRow.removeAllViews()
        for (bmp in thumbBitmaps) bmp.recycle()
        thumbBitmaps.clear()
        for (bmp in bitmaps.values) bmp.recycle()
        bitmaps.clear()
        metas.clear()
        failed.clear()
        decoding.clear()
    }

    //pri added by zhuyang,Hand every slot the bitmap of the page it stands for; slot 0 / 1 / 2 = index - 1 / index / index + 1,20261014
    //pri added by zhuyang,INVARIANT (do not re-break): a slot draws exactly the bitmap held in slotShown[i], or nothing.
    //pri added by zhuyang,Whatever the slot draws is never recycled (trim follows this record), and the slot is emptied
    //pri added by zhuyang,before it moves to another page. So what may leave the canvas is decided by the slot binding,
    //pri added by zhuyang,never by pager.index (a decode is asynchronous: by the time it lands the index may have moved)
    //pri added by zhuyang,and never by "view.drawable == null" - setImageBitmap(null) installs an EMPTY BitmapDrawable,
    //pri added by zhuyang,which is not null, so that test used to swallow the bitmap of the page being shown and left the
    //pri added by zhuyang,pager black while the thumbnails (set straight on their cells) rendered fine,20261014
    private fun syncSlots() {
        for (i in 0 until SLOT_COUNT) {
            val page = pager.index + i - 1
            val view = pager.slots[i]
            if (page !in uris.indices) {
                if (slotPages[i] != NO_PAGE) {
                    slotPages[i] = NO_PAGE
                    slotShown[i] = null
                    view.setImageDrawable(null) //pri added by zhuyang,The honest empty: nothing left for trim() to protect,20261014
                }
                continue
            }
            val bmp = bitmaps[page]
            if (slotPages[i] != page) {
                //pri added by zhuyang,The slot moved to another page: give up what it was drawing NOW (the drawable is
                //pri added by zhuyang,dropped, the page may be recycled afterwards) and wait for the new bitmap,20261014
                slotPages[i] = page
                slotShown[i] = null
                view.setImageDrawable(null)
            }
            //pri added by zhuyang,A bitmap that lands late still reaches its slot, even when the slot was already given
            //pri added by zhuyang,something (or an empty placeholder) in the meantime,20261014
            if (bmp != null && slotShown[i] !== bmp) {
                slotShown[i] = bmp
                view.setImageBitmap(bmp)
            }
        }
    }

    //pri added by zhuyang,The neighbourhood that is kept: the three slots (what is actually bound, which is what a
    //pri added by zhuyang,visible pager page can be drawing at any moment) plus the settled current page,20261014
    private fun visibleRange(): IntRange = minOf(slotPages.min(), pager.index)..maxOf(slotPages.max(), pager.index)
    //pri added by zhuyang,The visible page loads first, the two neighbours with it so a drag has something to drag in,20261014
    //pri added by zhuyang,Only the pages the slots cover: they all sit inside the range trim() keeps, so nothing asked
    //pri added by zhuyang,for here can ever be recycled right after - that used to be an endless decode loop,20261014
    private fun ensureDecoded() {
        val wanted = visibleRange()
        for (i in 0 until SLOT_COUNT) {
            val page = pager.index + i - 1
            if (page in uris.indices && page in wanted &&
                !bitmaps.containsKey(page) && !failed.contains(page) && decoding.add(page)
            ) {
                decodePage(page)
            }
        }
    }

    private fun decodePage(page: Int) {
        val uri = uris[page]
        val gen = generation
        Thread {
            val meta = readMeta(uri)
            val decoded = ImageLoader.decode(this, uri) //pri added by zhuyang,Logs and returns null on failure,20261014
            runOnUiThread {
                decoding.remove(page)
                if (destroyed || gen != generation) {
                    decoded?.recycle() //pri added by zhuyang,Stale result (another transfer / teardown won): drop it,20261014
                    return@runOnUiThread
                }
                metas[page] = meta
                if (decoded == null) {
                    //pri added by zhuyang,A null decode used to be silent on this ROM (INFO default hides Log.d) and
                    //pri added by zhuyang,only the throwable path logged: a failing page was indistinguishable from an
                    //pri added by zhuyang,empty one,20261014
                    Log.e(TAG, "decode returned null, showing the failure hint: $uri")
                    failed.add(page)
                } else {
                    failed.remove(page)
                    bitmaps[page] = decoded
                    Log.i(TAG, "decoded page $page (${decoded.width}x${decoded.height})")
                }
                syncSlots() //pri added by zhuyang,Before trim: the slot moves off the old page first,20261014
                trim()
                updateChrome()
            }
        }.start()
    }

    //pri added by zhuyang,Keep only the bitmaps the viewer can still draw: the ones the slots are bound to (slotShown
    //pri added by zhuyang,is exactly what they draw) plus the settled current page. The range is what makes the bound
    //pri added by zhuyang,never drop below three, so the memory bound stays at three full-size bitmaps per transfer,20261014
    private fun trim() {
        val keep = visibleRange()
        val entries = bitmaps.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            val drawn = slotShown.any { it === entry.value }
            if (!drawn && entry.key !in keep) {
                entries.remove()
                entry.value.recycle()
            }
        }
    }

    //pri added by zhuyang,Title block + counter + strip highlight + failure hint, all of them idempotent,20261014
    private fun updateChrome() {
        val meta = metas[pager.index]
        title.text = if (meta != null && meta.addedMs > 0L) DATE_FORMAT.format(Date(meta.addedMs)) else ""
        subtitle.text = meta?.name.orEmpty()

        //pri added by zhuyang,Only when there is more than one image: a single photo has no "which one" to show,20261014
        val several = uris.size > 1
        position.visibility = if (several) View.VISIBLE else View.GONE
        if (several) position.text = getString(R.string.viewer_position, pager.index + 1, uris.size)
        thumbs.visibility = if (several) View.VISIBLE else View.GONE
        updateThumbSelection()
        updateFailure()
    }

    //pri added by zhuyang,The current thumbnail is framed in white and scaled up, like the reference strip,20261014
    //pri added by zhuyang,Selection scales the DRAWING (scaleX / scaleY), never the cell's layout size: the centring
    //pri added by zhuyang,math below reads left / width, which must stay the uniform slot,20261014
    private fun updateThumbSelection() {
        if (thumbs.visibility != View.VISIBLE) return
        if (thumbsRow.childCount == 0) return
        for (i in 0 until thumbsRow.childCount) {
            val cell = thumbsRow.getChildAt(i)
            val current = i == pager.index
            cell.scaleX = if (current) THUMB_CURRENT_SCALE else 1f
            cell.scaleY = cell.scaleX
            //pri added by zhuyang,A fresh instance per call: sharing one Drawable would steal the callback back off the new cell,20261014
            cell.foreground = if (current) ContextCompat.getDrawable(this, R.drawable.viewer_thumb_frame) else null
        }
        //pri added by zhuyang,Before layout (or after a rotation) the strip has no width: the layout listeners come
        //pri added by zhuyang,back through here once it has one,20261014
        val viewport = thumbs.width - thumbs.paddingLeft - thumbs.paddingRight
        if (viewport <= 0) return
        val slot = thumbsRow.getChildAt(0).width
        if (slot <= 0) return
        //pri added by zhuyang,Half a viewport at each end of the row: an edge cell can then reach the middle of the
        //pri added by zhuyang,strip. Without it the first / last cell sit flush at the edge (the reference does the
        //pri added by zhuyang,same with a LazyRow contentPadding of (maxWidth - slot) / 2),20261014
        val pad = ((viewport - slot) / 2).coerceAtLeast(0)
        if (pad != thumbPad) {
            thumbPad = pad
            thumbsRow.setPadding(
                thumbsRowPadding[0] + pad,
                thumbsRowPadding[1],
                thumbsRowPadding[2] + pad,
                thumbsRowPadding[3]
            )
            //pri added by zhuyang,The cells move by "pad" with this: the centring below must wait for the row to be
            //pri added by zhuyang,laid out again (its layout listener calls this back),20261014
            return
        }
        //pri added by zhuyang,Only where the row is long enough to scroll: a short row would scroll the trailing pad
        //pri added by zhuyang,into view and shift every cell left, and the row is already centred in the viewport,20261014
        val range = thumbsRow.width - viewport
        if (range <= 0) return
        val cell = thumbsRow.getChildAt(pager.index) ?: return
        val target = cell.left - (viewport - cell.width) / 2
        thumbs.smoothScrollTo(target.coerceIn(0, range), 0)
    }

    private fun updateFailure() {
        val broken = uris.isEmpty() || (failed.contains(pager.index) && bitmaps[pager.index] == null)
        failure.visibility = if (broken) View.VISIBLE else View.GONE
    }

    //pri added by zhuyang,Bottom strip of this transfer's photos; tapping one jumps to it (hidden for a single photo),20261014
    private fun buildThumbs() {
        thumbsRow.removeAllViews()
        //pri added by zhuyang,The centring pad is a function of the strip width and the cell size: a new list has to
        //pri added by zhuyang,be worked out from scratch,20261014
        thumbPad = 0
        thumbsRow.setPadding(
            thumbsRowPadding[0],
            thumbsRowPadding[1],
            thumbsRowPadding[2],
            thumbsRowPadding[3]
        )
        if (uris.size <= 1) return
        val size = dp(THUMB_DP)
        val gap = dp(THUMB_GAP_DP)
        for (i in uris.indices) {
            val cell = ImageView(this)
            val params = LinearLayout.LayoutParams(size, size)
            if (i < uris.lastIndex) params.marginEnd = gap
            cell.layoutParams = params
            cell.scaleType = ImageView.ScaleType.CENTER_CROP
            cell.contentDescription = getString(R.string.viewer_thumb, i + 1)
            cell.setOnClickListener { pager.goTo(i) }
            thumbsRow.addView(cell)
        }
        loadThumbs()
    }

    private fun loadThumbs() {
        //pri added by zhuyang,D6: a single photo hides the strip, so its cells are gone (removeAllViews above) - the
        //pri added by zhuyang,decodes used to land in thumbBitmaps with no cell holding them and were never recycled,20261014
        if (thumbs.visibility != View.VISIBLE) return
        val gen = generation
        val list = uris
        Thread {
            for (i in list.indices) {
                val bmp = ImageLoader.decode(this, list[i], THUMB_MAX_SIDE) ?: continue
                runOnUiThread {
                    val cell = thumbsRow.getChildAt(i) as? ImageView
                    if (destroyed || gen != generation || cell == null) {
                        bmp.recycle()
                        return@runOnUiThread
                    }
                    cell.setImageBitmap(bmp)
                    thumbBitmaps.add(bmp)
                }
            }
        }.start()
    }

    //pri added by zhuyang,Title data straight off the MediaStore row: display name + DATE_ADDED (seconds),20261014
    //pri added by zhuyang,The name Xfer.saveToGallery writes (nfctap_<millis>.jpg) carries the arrival time, so it is the
    //pri added by zhuyang,fallback when the row has no date - which is where the "date" of a received photo comes from,20261014
    private fun readMeta(uri: Uri): Meta {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var addedMs = 0L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (nameColumn >= 0) cursor.getString(nameColumn)?.let { name = it }
                    val addedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    if (addedColumn >= 0 && !cursor.isNull(addedColumn)) {
                        addedMs = cursor.getLong(addedColumn) * 1000L
                    }
                }
            }
        }.onFailure { Log.w(TAG, "no metadata for $uri, falling back to the name", it) }
        if (addedMs <= 0L) addedMs = NAME_MILLIS.find(name)?.value?.toLongOrNull() ?: 0L
        return Meta(name, addedMs)
    }

    private fun readUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
            ?.filterNotNull()
            .orEmpty()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class Meta(val name: String, val addedMs: Long)

    companion object {
        /** The photos of this transfer, as an `ArrayList<Uri>` (MediaStore rows written by Xfer). */
        const val EXTRA_URIS = "com.pri.nfctouchdemo.viewer.EXTRA_URIS"

        private const val TAG = "ImageViewer"

        private const val SLOT_COUNT = 3

        //pri added by zhuyang,slotPages[i] when the slot stands for no page of this transfer,20261014
        private const val NO_PAGE = -1

        //pri added by zhuyang,The strip is 30dp cells with 6dp gaps in a 44dp bar, like the reference LazyRow,20261014
        private const val THUMB_DP = 30f
        private const val THUMB_GAP_DP = 6f
        private const val THUMB_CURRENT_SCALE = 1.25f
        private const val THUMB_MAX_SIDE = 128 //pri added by zhuyang,Thumbnails decode small: a strip cell is 30dp,20261014

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        private val NAME_MILLIS = Regex("\\d{10,}") //pri added by zhuyang,nfctap_<millis>.jpg,20261014
    }
}
