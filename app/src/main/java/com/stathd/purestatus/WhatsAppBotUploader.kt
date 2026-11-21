package com.stathd.purestatus

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WhatsAppBotUploader — Zero-dependency multipart/form-data uploader for StatHD Cloudflare Worker Bot.
 * Streams the 1080p MP4 (<= 16 MB) or HD JPG directly to POST <botUrl>/send-hd.
 */
object WhatsAppBotUploader {

    data class UploadResult(
        val success: Boolean,
        val message: String
    )

    fun uploadToBot(
        botBaseUrl: String,
        recipientPhone: String,
        file: File,
        isPhoto: Boolean
    ): UploadResult {
        val cleanBase = botBaseUrl.trim().trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/send-hd")) cleanBase else "$cleanBase/send-hd"
        val boundary = "----StatHDBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"

        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                connectTimeout = 20_000
                readTimeout = 90_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            BufferedOutputStream(conn.outputStream).use { out ->
                fun writeTextField(name: String, value: String) {
                    out.write("--$boundary$lineEnd".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write(lineEnd.toByteArray())
                }

                writeTextField("phone", recipientPhone.replace(Regex("[^0-9]"), ""))
                writeTextField("mediaType", if (isPhoto) "image" else "video")

                val mime = if (isPhoto) "image/jpeg" else "video/mp4"
                val fileName = if (isPhoto) "stathd_hd.jpg" else "stathd_1080p.mp4"
                out.write("--$boundary$lineEnd".toByteArray())
                out.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$lineEnd"
                        .toByteArray()
                )
                out.write("Content-Type: $mime$lineEnd$lineEnd".toByteArray())

                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
                out.write(lineEnd.toByteArray())
                out.write("--$boundary--$lineEnd".toByteArray())
                out.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..299) {
                UploadResult(
                    success = true,
                    message = "Sent to your WhatsApp! Open your bot chat and Forward to My Status."
                )
            } else {
                val errMsg = try {
                    JSONObject(bodyText).optString("error", "Server error ($code)")
                } catch (_: Exception) {
                    "Server error ($code): $bodyText"
                }
                UploadResult(success = false, message = errMsg)
            }
        } catch (e: Exception) {
            UploadResult(
                success = false,
                message = e.localizedMessage ?: "Network error connecting to StatHD Bot"
            )
        } finally {
            conn?.disconnect()
        }
    }
}
