package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * Fix S: LogZipExporter 단위 테스트.
 * Context 의존 export()는 Robolectric 필요 → 순수 로직만 테스트.
 */
class LogZipExporterTest {

    @Test
    fun `formatSize bytes`() {
        assertEquals("512B", LogZipExporter.formatSize(512))
    }

    @Test
    fun `formatSize KB`() {
        assertEquals("15KB", LogZipExporter.formatSize(15 * 1024))
    }

    @Test
    fun `formatSize MB`() {
        assertEquals("2.5MB", LogZipExporter.formatSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formatSize 0`() {
        assertEquals("0B", LogZipExporter.formatSize(0))
    }
}
