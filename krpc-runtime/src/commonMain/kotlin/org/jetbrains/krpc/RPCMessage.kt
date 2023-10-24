package org.jetbrains.krpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RPCMessage {
    val callId: String
    val serviceType: String

    @Serializable
    sealed interface CallResult : RPCMessage

    @Serializable
    data class CallData(
        override val callId: String,
        override val serviceType: String,
        @SerialName("method")
        val callableName: String,
        val data: String,
    ) : RPCMessage

    @Serializable
    data class CallSuccess(
        override val callId: String,
        override val serviceType: String,
        val data: String
    ) : CallResult

    /**
     * Both for client and server
     */
    @Serializable
    data class CallException(
        override val callId: String,
        override val serviceType: String,
        val cause: SerializedException
    ) : CallResult

    @Serializable
    data class StreamMessage(
        override val callId: String,
        override val serviceType: String,
        @SerialName("flowId")
        val streamId: String,
        val data: String
    ) : RPCMessage

    @Serializable
    data class StreamCancel(
        override val callId: String,
        override val serviceType: String,
        @SerialName("flowId")
        val streamId: String,
        val cause: SerializedException
    ) : RPCMessage

    @Serializable
    data class StreamFinished(
        override val callId: String,
        override val serviceType: String,
        @SerialName("flowId")
        val streamId: String,
    ) : RPCMessage
}
