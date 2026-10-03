package me.waveio.claudebot.platform

import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Shared by the native preview and offline JVM decoding checks. */
internal fun pairingQrDecoderFactory() = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
