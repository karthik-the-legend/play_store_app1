package app.formkit.feature.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.formkit.R

enum class Tool(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val icon: Int,
    val category: Category,
) {
    ResizeKb(R.string.tool_resize_title, R.string.tool_resize_desc, R.drawable.ic_tool_resize, Category.Photo),
    PassportPhoto(R.string.tool_passport_title, R.string.tool_passport_desc, R.drawable.ic_tool_passport, Category.Photo),
    Signature(R.string.tool_signature_title, R.string.tool_signature_desc, R.drawable.ic_tool_signature, Category.Photo),
    ImagesToPdf(R.string.tool_images_to_pdf_title, R.string.tool_images_to_pdf_desc, R.drawable.ic_tool_images_to_pdf, Category.Pdf),
    ScanToPdf(R.string.tool_scan_title, R.string.tool_scan_desc, R.drawable.ic_tool_scan, Category.Pdf),
    CompressPdf(R.string.tool_compress_pdf_title, R.string.tool_compress_pdf_desc, R.drawable.ic_tool_compress_pdf, Category.Pdf),
    MergePdf(R.string.tool_merge_pdf_title, R.string.tool_merge_pdf_desc, R.drawable.ic_tool_merge_pdf, Category.Pdf),
    SplitPdf(R.string.tool_split_pdf_title, R.string.tool_split_pdf_desc, R.drawable.ic_tool_split_pdf, Category.Pdf),
    PdfToImages(R.string.tool_pdf_to_images_title, R.string.tool_pdf_to_images_desc, R.drawable.ic_tool_pdf_to_images, Category.Pdf),
    ;

    enum class Category { Photo, Pdf }

    companion object {
        /** The flagship tool, given the wide card at the top of Home. */
        val featured = ResizeKb
    }
}
