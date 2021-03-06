package com.koushikdutta.async.http;

import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import com.koushikdutta.async.AsyncSSLSocketWrapper;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.LineEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ConnectCallback;
import com.koushikdutta.async.http.libcore.RawHeaders;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class AsyncSSLSocketMiddleware extends AsyncSocketMiddleware {
    static SSLContext defaultSSLContext;

    static {
        // following is the "trust the system" certs setup
        try {
            // critical extension 2.5.29.15 is implemented improperly prior to 4.0.3.
            // https://code.google.com/p/android/issues/detail?id=9307
            // https://groups.google.com/forum/?fromgroups=#!topic/netty/UCfqPPk5O4s
            // certs that use this extension will throw in Cipher.java.
            // fallback is to use a custom SSLContext, and hack around the x509 extension.
            if (Build.VERSION.SDK_INT <= 15)
                throw new Exception();
            defaultSSLContext = SSLContext.getInstance("Default");
        }
        catch (Exception ex) {
            try {
                defaultSSLContext = SSLContext.getInstance("TLS");
                TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                        for (X509Certificate cert : certs) {
                            if (cert != null && cert.getCriticalExtensionOIDs() != null)
                                cert.getCriticalExtensionOIDs().remove("2.5.29.15");
                        }
                    }
                } };
                defaultSSLContext.init(null, trustAllCerts, null);
            }
            catch (Exception ex2) {
                ex.printStackTrace();
                ex2.printStackTrace();
            }
        }
    }

    public static SSLEngine createDefaultSSLEngine() {
        return defaultSSLContext.createSSLEngine();
    }

    public AsyncSSLSocketMiddleware(AsyncHttpClient client) {
        super(client, "https", 443);
    }

    SSLContext sslContext;

    public void setSSLContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    TrustManager[] trustManagers;

    public void setTrustManagers(TrustManager[] trustManagers) {
        this.trustManagers = trustManagers;
    }

    HostnameVerifier hostnameVerifier;

    public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
    }

    List<AsyncSSLEngineConfigurator> engineConfigurators = new ArrayList<AsyncSSLEngineConfigurator>();

    public void addEngineConfigurator(AsyncSSLEngineConfigurator engineConfigurator) {
        engineConfigurators.add(engineConfigurator);
    }

    public void clearEngineConfigurators() {
        engineConfigurators.clear();
    }

    protected SSLEngine createConfiguredSSLEngine() {
        if (sslContext == null) {
            sslContext = defaultSSLContext;
        }

        SSLEngine sslEngine = sslContext.createSSLEngine();
        for (AsyncSSLEngineConfigurator configurator : engineConfigurators) {
            configurator.configureEngine(sslEngine);
        }

        return sslEngine;
    }

    @Override
    protected ConnectCallback wrapCallback(final ConnectCallback callback, final Uri uri, final int port, final boolean proxied) {
        return new ConnectCallback() {
            @Override
            public void onConnectCompleted(Exception ex, final AsyncSocket socket) {
                if (ex == null) {
                    if (!proxied) {
                        callback.onConnectCompleted(null, new AsyncSSLSocketWrapper(socket, uri.getHost(), port, createConfiguredSSLEngine(), trustManagers, hostnameVerifier, true));
                    }
                    else {
                        // this SSL connection is proxied, must issue a CONNECT request to the proxy server
                        // http://stackoverflow.com/a/6594880/704837
                        RawHeaders connect = new RawHeaders();
                        connect.setStatusLine(String.format("CONNECT %s:%s HTTP/1.1", uri.getHost(), port));
                        Util.writeAll(socket, connect.toHeaderString().getBytes(), new CompletedCallback() {
                            @Override
                            public void onCompleted(Exception ex) {
                                if (ex != null) {
                                    callback.onConnectCompleted(ex, socket);
                                    return;
                                }

                                LineEmitter liner = new LineEmitter();
                                liner.setLineCallback(new LineEmitter.StringCallback() {
                                    String statusLine;
                                    @Override
                                    public void onStringAvailable(String s) {
                                        if (statusLine == null) {
                                            statusLine = s;
                                            if (statusLine.length() > 128 || !statusLine.contains("200")) {
                                                socket.setDataCallback(null);
                                                socket.setEndCallback(null);
                                                callback.onConnectCompleted(new IOException("non 200 status line"), socket);
                                            }
                                        }
                                        else {
                                            socket.setDataCallback(null);
                                            socket.setEndCallback(null);
                                            if (TextUtils.isEmpty(s.trim())) {
                                                callback.onConnectCompleted(null, new AsyncSSLSocketWrapper(socket, uri.getHost(), port, createConfiguredSSLEngine(), trustManagers, hostnameVerifier, true));
                                            }
                                            else {
                                                callback.onConnectCompleted(new IOException("unknown second status line"), socket);
                                            }
                                        }
                                    }
                                });

                                socket.setDataCallback(liner);

                                socket.setEndCallback(new CompletedCallback() {
                                    @Override
                                    public void onCompleted(Exception ex) {
                                        if (!socket.isOpen() && ex == null)
                                            ex = new IOException("socket closed before proxy connect response");
                                        callback.onConnectCompleted(ex, socket);
                                    }
                                });

//                                AsyncSocket wrapper = socket;
//                                if (ex == null)
//                                    wrapper = new AsyncSSLSocketWrapper(socket, uri.getHost(), port, sslContext, trustManagers, hostnameVerifier, true);
//                                callback.onConnectCompleted(ex, wrapper);
                            }
                        });
                    }
                }
                else {
                    callback.onConnectCompleted(ex, socket);
                }
            }
        };
    }
}
