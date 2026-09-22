@file:Suppress("unused", "RedundantSuspendModifier", "UnusedReceiverParameter")

package eu.kanade.tachiyomi.network

import okhttp3.Call
import okhttp3.Response

suspend fun Call.await(): Response = throw Exception("Stub!")

/**
 * Like [await], but throws [HttpException] when the response is not successful.
 */
suspend fun Call.awaitSuccess(): Response = throw Exception("Stub!")
