package com.melisma.app.lyrics.provider

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cancelled load has to let go of its request, or the one replacing it waits out the timeout. */
class HttpExecuteTest {

    private val server = ServerSocket(0)
    private val held = ConcurrentLinkedQueue<Socket>()

    private fun serve(answer: String?) = thread(isDaemon = true) {
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            // Null holds the connection open and says nothing: a server that has stalled.
            if (answer == null) {
                held += client
            } else {
                client.use {
                    it.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) Unit }
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${answer.length}\r\nConnection: close\r\n\r\n$answer".toByteArray(),
                    )
                }
            }
        }
    }

    private fun call() = Http.client.newCall(Http.request("http://127.0.0.1:${server.localPort}/"))

    @After
    fun close() {
        held.forEach { runCatching { it.close() } }
        server.close()
    }

    @Test
    fun `an answer is handed to the block`() = runBlocking {
        serve("hello")
        assertEquals("hello", Http.execute(call()) { it.body?.string() })
    }

    @Test
    fun `cancelling ends a stalled request rather than waiting out its timeout`() = runBlocking {
        serve(null)
        val job = launch(Dispatchers.Default) {
            runCatching { Http.execute(call()) { it.body?.string() } }
        }
        delay(300)
        val started = System.nanoTime()
        job.cancel()
        job.join()
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        // The read timeout is 12 seconds; a blocking execute() would have held on that long.
        assertTrue("waited ${waitedMs}ms", waitedMs < 2_000)
    }
}
