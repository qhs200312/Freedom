package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipUtil {
    private const val BUFFER_SIZE = 4096
    private const val MAX_ENTRIES = 2048
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /**
     * Zip the contents of a folder.
     *
     * @param folderPath The path to the folder to zip.
     * @param outputZipFilePath The path to the output zip file.
     * @return True if the operation is successful, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun zipFromFolder(folderPath: String, outputZipFilePath: String): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            if (folderPath.isEmpty() || outputZipFilePath.isEmpty()) {
                return false
            }

            val filesToCompress = ArrayList<String>()
            val directory = File(folderPath)
            if (directory.isDirectory) {
                directory.listFiles()?.forEach {
                    if (it.isFile) {
                        filesToCompress.add(it.absolutePath)
                    }
                }
            }
            if (filesToCompress.isEmpty()) {
                return false
            }

            ZipOutputStream(FileOutputStream(outputZipFilePath)).use { zos ->
                filesToCompress.forEach { file ->
                    val ze = ZipEntry(File(file).name)
                    zos.putNextEntry(ze)
                    FileInputStream(file).use { inputStream ->
                        while (true) {
                            val len = inputStream.read(buffer)
                            if (len <= 0) break
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to zip folder", e)
            return false
        }
        return true
    }

    /**
     * Unzip the contents of a zip file to a folder.
     *
     * @param zipFile The zip file to unzip.
     * @param destDirectory The destination directory.
     * @return True if the operation is successful, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun unzipToFolder(zipFile: File, destDirectory: String): Boolean {
        try {
            val destDir = File(destDirectory).canonicalFile
            if (!destDir.exists()) destDir.mkdirs()
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                var count = 0
                var total = 0L
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (++count > MAX_ENTRIES) throw IOException("Too many zip entries")

                    val target = File(destDir, entry.name).canonicalFile
                    if (target != destDir &&
                        !target.path.startsWith(destDir.path + File.separator)
                    ) {
                        throw IOException("Zip entry escapes destination: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(target)).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_TOTAL_BYTES) throw IOException("Zip too large")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unzip file", e)
            return false
        }
        return true
    }
}
