package com.ingemark.requestage;

import static com.ingemark.requestage.Message.INIT;
import static com.ingemark.requestage.StressTester.fac;
import static com.ingemark.requestage.Util.nettySend;
import static com.ingemark.requestage.Util.now;
import static com.ingemark.requestage.Util.sneakyThrow;
import static com.ingemark.requestage.script.JsFunctions.parseXml;
import static com.ingemark.requestage.script.JsFunctions.prettyXml;
import static org.mozilla.javascript.Context.getCurrentContext;
import static org.mozilla.javascript.Context.javaToJS;
import static org.mozilla.javascript.ScriptRuntime.constructError;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.json.JsonParser;
import org.mozilla.javascript.json.JsonParser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ingemark.requestage.script.JdomBuilder;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpClientConfig.Builder;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Response;

public class JsHttp extends BaseFunction
{
  private static final Logger log = LoggerFactory.getLogger(JsHttp.class);
  private static final Map<String, Acceptor> acceptors = hashMap(
      "any", new Acceptor() { public boolean acceptable(Response r) { return true; } },
      "success", new Acceptor() { public boolean acceptable(Response r) {
        final int st = r.getStatusCode();
        return st >= 200 && st < 400;
      }},
      "ok", new Acceptor() { public boolean acceptable(Response r) {
        final int st = r.getStatusCode();
        return st >= 200 && st < 300;
      }}
    );
  private final StressTester tester;
  volatile int index;
  volatile Acceptor acceptor = acceptors.get("any");

  public JsHttp(ScriptableObject parentScope, StressTester tester) {
    super(parentScope, getFunctionPrototype(parentScope));
    this.tester = tester;
    defineHttpMethods("get", "put", "post", "delete", "head", "options");
    putProperty(this, "acceptableStatus", new Callable() {
      public Object call(Context _1, Scriptable _2, Scriptable _3, Object[] args) {
        return acceptor = acceptors.get(args[0]);
    }});
  }

  public void initDone() { index = -1; }

  @Override public Object call(Context _1, Scriptable _2, Scriptable _3, Object[] args) {
    return new ReqBuilder(ScriptRuntime.toString(args[0]));
  }
  @Override public int getArity() { return 1; }

  public class ReqBuilder {
    final String name;
    public BoundRequestBuilder brb;
    private Acceptor acceptor = JsHttp.this.acceptor;

    ReqBuilder(String name) { this.name = name; }
    ReqBuilder(String method, String url) { this(null); brb(method, url); }

    public ReqBuilder get(String url) { return brb("GET", url); }
    public ReqBuilder put(String url) { return brb("PUT", url); }
    public ReqBuilder post(String url) { return brb("POST", url); }
    public ReqBuilder delete(String url) { return brb("DELETE", url); }
    public ReqBuilder head(String url) { return brb("HEAD", url); }
    public ReqBuilder options(String url) { return brb("OPTIONS", url); }

    public ReqBuilder body(final Object body) {
      if (body instanceof JdomBuilder) {
        brb.addHeader("Content-Type", "text/xml;charset=UTF-8");
        brb.setBody(body.toString());
      } else if (body instanceof Scriptable) {
        brb.addHeader("Content-Type", "application/json;charset=UTF-8");
        fac.call(new ContextAction() { @Override public Object run(Context cx) {
          brb.setBody((String)NativeJSON.stringify(cx, getParentScope(), body, null, ""));
          return null;
        }});
      }
      else brb.setBody(body.toString());
      return this;
    }

    private ReqBuilder brb(String method, String url) {
      brb = tester.client.prepareConnect(url).setMethod(method.toUpperCase());
      return this;
    }

    public ReqBuilder accept(String qualifier) {
      acceptor = acceptors.get(qualifier);
      return this;
    }
    public void go() { go1(null, true); }
    public void go(Callable f) { go1(f, false); }
    public void goDiscardingBody(Callable f) { go1(f, true); }

    private void go1(Callable f, boolean discardBody) {
      if (index >= 0) executeInit(this, f, discardBody); else executeTest(this, f, discardBody);
    }

