package com.litmus.worldscope.utility;

import android.content.Context;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.litmus.worldscope.model.WorldScopeUser;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashSet;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.GsonConverterFactory;
import retrofit2.Retrofit;

/**
 * WorldScopeRestAPI creates instances of API calls through RetroFit and other libraries
 * Cookies are also intercepted and stored
 * Think of this class as the gun and WorldScopeAPIService as the bullets
 */
public class WorldScopeRestAPI {

    private static final String TAG = "WorldScopeRestAPI";

    private static final String cookiesSetTag = "PREF_COOKIES";

    private static final String cookiesHeaderTag = "cookie";

    private static final String setCookiesHeaderTag = "set-cookie";

    private Context context;

    private OkHttpClient okHttpClient;
    static Retrofit.Builder retrofitBuilder;

    public WorldScopeRestAPI(Context context) {
        this.context = context;

        // Set up interceptors for cookies with Context given
        okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(new AddCookiesInterceptor())
            .addInterceptor(new SaveCookiesInterceptor())
            .build();
    }

    public WorldScopeAPIService.WorldScopeAPIInterface buildWorldScopeAPIService() {

        // Create an instance of the GsonBuilder to pass JSON results
        GsonBuilder  gsonBuilder = new GsonBuilder();
        gsonBuilder.setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE);

        // TODO: Consider putting a switch here
        gsonBuilder.registerTypeAdapter(WorldScopeUser.class, new WorldScopeJsonDeserializer<WorldScopeUser>());

        // Create an instance of RetrofitBuilder to build the API for use
        Retrofit retrofit = getRetrofitBuilderInstance()
                .baseUrl(WorldScopeAPIService.WorldScopeURL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build();

        Log.d(TAG, "Returning created service");
        return retrofit.create(WorldScopeAPIService.WorldScopeAPIInterface.class);
    }

    /**
     * Returns the instance of Retrofit.Builder required to build requests
     * @return Retrofit.Builder
     */
    private static Retrofit.Builder getRetrofitBuilderInstance() {
        if(retrofitBuilder == null) {
            return new Retrofit.Builder();
        } else {
          return retrofitBuilder;
        }
    }

    private static class WorldScopeJsonDeserializer<T> implements JsonDeserializer<T> {
        @Override
        public T deserialize(JsonElement je, Type type, JsonDeserializationContext jdc) throws JsonParseException {
            JsonElement content = je.getAsJsonObject();

            return new Gson().fromJson(content, type);
        }
    }

    // AddCookiesInterceptor appends cookies to request
    public class AddCookiesInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {

            Log.d(TAG, "Request: " + chain.request().toString());
            Log.d(TAG, "Request URL: " + chain.request().url());
            Log.d(TAG, "Request Method: " + chain.request().method());

            HashSet<String> preferences = (HashSet<String>) PreferenceManager.getDefaultSharedPreferences(context)
                    .getStringSet(cookiesSetTag, new HashSet<String>());

            // If the request is login, clear previous cookies (cookies not required for login)
            if(chain.request().method().equals("POST") && chain.request().url().toString().equals(WorldScopeAPIService.WorldScopeURL + "/api/users/login")) {
                Log.d(TAG, "Login detected, clearing cookies");
                preferences.clear();

                // Clear cookies from WorldScopeAPIService
                WorldScopeAPIService.resetCookie();
            }

            Request.Builder builder = chain.request().newBuilder();
            for(String cookie: preferences) {
                builder.addHeader(cookiesHeaderTag, cookie);
                Log.d(TAG, "Header: " + cookiesHeaderTag + "=" + cookie);
            }

            return chain.proceed(builder.build());

        }
    }

    // SaveCookiesInterceptor saves cookies received
    public class SaveCookiesInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response originalResponse = chain.proceed(chain.request());

            Log.d(TAG, "Response: " + originalResponse.toString());

            if(!originalResponse.headers(setCookiesHeaderTag).isEmpty()) {
                HashSet<String> cookies = new HashSet<>();

                for(String header: originalResponse.headers(setCookiesHeaderTag)) {

                    // Remove the extra "; HttpOnly; Path=/" behind cookie
                    int cookieEndIndex = header.indexOf(";");
                    if(cookieEndIndex > 0) {
                        header = header.substring(0, cookieEndIndex);
                        cookies.add(header);

                        // Set the cookie in WorldScopeAPIService
                        WorldScopeAPIService.setCookie(header);

                        Log.d(TAG, "Cookies saved: " + header);
                    }
                }

                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putStringSet(cookiesSetTag, cookies)
                    .apply();
            }
            return originalResponse;
        }

    }
}
