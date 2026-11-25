package io.github.velcheor137.shiori

import android.app.Application
import android.os.Build
import io.github.velcheor137.shiori.core.network.ApiHostConfig
import io.github.velcheor137.shiori.core.network.LibSocialHttp
import io.github.velcheor137.shiori.core.security.TokenVault
import io.github.velcheor137.shiori.data.auth.AuthRepository

class ShioriApp : Application() {
    lateinit var auth: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        auth = AuthRepository(TokenVault(this))
        auth.http = LibSocialHttp(
            config = ApiHostConfig(),
            userAgent = LibSocialHttp.userAgent(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE),
            tokenProvider = auth,
        )
    }
}
