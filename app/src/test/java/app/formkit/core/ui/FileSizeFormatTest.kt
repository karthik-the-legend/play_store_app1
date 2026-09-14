package app.formkit.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FileSizeFormatTest {

    @Test
    fun `sizes use 1000-based units`() {
        assertEquals(DisplaySize("999", SizeUnit.Bytes), displaySize(999, Locale.US))
        assertEquals(DisplaySize("1", SizeUnit.Kilobytes), displaySize(1_000, Locale.US))
        assertEquals(DisplaySize("1", SizeUnit.Megabytes), displaySize(1_000_000, Locale.US))
    }

    @Test
    fun `sizes round down so a file under the limit never reads as at the limit`() {
        assertEquals(DisplaySize("49", SizeUnit.Kilobytes), displaySize(49_999, Locale.US))
        assertEquals(DisplaySize("48", SizeUnit.Kilobytes), displaySize(48_750, Locale.US))
        assertEquals(DisplaySize("999", SizeUnit.Kilobytes), displaySize(999_999, Locale.US))
    }

    @Test
    fun `small sizes show one decimal place`() {
        assertEquals(DisplaySize("9.9", SizeUnit.Kilobytes), displaySize(9_950, Locale.US))
        assertEquals(DisplaySize("1.2", SizeUnit.Megabytes), displaySize(1_250_000, Locale.US))
        assertEquals(DisplaySize("12.3", SizeUnit.Megabytes), displaySize(12_345_678, Locale.US))
    }

    @Test
    fun `numbers follow the locale`() {
        assertEquals(DisplaySize("9,9", SizeUnit.Kilobytes), displaySize(9_950, Locale.GERMANY))
    }
}
