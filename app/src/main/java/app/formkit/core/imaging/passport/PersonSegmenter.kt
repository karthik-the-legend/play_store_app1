package app.formkit.core.imaging.passport

import android.graphics.Bitmap

/** Separates a person from the background, entirely on the device. */
interface PersonSegmenter {

    /**
     * A mask at [image]'s exact size: 255 where the person is, 0 for background.
     *
     * @throws SegmentationUnavailableException if this phone can't run background removal at all
     */
    suspend fun segment(image: Bitmap): ForegroundMask
}

/** Background removal can't run on this phone, so the photo has to be used as it is. */
class SegmentationUnavailableException(cause: Throwable? = null) :
    Exception("On-device background removal isn't available on this phone", cause)
