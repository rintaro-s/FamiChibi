package com.nbks.famichibi

object PendingDeepLink {
    @Volatile
    var hostUrl: String? = null
        private set
    @Volatile
    var inviteCode: String? = null
        private set
    @Volatile
    var hasPending: Boolean = false
        private set

    fun set(host: String, invite: String) {
        hostUrl = host
        inviteCode = invite
        hasPending = true
    }

    fun clear() {
        hostUrl = null
        inviteCode = null
        hasPending = false
    }
}
