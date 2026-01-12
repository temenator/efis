package com.example.efis

import java.io.InputStream

class CountingInputStream(private val base: InputStream) : InputStream() {
    @Volatile
    var total: Long = 0
        private set

    override fun read(): Int {
        val r = base.read()
        if (r >= 0) total++
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = base.read(b, off, len)
        if (n > 0) total += n.toLong()
        return n
    }

    override fun available(): Int = base.available()
    override fun close() = base.close()
}

