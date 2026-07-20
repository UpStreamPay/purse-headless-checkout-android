package eu.purse.dropin.core.api

/**
 * Raised when a Payment API call (e.g. `/init`) fails at the network/transport level, returns a
 * non-2xx/3xx status, or produces a response the caller can't use (unsuccessful init, empty-balance
 * card). See [PaymentApiClient].
 */
class PaymentApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
