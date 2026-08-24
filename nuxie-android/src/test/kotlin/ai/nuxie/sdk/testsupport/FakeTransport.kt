package ai.nuxie.sdk.testsupport

import ai.nuxie.sdk.network.HttpTransport

/** Records requests; scripts responses. Default: 200 with an empty profile. */
internal class FakeTransport : HttpTransport {
    val requests = mutableListOf<HttpTransport.Request>()
    var respond: (HttpTransport.Request) -> HttpTransport.Response = { request ->
        if (request.url.path == "/profile") {
            HttpTransport.Response(200, """{"segments":[]}""".encodeToByteArray())
        } else {
            HttpTransport.Response(200, ByteArray(0))
        }
    }

    override fun execute(request: HttpTransport.Request): HttpTransport.Response {
        requests.add(request)
        return respond(request)
    }
}