    private void executeInit(ReqBuilder reqBuilder, Callable f, final boolean discardBody) {
      if (reqBuilder.name != null) {
        log.debug("Adding " + reqBuilder.name + " under " + index);
        nettySend(tester.channel, new Message(INIT, reqBuilder.name));
        tester.lsmap.put(reqBuilder.name, new LiveStats(index++, reqBuilder.name));
      }
      try {
        handleResponse(reqBuilder.brb.execute(new AsyncCompletionHandlerBase() {
          public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            return discardBody? STATE.CONTINUE : super.onBodyPartReceived(bodyPart); }}
        ).get(), f);
      } catch (Exception e) { sneakyThrow(e); }
    }

    private void executeTest(ReqBuilder reqBuilder, final Callable f, final boolean discardBody) {
      final String reqName = reqBuilder.name;
      if (reqName == null) throw constructError("NoName",
          "Attempt to execute an unnamed request in test phase");
      final LiveStats liveStats = tester.lsmap.get(reqName);
      if (liveStats == null) throw constructError("NotRegistered",
          String.format("Request %s was not registered in init phase", reqName));
      final int startSlot = liveStats.registerReq();
      final long start = now();
      try {
        reqBuilder.brb.execute(new AsyncCompletionHandler<Void>() {
          public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            return discardBody? STATE.CONTINUE : super.onBodyPartReceived(bodyPart);
          }
          @Override public Void onCompleted(final Response resp) {
            fac.call(new ContextAction() {
              @Override public Object run(Context cx) {
                Throwable failure = null;
                try { handleResponse(resp, f); }
                catch (Throwable t) { failure = t; }
                finally { liveStats.deregisterReq(startSlot, now(), start, failure); }
                return null;
              }
            });
            return null;
          }
          @Override public void onThrowable(Throwable t) {
            liveStats.deregisterReq(startSlot, now(), start, t);
          }
      });
      } catch (IOException e) { sneakyThrow(e); }
    }

    private void handleResponse(Response resp, Callable f) {
      if (!acceptor.acceptable(resp))
        throw constructError("FailedResponse", resp.getStatusCode() + " " + resp.getStatusText());
      if (f != null) tester.jsScope.call(f, betterResponse(resp));
    }
  }

  public Scriptable betterAhccBuilder(final AsyncHttpClientConfig.Builder b) {
    return (Scriptable)fac.call(new ContextAction() {
      @Override public Object run(Context cx) {
        final Scriptable bb = (Scriptable) javaToJS(new BetterAhccBuilder(b), getParentScope());
        bb.setPrototype((Scriptable) javaToJS(b, getParentScope()));
        return bb;
      }
    });
  }
  public class BetterAhccBuilder {
    private final Builder b;
    BetterAhccBuilder(Builder b) { this.b = b; }
    public Object proxy(String proxyStr) {
      b.setProxyServer(toProxyServer(proxyStr));
      return this;
    }
  }
  private static ProxyServer toProxyServer(String proxyString) {
    if (proxyString == null) return null;
    final String[] parts = proxyString.split(":");
    return new ProxyServer(parts[0], parts.length > 1? Integer.valueOf(parts[1]) : 80);
  }

  Scriptable betterResponse(Response r) {
    final Scriptable br = (Scriptable) javaToJS(new BetterResponse(r), getParentScope());
    br.setPrototype((Scriptable) javaToJS(r, getParentScope()));
    return br;
  }
  public class BetterResponse {
    private final Response r;
    BetterResponse(Response r) { this.r = r; }
    public Object xmlBody() { return parseXml(this.r); }
    public Object prettyXmlBody() { return prettyXml(r); }
    public Object jsonBody() {
      try { return new JsonParser(getCurrentContext(), getParentScope()).parseValue(
            responseBody(this.r)); }
      catch (ParseException e) { return sneakyThrow(e); }
    }
    public String stringBody() { return responseBody(this.r); }
    @Override public String toString() { return stringBody(); }
  }

  static String responseBody(Response r) {
    try { return r.getResponseBody(); } catch (IOException e) { return sneakyThrow(e); }
  }

  private static Map<String, Acceptor> hashMap(Object... kvs) {
    final Map<String, Acceptor> r = new HashMap<String, Acceptor>();
    for (int i = 0; i < kvs.length;) r.put((String)kvs[i++], (Acceptor)kvs[i++]);
    return r;
  }

  private void defineHttpMethods(String... methods) {
    for (final String m : methods) putProperty(this, m, new Callable() {
      public Object call(Context _1, Scriptable _2, Scriptable _3, Object[] args) {
        return new ReqBuilder(m, ScriptRuntime.toString(args[0]));
      }
    });
  }

  interface Acceptor { boolean acceptable(Response r); }
}
