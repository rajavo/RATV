package com.ratv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ratv.app.data.Http
import com.ratv.app.data.Prefs
import com.ratv.app.data.Repository

class RatvApp : Application(), ImageLoaderFactory {

    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = Repository(this, Prefs(this))
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Http.client }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()

    companion object {
        lateinit var instance: RatvApp
            private set
    }
}
