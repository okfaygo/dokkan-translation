package dev.fogo.dokkantranslate.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Invisible activity whose only job is to show the system screen-capture
 * consent dialog and hand the result to BubbleService.
 *
 * It exists because consent can only be requested from an Activity, while
 * the bubble lives in a Service — and because a MediaProjection token is
 * single-use, so the bubble needs a way to ask again after the system stops
 * the projection (screen lock, or the user revoking it) without making the
 * user go back to the app manually.
 */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            BubbleService.start(this, resultCode, data)
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 4242

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
