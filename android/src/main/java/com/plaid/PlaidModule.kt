package com.plaid

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.text.TextUtils
import android.util.Log
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.plaid.gson.PlaidJsonConverter
import com.plaid.link.Plaid
import com.plaid.link.configuration.LinkLogLevel
import com.plaid.link.configuration.LinkTokenConfiguration
import com.plaid.link.event.LinkEvent
import com.plaid.link.exception.LinkException
import com.plaid.link.result.LinkAccountSubtype
import com.plaid.link.result.LinkResultHandler
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList

@ReactModule(name = PlaidModule.TAG)
class PlaidModule internal constructor(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext), ActivityEventListener {

  val mActivityResultManager by lazy { ActivityResultManager() }

  private val jsonConverter by lazy { PlaidJsonConverter() }

  private var onSuccessCallback: Callback? = null
  private var onExitCallback: Callback? = null

  companion object {
    private const val LINK_TOKEN_PREFIX = "link"

    const val TAG = "PlaidAndroid"
  }

  override fun getName(): String {
    return PlaidModule.TAG
  }

  override fun initialize() {
    super.initialize()
    reactApplicationContext.addActivityEventListener(this)
  }

  override fun onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy()
    reactApplicationContext.removeActivityEventListener(this)
  }

  private fun getLinkTokenConfiguration(
    token: String,
    noLoadingState: Boolean,
    logLevel: LinkLogLevel,
  ): LinkTokenConfiguration? {
    if (token == null) {
      return null
    }

    if (!token.startsWith(LINK_TOKEN_PREFIX)) {
      return null
    }

    val builder = LinkTokenConfiguration.Builder()
      .token(token)
      .logLevel(logLevel)
      .noLoadingState(noLoadingState)

    return builder.build()
  }

  @ReactMethod
  @Suppress("unused")
  fun startLinkActivityForResult(
    token: String,
    noLoadingState: Boolean,
    logLevel: String,
    onSuccessCallback: Callback,
    onExitCallback: Callback
  ) {
    val activity = currentActivity ?: throw IllegalStateException("Current activity is null")
    this.onSuccessCallback = onSuccessCallback
    this.onExitCallback = onExitCallback

    try {
      Plaid.setLinkEventListener { linkEvent: LinkEvent ->
        var json = jsonConverter.convert(linkEvent)
        val eventMap = convertJsonToMap(JSONObject(json))
        reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
          .emit("onEvent", eventMap)
      }

      val tokenConfiguration = getLinkTokenConfiguration(token, noLoadingState, getLogLevel(logLevel))
      tokenConfiguration?.let {
        Plaid.create(
          reactApplicationContext.getApplicationContext() as Application,
          it
        ).open(activity)
        return
      }

      throw LinkException("Unable to open link, please check that your configuration is valid")
    } catch (ex: JSONException) {
      Log.e("PlaidModule", ex.toString())
      throw ex
    }
  }

  private fun maybeGetStringField(obj: JSONObject, fieldName: String): String? {
    if (obj.has(fieldName) && !TextUtils.isEmpty(obj.getString(fieldName))) {
      return obj.getString(fieldName)
    }
    return null
  }

  private fun maybeGetBooleanField(obj: JSONObject, fieldName: String): Boolean? {
    if (obj.has(fieldName)) {
      return obj.getBoolean(fieldName);
    }
    return null
  }

  private fun getLogLevel(string: String): LinkLogLevel {
    when (string) {
      "debug" -> return LinkLogLevel.DEBUG
      "info" -> return LinkLogLevel.INFO
      "warn" -> return LinkLogLevel.WARN
      "error" ->  return LinkLogLevel.ERROR
      else -> {
        return LinkLogLevel.ASSERT
      }
    }
  }

  override fun onActivityResult(
    activity: Activity,
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {

    // Dispath to embedded to handle the callback.
    if (mActivityResultManager[requestCode] != null) {
      mActivityResultManager.dispatch(requestCode, resultCode, data)
      return
    }

    val linkHandler = LinkResultHandler(
      onSuccess = { success ->
        val result = convertJsonToMap(JSONObject(jsonConverter.convert(success)))
        print(result)
        this.onSuccessCallback?.invoke(result)
      },
      onExit = { exit ->
        val result = convertJsonToMap(JSONObject(jsonConverter.convert(exit)))
        print(result)
        this.onExitCallback?.invoke(result)
      }
    )

    if (linkHandler.onActivityResult(requestCode, resultCode, data)) {
      return
    } else {
      Log.i("PlaidModule", "Result code not handled.")
    }
    return
  }

  override fun onNewIntent(intent: Intent) {
    // Do Nothing
  }
}
