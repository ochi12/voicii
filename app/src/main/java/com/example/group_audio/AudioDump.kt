package com.example.group_audio

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder


class AudioDump(context: Context, filename: String?) {
    private val tmpFile: File
    private var tmpStream: FileOutputStream? = null

    init {
        val tmpDir = context.cacheDir
        tmpFile = File(tmpDir, filename)
    }

    fun add(pcm: ShortArray) {
        try {
            if (!tmpFile.exists()) {
                tmpFile.createNewFile()
            }
            if (tmpStream == null) {
                tmpStream = FileOutputStream(tmpFile, true)
            }
            val bytes = ByteArray(pcm.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            tmpStream!!.write(bytes)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun saveFile(filename: String?) {
        val length = tmpFile.length().toInt()
        val bytes = ByteArray(length)
        if (length == 0) {
            return
        }
        try {
            tmpStream!!.close()
            tmpStream = null
            val fileInputStream = FileInputStream(tmpFile)
            fileInputStream.read(bytes)
            fileInputStream.close()
            tmpFile.delete()
            val outputFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                filename
            )
            val outputStream = FileOutputStream(outputFile)
            writeWavFile(outputStream, bytes)
            outputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun writeWavFile(outputStream: FileOutputStream, data: ByteArray) {
        val header = ByteArray(44)
        val sampleRate = 16000
        val channels = 1
        val format = 16
        val totalDataLen = (data.size + 36).toLong()
        val bitrate = (sampleRate * channels * format).toLong()
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = (totalDataLen shr 8 and 0xffL).toByte()
        header[6] = (totalDataLen shr 16 and 0xffL).toByte()
        header[7] = (totalDataLen shr 24 and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = format.toByte()
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (bitrate / 8 and 0xffL).toByte()
        header[29] = (bitrate / 8 shr 8 and 0xffL).toByte()
        header[30] = (bitrate / 8 shr 16 and 0xffL).toByte()
        header[31] = (bitrate / 8 shr 24 and 0xffL).toByte()
        header[32] = (channels * format / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (data.size and 0xff).toByte()
        header[41] = (data.size shr 8 and 0xff).toByte()
        header[42] = (data.size shr 16 and 0xff).toByte()
        header[43] = (data.size shr 24 and 0xff).toByte()
        outputStream.write(header)
        outputStream.write(data)
    }
}