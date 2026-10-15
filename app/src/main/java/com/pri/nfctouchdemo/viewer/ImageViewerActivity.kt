package com.pri.nfctouchdemo.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pri.nfctouchdemo.R
import com.pri.nfctouchdemo.gfx.ImageLoader

//pri added by zhuyang,D5: in-app image viewer, opened by TapService after a transfer completed,20261014,start
//pri added by zhuyang,Why our own Activity: it shows only the photos of this transfer (the system handler was Google
//pri added by zhuyang,Photos HostPhotoPagerActivity, which is a whole-gallery pager) and, being ours, the transition is ours
//pri added by zhuyang,to kill - the viewer theme sets windowAnimationStyle=@null (a Service cannot call overridePendingTransition),20261014
//pri added by zhuyang,Black background, full-bleed image, Back finishes. No overlay / float window involved: this is a real Activity,20261014
//pri added by zhuyang,The photos of a transfer are passed as Uris (ArrayList<Uri> extra); the list is small and the Uris are the
//pri added by zhuyang,MediaStore rows Xfer already created while saving, so no bitmap crosses the Intent boundary,20261014,end
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var image: ZoomImageView
    private lateinit var position: TextView
    private lateinit var failure: TextView

    private var uris: List<Uri> = emptyList()
    private var index = 0
    private var request = 0 //pri added by zhuyang,Decode generation: a late result from another page must be dropped,20261014
    private var bitmap: Bitmap? = null
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //pri added by zhuyang,Edge-to-edge like HomeActivity, but with explicit dark bars: the viewer is black in day
        //pri added by zhuyang,and night mode, and the default auto style would pick dark icons in day mode (invisible here),20261014
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_image_viewer)

        image = findViewById(R.id.viewer_image)
        position = findViewById(R.id.viewer_position)
        failure = findViewById(R.id.viewer_failure)

        //pri added by zhuyang,The counter keeps clear of the status bar / cutout: bars push the content, they do not cover it,20261014
        ViewCompat.setOnApplyWindowInsetsListener(position) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop + bars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        image.onPageSwipe = { step -> show(index + step, step) }

        uris = readUris(intent)
        if (uris.isEmpty()) {
            //pri added by zhuyang,The service only launches this with saved photos; an empty list still must not crash,20261014
            Log.e(TAG, "launched without any uri, intent=$intent")
            showFailure()
            return
        }
        show(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        //pri added by zhuyang,Second transfer while this viewer is up (launchMode singleTop): show the new set from the start,20261014
        uris = readUris(intent)
        if (uris.isEmpty()) {
            Log.e(TAG, "onNewIntent without any uri, intent=$intent")
            showFailure()
            return
        }
        show(0, 0)
    }

    override fun onDestroy() {
        //pri added by zhuyang,Teardown: no decode may touch the view any more, and the last bitmap is handed back,20261014
        destroyed = true
        request++
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    //pri added by zhuyang,Page 0-based to [target]; step is the swipe direction for the small slide-in feedback,20261014
    private fun show(target: Int, step: Int) {
        if (uris.isEmpty()) return
        val next = target.coerceIn(0, uris.size - 1)
        if (step != 0 && next == index) return //pri added by zhuyang,Already at the first / last image: nothing to page to,20261014
        index = next
        updateCounter()
        if (step > 0) slideIn(1f) else if (step < 0) slideIn(-1f)

        val uri = uris[index]
        val token = ++request
        Log.i(TAG, "show ${index + 1}/${uris.size} $uri")
        Thread {
            val decoded = ImageLoader.decode(this, uri) //pri added by zhuyang,Logs and returns null on failure,20261014
            runOnUiThread {
                if (destroyed || token != request) {
                    decoded?.recycle() //pri added by zhuyang,Stale result (another page / teardown won): drop it,20261014
                    return@runOnUiThread
                }
                if (decoded == null) {
                    Log.e(TAG, "decode failed, showing the failure hint: $uri")
                    showFailure()
                    return@runOnUiThread
                }
                failure.visibility = View.GONE
                val old = bitmap
                bitmap = decoded
                image.setImageBitmap(decoded) //pri added by zhuyang,Resets zoom / pan through setImageDrawable,20261014
                if (old != null && old !== decoded) old.recycle()
            }
        }.start()
    }

    private fun showFailure() {
        failure.visibility = View.VISIBLE
        val old = bitmap
        bitmap = null
        image.setImageDrawable(null)
        if (old != null) old.recycle()
    }

    //pri added by zhuyang,Only when there is more than one image: a single photo has no "which one" to show,20261014
    private fun updateCounter() {
        position.visibility = if (uris.size > 1) View.VISIBLE else View.GONE
        position.text = getString(R.string.viewer_position, index + 1, uris.size)
    }

    //pri added by zhuyang,Page feedback inside the viewer (the image slides in from the swipe direction),20261014
    private fun slideIn(direction: Float) {
        image.translationX = direction * width * 0.12f
        image.animate().translationX(0f).setDuration(SLIDE_MS).start()
    }

    private fun readUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
            ?.filterNotNull()
            .orEmpty()
    }

    companion object {
        /** The photos of this transfer, as an `ArrayList<Uri>` (MediaStore rows written by Xfer). */
        const val EXTRA_URIS = "com.pri.nfctouchdemo.viewer.EXTRA_URIS"

        private const val TAG = "ImageViewer"

        private const val SLIDE_MS = 140L
    }
}
