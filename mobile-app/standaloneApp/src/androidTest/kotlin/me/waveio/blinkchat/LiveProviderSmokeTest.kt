package me.waveio.blinkchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Opt-in only: the operator supplies a short-lived private fixture, never a test argument. */
@RunWith(AndroidJUnit4::class)
class LiveProviderSmokeTest {
    @Test fun qwenCloudTextVisionAndImageGeneration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveValidation") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.filesDir,"live-validation-key")
        assertTrue("Supply the opt-in key fixture in app-private storage",fixture.isFile)
        val key = fixture.readText().trim()
        val storage = ChatStorage(context)
        val provider = ProviderClient()
        try {
            storage.writeApiKey(key)
            assertEquals(key,storage.readApiKey())
            val bitmap = Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawCircle(96f,96f,60f,Paint().apply { color=Color.RED })
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            bitmap.recycle()
            val image = "data:image/png;base64," + android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
            val settings = ProviderSettings(chatModel="qwen3.8-flash",toolsEnabled=false)
            val reply = provider.chat(settings,key,listOf(ApiMessage("user","Describe the shape and its color in this image in at most six English words.",listOf(image))))
            assertTrue("The provider did not describe the supplied image",reply.text.contains("red",true) && reply.text.contains("circle",true))
            val generated = provider.generateImage(settings,key,"A simple blue ceramic cup on a plain white background, square composition, no text.")
            assertTrue(generated.bytes.size > 1000)
            val decoded = BitmapFactoryBounds.check(generated.bytes)
            assertTrue(decoded)
        } finally {
            provider.close()
            storage.writeApiKey(null)
            fixture.delete()
        }
    }
}

private object BitmapFactoryBounds {
    fun check(bytes:ByteArray):Boolean {
        val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        return options.outWidth > 0 && options.outHeight > 0
    }
}
