package org.dad.foundation

import retrofit2.Response

/**
 * The one HTTP-status mapping the raw-JSON `Wire<X>` conformers share: 401 signed out, 403 forbidden, 404 not
 * found, 409 conflict, 422 invalid input, 429 rate limited (the ingress limiter's has no body), anything else
 * unavailable. Error bodies are never read or surfaced.
 */
internal fun failureForStatus(code: Int): AccountFailure = when (code) {
    401 -> AccountFailure.SignedOut
    403 -> AccountFailure.Forbidden
    404 -> AccountFailure.NotFound
    409 -> AccountFailure.Conflict
    422 -> AccountFailure.InvalidInput
    429 -> AccountFailure.RateLimited
    else -> AccountFailure.Unavailable
}

/** The body of a 2xx response, or the failure for its status. A 2xx with no body is an invalid response. */
internal fun <T> Response<T>.successBody(): T {
    if (isSuccessful) return body() ?: throw AccountFailure.InvalidResponse
    errorBody()?.close()
    throw failureForStatus(code())
}
