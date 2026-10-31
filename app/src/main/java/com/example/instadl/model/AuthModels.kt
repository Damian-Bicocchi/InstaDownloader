package com.example.instadl.model

import com.google.gson.annotations.SerializedName

data class LoggedInUser(
    @SerializedName("pk") val pk: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("full_name") val fullName: String? = null
)

data class IgError(
    @SerializedName("message") val message: String? = null,
    @SerializedName("status") val status: String? = null
)
