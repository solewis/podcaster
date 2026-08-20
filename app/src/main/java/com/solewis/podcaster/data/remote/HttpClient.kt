package com.solewis.podcaster.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A single process-wide OkHttp client. Verified while building this app: podcast CDNs and the
 * iTunes Search API both work fine with an explicit User-Agent (some feed hosts reject the
 * default one); OkHttp's transparent gzip handling is preserved by NOT setting an explicit
 * Accept-Encoding header anywhere.
 */
object HttpClient {

    private const val USER_AGENT = "Podcaster/1.0 (Android; personal use, not for distribution)"

    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
