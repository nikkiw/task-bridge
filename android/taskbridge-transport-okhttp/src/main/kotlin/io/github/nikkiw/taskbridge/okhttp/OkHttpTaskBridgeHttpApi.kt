/*
 * Copyright 2026 Nikolay Vlasov (https://github.com/nikkiw)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nikkiw.taskbridge.okhttp

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.github.nikkiw.taskbridge.model.CancelTaskBody
import io.github.nikkiw.taskbridge.model.CancelTaskResponse
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.SubmitActionResponse
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskCreatedResponse
import io.github.nikkiw.taskbridge.policy.TaskBridgeHttpStatusException
import io.github.nikkiw.taskbridge.transport.TaskBridgeHttpApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Tag
import retrofit2.http.Url
import java.io.IOException

/**
 * [TaskBridgeHttpApi] implementation using Retrofit and OkHttp.
 */
class OkHttpTaskBridgeHttpApi<Ctx> private constructor(
    private val service: RetrofitTaskBridgeHttpApi,
    private val json: Json,
) : TaskBridgeHttpApi<Ctx> {
    override suspend fun createTaskJson(
        context: Ctx,
        url: String,
        body: TaskCreateJsonRequest,
    ): TaskCreatedResponse = wrapHttpErrors { service.createTaskJson(url, body, TaskBridgeContextTag(context)) }

    override suspend fun createTaskMultipart(
        context: Ctx,
        url: String,
        clientRequestId: String,
        taskType: String,
        inputJson: String?,
        metadataJson: String?,
        attachments: List<TaskBridgeMultipartAttachment>,
    ): TaskCreatedResponse =
        wrapHttpErrors {
            service.createTaskMultipart(
                url = url,
                clientRequestId = clientRequestId.toPlainBody(),
                taskType = taskType.toPlainBody(),
                input = inputJson?.toPlainBody(),
                metadata = metadataJson?.toPlainBody(),
                attachments = attachments.map { it.toPart() },
                context = TaskBridgeContextTag(context),
            )
        }

    override suspend fun pollEvents(
        context: Ctx,
        url: String,
        afterEventId: String?,
        waitTimeoutMs: Int,
        maxEvents: Int,
    ): PollEventsResponse =
        wrapHttpErrors {
            val response: Response<PollEventsResponse> =
                service.pollEvents(url, afterEventId, waitTimeoutMs, maxEvents, TaskBridgeContextTag(context))
            if (!response.isSuccessful) {
                throw HttpException(response)
            }
            val body = response.body() ?: throw IOException("Empty response body")

            // Re-serialize to get the raw JSON.
            // A more efficient way would be using an Interceptor or a custom Converter.Factory,
            // but this is the least invasive change for now.
            body.rawJson = json.encodeToString(PollEventsResponse.serializer(), body)
            body
        }

    override suspend fun cancelTask(
        context: Ctx,
        url: String,
        body: CancelTaskBody?,
    ): CancelTaskResponse = wrapHttpErrors { service.cancelTask(url, body, TaskBridgeContextTag(context)) }

    override suspend fun submitAction(
        context: Ctx,
        url: String,
        body: TaskActionRequest,
    ): SubmitActionResponse = wrapHttpErrors { service.submitAction(url, body, TaskBridgeContextTag(context)) }

    /**
     * Companion object for creating instances of [OkHttpTaskBridgeHttpApi].
     */
    companion object {
        /**
         * Creates a new [TaskBridgeHttpApi] using the provided client and configuration.
         */
        fun <Ctx> create(
            baseUrl: String,
            okHttpClient: OkHttpClient,
            json: Json,
        ): TaskBridgeHttpApi<Ctx> {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val mediaType = "application/json".toMediaType()
            val service =
                Retrofit
                    .Builder()
                    .baseUrl(normalized)
                    .client(okHttpClient)
                    .addConverterFactory(json.asConverterFactory(mediaType))
                    .build()
                    .create(RetrofitTaskBridgeHttpApi::class.java)
            return OkHttpTaskBridgeHttpApi(service, json)
        }
    }
}

private interface RetrofitTaskBridgeHttpApi {
    @POST
    suspend fun createTaskJson(
        @Url url: String,
        @Body body: TaskCreateJsonRequest,
        @Tag context: TaskBridgeContextTag?,
    ): TaskCreatedResponse

    @Multipart
    @POST
    @Suppress("LongParameterList")
    suspend fun createTaskMultipart(
        @Url url: String,
        @Part("clientRequestId") clientRequestId: RequestBody,
        @Part("taskType") taskType: RequestBody,
        @Part("input") input: RequestBody?,
        @Part("metadata") metadata: RequestBody?,
        @Part attachments: List<MultipartBody.Part>,
        @Tag context: TaskBridgeContextTag?,
    ): TaskCreatedResponse

    @GET
    suspend fun pollEvents(
        @Url url: String,
        @Query("afterEventId") afterEventId: String?,
        @Query("waitTimeoutMs") waitTimeoutMs: Int,
        @Query("maxEvents") maxEvents: Int,
        @Tag context: TaskBridgeContextTag?,
    ): Response<PollEventsResponse>

    @POST
    suspend fun cancelTask(
        @Url url: String,
        @Body body: CancelTaskBody? = null,
        @Tag context: TaskBridgeContextTag?,
    ): CancelTaskResponse

    @POST
    suspend fun submitAction(
        @Url url: String,
        @Body body: TaskActionRequest,
        @Tag context: TaskBridgeContextTag?,
    ): SubmitActionResponse
}

private suspend fun <T> wrapHttpErrors(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        throw TaskBridgeHttpStatusException(e.code(), e)
    }

private fun TaskBridgeMultipartAttachment.toPart(): MultipartBody.Part =
    MultipartBody.Part.createFormData(
        fieldName,
        fileName,
        content.toRequestBody(contentType.toMediaType()),
    )

private fun String.toPlainBody(): RequestBody = toRequestBody("text/plain; charset=utf-8".toMediaType())
