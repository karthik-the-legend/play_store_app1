package app.formkit.core.imaging.passport

import android.graphics.Bitmap

/** Separates a person from the background, entirely on the device. */
interface PersonSegmenter {

    /** A mask at [image]'s exact size: 255 where the person is, 0 for background. */
    suspend fun segment(image: Bitmap): ForegroundMask
}
