package net.tdobson.groqvoice.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends on [Call.enqueue] until the whole body has been read on OkHttp's thread. Cancelling the coroutine
 * cancels the call. The returned [Response] is already closed: use its code and headers, not its body.
 */
suspend fun Call.awaitResponse(): Pair<Response, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val read = runCatching { response.use { it.body.string() } }
            read.fold(
                onSuccess = { body -> cont.resume(response to body) },
                onFailure = { e -> cont.resumeWithException(e) },
            )
        }
    })
}
