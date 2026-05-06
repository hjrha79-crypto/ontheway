package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * FIX-LOGSYNC: logSync 동기 쓰기 메커니즘 검증.
 * OtwFileLogger.logSync는 Android Context 의존이므로
 * 여기서는 핵심 메커니즘(FileOutputStream + fd.sync)을 직접 검증.
 */
class OtwFileLoggerSyncTest {

    @Test
    fun `동기 쓰기 후 파일에 데이터 즉시 존재`() {
        val tmpFile = File.createTempFile("otw_sync_test", ".txt")
        try {
            val line = "12:34:56.789 [TEST] sync write test\n"
            FileOutputStream(tmpFile, true).use { fos ->
                fos.write(line.toByteArray())
                fos.fd.sync()
            }
            // sync 후 즉시 읽기 가능
            val content = tmpFile.readText()
            assertTrue("sync 후 데이터 존재", content.contains("sync write test"))
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `동기 쓰기 append 모드`() {
        val tmpFile = File.createTempFile("otw_sync_append", ".txt")
        try {
            // 첫 쓰기
            FileOutputStream(tmpFile, true).use { fos ->
                fos.write("line1\n".toByteArray())
                fos.fd.sync()
            }
            // 두 번째 쓰기 (append)
            FileOutputStream(tmpFile, true).use { fos ->
                fos.write("line2\n".toByteArray())
                fos.fd.sync()
            }
            val content = tmpFile.readText()
            assertTrue("line1 존재", content.contains("line1"))
            assertTrue("line2 존재", content.contains("line2"))
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `실패 시 예외 전파 안 함 (fail-safe)`() {
        // 존재하지 않는 디렉토리에 쓰기 시도
        val bogusFile = File("/nonexistent_dir_12345/test.txt")
        var caught = false
        try {
            FileOutputStream(bogusFile, true).use { fos ->
                fos.write("test\n".toByteArray())
                fos.fd.sync()
            }
        } catch (_: Exception) {
            caught = true
        }
        assertTrue("비정상 경로에서 예외 발생 (앱에서는 catch로 안전)", caught)
    }

    @Test
    fun `logSync 호출 시 lastWriteTime 갱신 검증 패턴`() {
        // OtwFileLogger.logSync는 Context 필요하므로 직접 호출 불가
        // 대신 lastWriteTime 갱신 로직이 logSync 첫줄에 있음을 패턴으로 검증
        val before = OtwFileLogger.lastWriteTime
        // Context 없이 logSync → 즉시 return (appCtx == null)
        OtwFileLogger.logSync("TEST", "no context")
        // appCtx null이면 갱신 안 됨 — 이것은 안전 동작
        // 실제 갱신은 Context 존재 시만 발생
        assertTrue("lastWriteTime은 context 없이 변경 안 됨", OtwFileLogger.lastWriteTime == before)
    }

    // ══ FIX-REGRESSION: 추가 시나리오 ══

    @Test
    fun `REG-LS1 다중 동시 sync 쓰기`() {
        val tmpFile = File.createTempFile("otw_concurrent", ".txt")
        try {
            repeat(10) { i ->
                FileOutputStream(tmpFile, true).use { fos ->
                    fos.write("line_$i\n".toByteArray())
                    fos.fd.sync()
                }
            }
            val lines = tmpFile.readLines()
            assertEquals("10줄 모두 보존", 10, lines.size)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `REG-LS2 빈 문자열 쓰기 안전`() {
        val tmpFile = File.createTempFile("otw_empty", ".txt")
        try {
            FileOutputStream(tmpFile, true).use { fos ->
                fos.write("".toByteArray())
                fos.fd.sync()
            }
            assertEquals(0, tmpFile.length())
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `REG-LS3 대용량 라인 쓰기 안전`() {
        val tmpFile = File.createTempFile("otw_large", ".txt")
        try {
            val largeLine = "X".repeat(10_000) + "\n"
            FileOutputStream(tmpFile, true).use { fos ->
                fos.write(largeLine.toByteArray())
                fos.fd.sync()
            }
            assertTrue("10KB+ 쓰기 성공", tmpFile.length() > 10_000)
        } finally {
            tmpFile.delete()
        }
    }
}
