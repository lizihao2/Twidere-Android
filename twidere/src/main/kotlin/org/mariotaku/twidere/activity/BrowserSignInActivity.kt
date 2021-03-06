/*
 * 				Twidere - Twitter client for Android
 * 
 *  Copyright (C) 2012-2014 Mariotaku Lee <mariotaku.lee@gmail.com>
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.text.TextUtils.isEmpty
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_browser_sign_in.*
import org.attoparser.ParseException
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.twitter.TwitterOAuth
import org.mariotaku.restfu.oauth.OAuthAuthorization
import org.mariotaku.restfu.oauth.OAuthToken
import org.mariotaku.twidere.BuildConfig
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants
import org.mariotaku.twidere.TwidereConstants.LOGTAG
import org.mariotaku.twidere.constant.IntentConstants.*
import org.mariotaku.twidere.extension.newMicroBlogInstance
import org.mariotaku.twidere.model.CustomAPIConfig
import org.mariotaku.twidere.model.SingleResponse
import org.mariotaku.twidere.util.AsyncTaskUtils
import org.mariotaku.twidere.util.MicroBlogAPIFactory
import org.mariotaku.twidere.util.OAuthPasswordAuthenticator
import org.mariotaku.twidere.util.webkit.DefaultWebViewClient
import java.io.IOException
import java.io.StringReader

@SuppressLint("SetJavaScriptEnabled")
class BrowserSignInActivity : BaseActivity() {

    private var requestToken: OAuthToken? = null
    private var task: GetRequestTokenTask? = null

    public override fun onDestroy() {
        loaderManager.destroyLoader(0)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser_sign_in)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookie()
        } else {
            CookieManager.getInstance().removeAllCookies(null)
        }
        webView.setWebViewClient(AuthorizationWebViewClient(this))
        webView.isVerticalScrollBarEnabled = false
        webView.addJavascriptInterface(InjectorJavaScriptInterface(this), "injector")
        val webSettings = webView.settings
        webSettings.loadsImagesAutomatically = true
        webSettings.javaScriptEnabled = true
        webSettings.blockNetworkImage = false
        webSettings.saveFormData = true
        getRequestToken()
    }

    private fun getRequestToken() {
        if (requestToken != null || task != null && task!!.status == AsyncTask.Status.RUNNING)
            return
        task = GetRequestTokenTask(this)
        AsyncTaskUtils.executeTask<GetRequestTokenTask, Any>(task)
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    private fun readOAuthPin(html: String): String? {
        try {
            val data = OAuthPasswordAuthenticator.OAuthPinData()
            OAuthPasswordAuthenticator.readOAuthPINFromHtml(StringReader(html), data)
            return data.oauthPin
        } catch (e: ParseException) {
            Log.w(LOGTAG, e)
        } catch (e: IOException) {
            Log.w(LOGTAG, e)
        }

        return null
    }

    private fun setLoadProgressShown(shown: Boolean) {
        progressContainer.visibility = if (shown) View.VISIBLE else View.GONE
    }

    private fun setRequestToken(token: OAuthToken) {
        requestToken = token
    }

    internal class AuthorizationWebViewClient(activity: BrowserSignInActivity) : DefaultWebViewClient(activity) {

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.loadUrl(INJECT_CONTENT)
            val activity = activity as BrowserSignInActivity
            activity.setLoadProgressShown(false)
            val uri = Uri.parse(url)
            // Hack for fanfou
            if ("fanfou.com" == uri.host) {
                val path = uri.path
                val paramNames = uri.queryParameterNames
                if ("/oauth/authorize" == path && paramNames.contains("oauth_callback")) {
                    // Sign in successful response.
                    val requestToken = activity.requestToken
                    if (requestToken != null) {
                        val intent = Intent()
                        intent.putExtra(EXTRA_REQUEST_TOKEN, requestToken.oauthToken)
                        intent.putExtra(EXTRA_REQUEST_TOKEN_SECRET, requestToken.oauthTokenSecret)
                        activity.setResult(Activity.RESULT_OK, intent)
                        activity.finish()
                    }
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            (activity as BrowserSignInActivity).setLoadProgressShown(true)
        }

        override fun onReceivedError(view: WebView, errorCode: Int, description: String?,
                                     failingUrl: String?) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            val activity = activity
            Toast.makeText(activity, description, Toast.LENGTH_SHORT).show()
            activity.finish()
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val uri = Uri.parse(url)
            if (url.startsWith(TwidereConstants.OAUTH_CALLBACK_URL)) {
                val oauthVerifier = uri.getQueryParameter(EXTRA_OAUTH_VERIFIER)
                val activity = activity as BrowserSignInActivity
                val requestToken = activity.requestToken
                if (oauthVerifier != null && requestToken != null) {
                    val intent = Intent()
                    intent.putExtra(EXTRA_OAUTH_VERIFIER, oauthVerifier)
                    intent.putExtra(EXTRA_REQUEST_TOKEN, requestToken.oauthToken)
                    intent.putExtra(EXTRA_REQUEST_TOKEN_SECRET, requestToken.oauthTokenSecret)
                    activity.setResult(Activity.RESULT_OK, intent)
                    activity.finish()
                }
                return true
            }
            return false
        }

    }

    internal class GetRequestTokenTask(private val activity: BrowserSignInActivity) : AsyncTask<Any, Any, SingleResponse<OAuthToken>>() {
        private val apiConfig: CustomAPIConfig

        init {
            val intent = activity.intent
            apiConfig = intent.getParcelableExtra<CustomAPIConfig>(EXTRA_API_CONFIG)
        }

        override fun doInBackground(vararg params: Any): SingleResponse<OAuthToken> {
            if (isEmpty(apiConfig.consumerKey) || isEmpty(apiConfig.consumerSecret)) {
                return SingleResponse()
            }
            try {
                val endpoint = MicroBlogAPIFactory.getOAuthSignInEndpoint(apiConfig.apiUrlFormat,
                        apiConfig.isSameOAuthUrl)
                val auth = OAuthAuthorization(apiConfig.consumerKey, apiConfig.consumerSecret)
                val oauth = newMicroBlogInstance(activity, endpoint,
                        auth, true, null, TwitterOAuth::class.java)
                return SingleResponse(oauth.getRequestToken(TwidereConstants.OAUTH_CALLBACK_OOB), null, Bundle())
            } catch (e: MicroBlogException) {
                return SingleResponse(null, e, Bundle())
            }

        }

        override fun onPostExecute(result: SingleResponse<OAuthToken>) {
            activity.setLoadProgressShown(false)
            if (result.hasData()) {
                val token = result.data!!
                activity.setRequestToken(token)
                val endpoint = MicroBlogAPIFactory.getOAuthSignInEndpoint(apiConfig.apiUrlFormat, true)
                activity.loadUrl(endpoint.construct("/oauth/authorize", arrayOf("oauth_token", token.oauthToken)))
            } else {
                if (BuildConfig.DEBUG && result.hasException()) {
                    Log.w(LOGTAG, "Exception while browser sign in", result.exception)
                }
                if (!activity.isFinishing) {
                    Toast.makeText(activity, R.string.error_occurred, Toast.LENGTH_SHORT).show()
                    activity.finish()
                }
            }
        }

        override fun onPreExecute() {
            activity.setLoadProgressShown(true)
        }

    }

    internal class InjectorJavaScriptInterface(private val activity: BrowserSignInActivity) {

        @JavascriptInterface
        fun processHTML(html: String) {
            val oauthVerifier = activity.readOAuthPin(html)
            val requestToken = activity.requestToken
            if (oauthVerifier != null && requestToken != null) {
                val intent = Intent()
                intent.putExtra(EXTRA_OAUTH_VERIFIER, oauthVerifier)
                intent.putExtra(EXTRA_REQUEST_TOKEN, requestToken.oauthToken)
                intent.putExtra(EXTRA_REQUEST_TOKEN_SECRET, requestToken.oauthTokenSecret)
                activity.setResult(Activity.RESULT_OK, intent)
                activity.finish()
            }
        }
    }

    companion object {

        private val INJECT_CONTENT = "javascript:window.injector.processHTML('<head>'+document.getElementsByTagName('html')[0].innerHTML+'</head>');"
    }
}
