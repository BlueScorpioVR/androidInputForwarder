package net.philippe.headsetinput;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.*;
import java.io.ByteArrayInputStream;
import java.security.cert.*;
import java.util.UUID;

final class HostConfig {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("headset-input",Context.MODE_PRIVATE); }
    static String identity(Context c) {
        SharedPreferences p=prefs(c); String id=p.getString("client-id",null);
        if(id==null) {id=UUID.randomUUID().toString().replace("-","").substring(0,16);p.edit().putString("client-id",id).commit();}
        return id;
    }
    static String host(Context c) { return prefs(c).getString("host",""); }
    static X509Certificate certificate(Context c,String host) throws Exception {
        String encoded=prefs(c).getString("cert:"+host,null);
        return encoded==null?null:(X509Certificate)CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(Base64.decode(encoded,Base64.DEFAULT)));
    }
    static NvHTTP http(Context c,String host,boolean requirePair) throws Exception {
        NvHTTP.disableVerboseLogging();
        X509Certificate cert=certificate(c,host);
        if(requirePair && cert==null)throw new IllegalStateException("Pair this PC first");
        return new NvHTTP(new ComputerDetails.AddressTuple(host,47989),0,identity(c),cert,new AndroidCryptoProvider(c));
    }
    static void saveCertificate(Context c,String host,X509Certificate cert) throws Exception {
        prefs(c).edit().putString("cert:"+host,Base64.encodeToString(cert.getEncoded(),Base64.NO_WRAP)).commit();
    }
}
