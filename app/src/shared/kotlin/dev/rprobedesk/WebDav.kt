package dev.rprobedesk

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.TimeUnit

internal data class DavConfig(val server:String=DEFAULT_SERVER,val account:String="",val password:String="",
                             val root:String="r-probe-desk-sync",val profile:String="default") {
    companion object { const val DEFAULT_SERVER="https://dav.jianguoyun.com/dav/" }
    override fun toString()="WebDAV 配置"
}

internal class WebDav(private val c:DavConfig) {
    private val server=c.server.trim().trimEnd('/').toHttpUrl()
    private val directories:List<HttpUrl>
    private val file:HttpUrl
    init {
        require(server.username.isEmpty() && server.password.isEmpty() && server.query==null && server.fragment==null) { "请输入不含账户、查询参数或片段的服务器地址" }
        require(c.account.isNotBlank() && ':' !in c.account && c.password.isNotEmpty()) { "请填写账户与应用密码" }
        val parts=c.root.trim('/').split('/')+c.profile
        require(parts.all { it.isNotBlank() && it !in listOf(".","..") && it.none { ch -> ch=='/' || ch.code==92 || ch.isISOControl() } }) { "请填写有效的远程目录和配置名" }
        var url=server
        directories=parts.map { segment -> url=url.newBuilder().addPathSegment(segment).build(); url }
        file=url.newBuilder().addPathSegment("workspace.properties").build()
    }
    private fun request(method:String,url:HttpUrl,body:RequestBody?=null):Response {
        val request=Request.Builder().url(url).method(method,body)
            .header("Authorization",Credentials.basic(c.account,c.password,Charsets.UTF_8))
        if(method=="PROPFIND") request.header("Depth","0")
        return client.newCall(request.build()).execute()
    }
    private fun accepted(code:Int,allowed:Boolean) {
        check(allowed) { when(code) {
            401,403 -> "WebDAV 拒绝访问，请检查账户、应用密码和权限（HTTP $code）"
            404 -> "远程路径或同步配置不存在（HTTP 404）"
            else -> "WebDAV 请求未完成（HTTP $code）"
        } }
    }
    fun test() {
        val collection=server.newBuilder().addPathSegment("").build()
        request("PROPFIND",collection,ByteArray(0).toRequestBody()).use { accepted(it.code,it.code==207) }
    }
    fun upload(records:Properties) {
        val bytes=ByteArrayOutputStream().also { records.store(it,null) }.toByteArray()
        require(bytes.size<=LIMIT) { "同步配置超过 4 MiB" }
        for(dir in directories) request("MKCOL",dir.newBuilder().addPathSegment("").build()).use { accepted(it.code,it.code==201 || it.code==405) }
        request("PUT",file,bytes.toRequestBody("application/octet-stream".toMediaType())).use { accepted(it.code,it.isSuccessful) }
    }
    fun download():Properties = request("GET",file).use { response ->
        accepted(response.code,response.code==200)
        val source=requireNotNull(response.body).source()
        require(!source.request(LIMIT.toLong()+1)) { "远程配置超过 4 MiB" }
        Properties().apply { load(source.inputStream()) }
    }
    companion object {
        private const val LIMIT=4*1024*1024
        private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30,TimeUnit.SECONDS).build()
    }
}
