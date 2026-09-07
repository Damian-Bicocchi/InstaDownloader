package com.example.instadl.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName("logged_in_user") val loggedInUser: LoggedInUser? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error_type") val errorType: String? = null,
    @SerializedName("two_factor_required") val twoFactorRequired: Boolean? = null,
    @SerializedName("checkpoint_required") val checkpointRequired: Checkpoint? = null
)

data class LoggedInUser(
    @SerializedName("pk") val pk: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("full_name") val fullName: String? = null
)

data class Checkpoint(
    @SerializedName("challenge") val challenge: Challenge? = null
)

data class Challenge(
    @SerializedName("url") val url: String? = null,
    @SerializedName("api_path") val apiPath: String? = null
)

data class IgError(
    @SerializedName("message") val message: String? = null,
    @SerializedName("status") val status: String? = null
)
