package com.dsh.wake

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** 下载模型、解压。只在第一次启动时跑。 */
object Downloader {

    fun download(url: String, dest: File, onProgress: (Int) -> Unit = {}) {
        dest.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("下载失败：HTTP ${conn.responseCode}")
            }
            val total = conn.contentLength
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != lastPercent) {
                                lastPercent = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 解压 zip；zip 里通常带一层目录，返回这层目录。 */
    fun unzip(zipFile: File, targetDir: File): File {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(targetDir, entry.name)
                // 防目录穿越
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        return targetDir.listFiles()?.firstOrNull { it.isDirectory } ?: targetDir
    }
}
