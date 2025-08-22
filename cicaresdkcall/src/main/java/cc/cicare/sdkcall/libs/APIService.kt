package cc.cicare.sdkcall.libs


import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class CallRequest(
    val callerId: String,
    val callerName: String,
    val callerAvatar: String,
    val calleeId: String,
    val calleeName: String,
    val calleeAvatar: String,
    val checkSum: String,
)

data class CallResponse(
    @SerializedName("token")
    val token: String,
    val server: String
)

interface ApiService {
    @POST("api/sdk-call/one2one")
    suspend fun requestCall(@Body request: CallRequest): Response<CallResponse>
}

object ApiClient {
    var BASE_URL = "https://sip-gw.c-icare.cc:8443/" // untuk emulator Android
    var AUTH_TOKEN = "xHNYBNtmnckl8GJXQoBSMQTz8oJsa3j5zKk5FK00Y5uOXGzwXcot7u5WM8gIpV8dFQsLNaaozMt8k3Y1fTSSxQyzOAMeuFPIzPNqJhk0GDvjHGkBBkeqZNFU5UlRF4aj" // Ganti dengan token dinamis jika perlu

    val api: ApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        }

        val authInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $AUTH_TOKEN")
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(ApiService::class.java)
    }
}