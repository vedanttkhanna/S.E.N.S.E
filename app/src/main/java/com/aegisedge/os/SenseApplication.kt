package com.aegisedge.os

import android.app.Application
import android.util.Log
import com.aegisedge.os.core.inference.ModelRegistry

class SenseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val registry = ModelRegistry(this)
        listOf(
            ModelRegistry.Names.NANO_YOLO,
            ModelRegistry.Names.AUDIO_CLASSIFIER,
            ModelRegistry.Names.PALIGEMMA_3B,
            ModelRegistry.Names.GEMMA3_1B,
            ModelRegistry.Names.SHIELDGEMMA_2B,
        ).forEach { name ->
            Log.i("S.E.N.S.E.", "model $name available=${registry.isAvailable(name)}")
        }
    }
}
