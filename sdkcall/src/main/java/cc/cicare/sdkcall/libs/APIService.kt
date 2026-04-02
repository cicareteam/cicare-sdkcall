package cc.cicare.sdkcall.libs


import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
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

data class CallSipRequest(
    val callerId: String,
    val callerName: String,
    val callerAvatar: String,
    val destination: String,
)

data class CallResponse(
    @SerializedName("token")
    val token: String,
    val callee: String,
    val server: String
)

data class EncryptKeyResponse(
    val key: String
)

data class Error(
    val code: Int,
    val message: String,
    val details: Any? = null
)

sealed class CallResult {
    data class Success(val data: CallResponse): CallResult()
    data class Failure(val error: Error): CallResult()
}

interface ApiService {
    @POST("api/sdk-call/one2one")
    suspend fun requestCall(@Body request: CallRequest): Response<CallResponse>

    @POST("api/sdk-call/app2phone")
    suspend fun requestCallSip(@Body request: CallSipRequest): Response<CallResponse>

    @GET("api/u/encrypt-key")
    suspend fun getEncryptKey(): Response<EncryptKeyResponse>
}

object ApiClient {
    var BASE_URL = "" // untuk emulator Android
    var AUTH_TOKEN = "" // Ganti dengan token dinamis jika perlu

    val api: ApiService by lazy {
        if (BASE_URL.isBlank()) {
            throw IllegalStateException("BASE_URL cannot be empty.")
        }

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

object CallRepository {
    suspend fun getEncryptKey(): String? {
        return try {
            val response = ApiClient.api.getEncryptKey()
            if (response.isSuccessful) response.body()?.key else null
        } catch (e: Exception) {
            android.util.Log.e("SDK CALL", "Failed to fetch encrypt key: ${e.message}")
            null
        }
    }

    suspend fun requestCall(request: CallRequest): CallResult {
        return try {
            val response = ApiClient.api.requestCall(request)
            if (response.isSuccessful && response.body() != null) {
                CallResult.Success(response.body()!!)
            } else {
                CallResult.Failure(
                    Error(
                        code = response.code(),
                        message = response.errorBody()?.string() ?: "Unknown error"
                    )
                )
            }
        } catch (e: Exception) {
            CallResult.Failure(Error(code = -1, message = e.localizedMessage ?: "Unexpected error"))
        }
    }
    suspend fun requestCallSip(request: CallSipRequest): CallResult {
        return try {
            val response = ApiClient.api.requestCallSip(request)
            if (response.isSuccessful && response.body() != null) {
                CallResult.Success(response.body()!!)
            } else {
                CallResult.Failure(
                    Error(
                        code = response.code(),
                        message = response.errorBody()?.string() ?: "Unknown error"
                    )
                )
            }
        } catch (e: Exception) {
            CallResult.Failure(Error(code = -1, message = e.localizedMessage ?: "Unexpected error"))
        }
    }
}