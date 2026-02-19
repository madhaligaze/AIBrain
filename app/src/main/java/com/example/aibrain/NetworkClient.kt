package com.example.aibrain

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 20L
    private const val WRITE_TIMEOUT_S = 20L

    fun buildOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor()
        logging.level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "AIBrain-Android/${BuildConfig.VERSION_NAME}")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()
    }

    fun buildApi(baseUrl: String): ApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
