package com.example.mediahub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Trust = the device's own certificates PLUS a bundled list of modern root CAs.
 * Android 4.x does not know recent roots (e.g. ISRG Root X1 of Let's Encrypt), so https fails there;
 * this adds them inside the app, without root and without touching the system.
 */
final class TrustBundle {

    private TrustBundle() { }

    /** Parses every PEM certificate block; a block that cannot be parsed is skipped, not fatal. */
    static List<X509Certificate> parse(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        String text = new String(out.toByteArray(), "UTF-8");

        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        CertificateFactory cf;
        try {
            cf = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
        final String begin = "-----BEGIN CERTIFICATE-----";
        final String end = "-----END CERTIFICATE-----";
        int i = 0;
        while (true) {
            int b = text.indexOf(begin, i);
            if (b < 0) break;
            int e = text.indexOf(end, b);
            if (e < 0) break;
            e += end.length();
            try {
                byte[] block = text.substring(b, e).getBytes("US-ASCII");
                certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(block)));
            } catch (Exception skip) {
                // unreadable block: ignore
            }
            i = e;
        }
        return certs;
    }

    /** A trust manager that accepts a chain if the device trusts it OR a bundled root does. */
    static X509TrustManager composite(List<X509Certificate> extra) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int n = 0;
        for (X509Certificate c : extra) ks.setCertificateEntry("bundled" + (n++), c);

        final X509TrustManager system = pick(null);   // the device's own store
        final X509TrustManager bundled = pick(ks);
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException first) {
                    bundled.checkServerTrusted(chain, authType);   // throws if still untrusted
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] a = system.getAcceptedIssuers();
                X509Certificate[] b = bundled.getAcceptedIssuers();
                X509Certificate[] all = new X509Certificate[a.length + b.length];
                System.arraycopy(a, 0, all, 0, a.length);
                System.arraycopy(b, 0, all, a.length, b.length);
                return all;
            }
        };
    }

    private static X509TrustManager pick(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager tm : f.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("No X509TrustManager available");
    }
}
